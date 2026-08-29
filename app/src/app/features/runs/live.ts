import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { apiError } from '../../core/api-error';
import { RunSummary, RunsService } from '../../core/api/runs.service';
import { Icon } from '../../core/icon';
import { statusView } from '../../core/status';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';

const REFRESH_MS = 10_000;

/**
 * What is happening right now, and what is waiting on you.
 *
 * Deliberately has no filter and no paging: this is the screen you glance at, and anything that
 * makes you configure it first defeats the point. History is where you go looking.
 *
 * Asked sorts above running because a question does not resolve itself — a running task finishes
 * on its own, a parked one waits for a person forever. Within the asked group the OLDEST is first:
 * the longest wait is the most urgent, which is the opposite of every other list here.
 */
@Component({
  selector: 'app-live',
  imports: [RouterLink, Icon, MatButtonModule, MatTooltipModule],
  host: { class: 'block' },
  template: `
    <div class="mx-auto w-full max-w-5xl">
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 class="page-h flex items-center gap-2">
            <app-icon name="radio" class="text-accent" />
            Live
          </h1>
          <p class="mt-0.5 text-sm text-muted">What is running, and what is waiting on you.</p>
        </div>
        <div class="flex items-center gap-2 text-xs text-muted">
          <span>{{ asked().length }} waiting · {{ running().length }} running</span>
          <span class="size-2 animate-pulse rounded-full bg-emerald-500"></span>
        </div>
      </div>

      @if (error()) {
        <div class="card mt-4 border-red-500/40 px-3 py-2 text-sm text-red-500">{{ error() }}</div>
      }

      @if (idle()) {
        <div class="card mt-6 flex min-h-[40vh] flex-col items-center justify-center px-6 text-center">
          <app-icon name="moon" class="text-3xl text-muted" />
          <div class="mt-3 text-sm font-medium">Nothing needs you</div>
          <div class="mt-1 text-xs text-muted">
            Nothing running, nothing waiting. Start something from
            <a routerLink="/workflows" class="text-accent underline">Workflows</a>, or look through
            <a routerLink="/runs" class="text-accent underline">History</a>.
          </div>
        </div>
      }

      @if (asked().length) {
        <div class="mt-6 text-sm font-semibold tracking-wide text-muted uppercase">Waiting on you · longest first</div>
        <div class="mt-2 space-y-2">
          @for (r of asked(); track r.id) {
            <a
              [routerLink]="['/runs', r.id]"
              class="flex flex-wrap items-center gap-3 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 transition hover:border-amber-500/70"
            >
              <app-icon name="message-circle-question-mark" class="shrink-0 text-amber-500" />
              <span class="font-medium">{{ r.workflow }}</span>
              <span class="font-mono text-[11px] text-muted">{{ r.id }}</span>
              <span class="flex-auto"></span>
              <span class="text-xs text-amber-500">asked {{ ago(r.createdAt) }}</span>
              <button matIconButton type="button" matTooltip="Stop this run" (click)="abandon(r, $event)">
                <app-icon name="circle-slash" />
              </button>
            </a>
          }
        </div>
      }

      @if (running().length) {
        <div class="mt-6 text-sm font-semibold tracking-wide text-muted uppercase">Running · newest first</div>
        <div class="card mt-2 divide-y divide-edge">
          @for (r of running(); track r.id) {
            @let v = view(r.state);
            <a
              [routerLink]="['/runs', r.id]"
              class="flex flex-wrap items-center gap-3 px-4 py-3 transition hover:bg-ink/5"
            >
              <span [class]="v.badge">
                <app-icon [name]="v.icon" [class.spin]="v.icon === 'loader'" />
                {{ v.label }}
              </span>
              <span class="font-medium">{{ r.workflow }}</span>
              <span class="font-mono text-[11px] text-muted">{{ r.id }}</span>
              <span class="flex-auto"></span>
              <span class="text-xs text-muted">started {{ ago(r.createdAt) }}</span>
              <button matIconButton type="button" matTooltip="Stop this run" (click)="abandon(r, $event)">
                <app-icon name="circle-slash" />
              </button>
            </a>
          }
        </div>
      }
    </div>
  `,
})
export class Live implements OnDestroy {
  private service = inject(RunsService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);
  private timer: ReturnType<typeof setInterval>;

  readonly runs = signal<RunSummary[]>([]);
  readonly error = signal<string>('');
  readonly loaded = signal<boolean>(false);

  readonly asked = computed(() =>
    this.runs()
      .filter((run) => run.state === 'ASKED')
      .sort((a, b) => a.createdAt - b.createdAt),
  );

  readonly running = computed(() =>
    this.runs()
      .filter((run) => run.state !== 'ASKED')
      .sort((a, b) => b.createdAt - a.createdAt),
  );

  readonly idle = computed(() => this.loaded() && this.runs().length === 0);

  constructor() {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), REFRESH_MS);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  refresh(): void {
    this.service.live().subscribe({
      next: (runs) => {
        this.runs.set(runs);
        this.loaded.set(true);
        this.error.set('');
      },
      error: (e) => this.error.set(apiError(e, 'Could not load live runs')),
    });
  }

  /** The rows are links; the stop button must neither bubble nor follow the href. */
  async abandon(run: RunSummary, event: Event): Promise<void> {
    event.preventDefault();
    event.stopPropagation();
    const ok = await this.confirm.ask(
      'Stop this run?',
      'The run ends immediately; whatever is still running is culled on the next tick. Nothing is deleted.',
      'Stop run',
      true,
    );
    if (!ok) {
      return;
    }
    this.service.abandon(run.id).subscribe({
      next: () => {
        this.toasts.ok('Run stopped');
        this.refresh();
      },
      error: (e) => this.toasts.error(apiError(e, 'Could not stop the run')),
    });
  }

  view(state: string) {
    return statusView(state);
  }

  ago(ms: number): string {
    if (!ms) {
      return '';
    }
    const sec = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (sec < 60) {
      return `${sec}s ago`;
    }
    const min = Math.floor(sec / 60);
    if (min < 60) {
      return `${min}m ago`;
    }
    const hr = Math.floor(min / 60);
    if (hr < 24) {
      return `${hr}h ${min % 60}m ago`;
    }
    return `${Math.floor(hr / 24)}d ago`;
  }
}
