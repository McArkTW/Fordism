import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmTooltip } from '@spartan-ng/helm/tooltip';
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
  imports: [RouterLink, Icon, HlmButton, HlmSkeleton, HlmTooltip],
  host: { class: 'block' },
  template: `
    <div class="mx-auto w-full max-w-5xl">
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 class="page-h flex items-center gap-2">
            <app-icon name="radio" class="text-primary" />
            Live
          </h1>
          <p class="text-muted-foreground mt-0.5 text-sm">What is running, and what is waiting on you.</p>
        </div>
        <div class="text-muted-foreground flex items-center gap-2 text-xs">
          <span>{{ asked().length }} waiting · {{ running().length }} running</span>
          <span class="size-2 animate-pulse rounded-full bg-emerald-500"></span>
        </div>
      </div>

      @if (error()) {
        <div class="border-destructive/40 bg-destructive/10 text-destructive mt-4 rounded-xl border px-3 py-2 text-sm">
          {{ error() }}
        </div>
      }

      @if (!loaded() && !error()) {
        <!-- First poll in flight — sketch the two row groups instead of flashing empty. -->
        <div class="mt-6 space-y-2">
          <div hlmSkeleton class="h-12 w-full rounded-xl"></div>
          <div hlmSkeleton class="h-12 w-full rounded-xl"></div>
          <div hlmSkeleton class="h-12 w-full rounded-xl"></div>
        </div>
      }

      @if (idle()) {
        <section
          class="border-border bg-card mt-6 flex min-h-[40vh] flex-col items-center justify-center rounded-xl border border-dashed px-6 text-center"
        >
          <div class="bg-muted flex size-12 items-center justify-center rounded-full">
            <app-icon name="moon" class="text-muted-foreground text-xl" />
          </div>
          <div class="mt-4 text-sm font-medium">Nothing needs you</div>
          <p class="text-muted-foreground mt-1 max-w-sm text-xs">
            Nothing running, nothing waiting. Start something from Workflows, or look through what
            already ran in History.
          </p>
          <div class="mt-4 flex items-center gap-2">
            <a hlmBtn variant="outline" size="sm" routerLink="/workflows">
              <app-icon name="workflow" />
              Workflows
            </a>
            <a hlmBtn variant="ghost" size="sm" routerLink="/runs">
              <app-icon name="history" />
              History
            </a>
          </div>
        </section>
      }

      @if (asked().length) {
        <div class="text-muted-foreground mt-6 text-sm font-semibold tracking-wide uppercase">
          Waiting on you · longest first
        </div>
        <div class="mt-2 space-y-2">
          @for (r of asked(); track r.id) {
            <a
              [routerLink]="['/runs', r.id]"
              class="flex flex-wrap items-center gap-3 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 transition hover:border-amber-500/70"
            >
              <app-icon name="message-circle-question-mark" class="shrink-0 text-amber-500" />
              <span class="font-medium">{{ r.workflow }}</span>
              <span class="text-muted-foreground font-mono text-[11px]">{{ r.id }}</span>
              <span class="flex-auto"></span>
              <span class="text-xs text-amber-600 dark:text-amber-400">asked {{ ago(r.createdAt) }}</span>
              <button
                hlmBtn
                variant="destructive"
                size="icon-sm"
                type="button"
                hlmTooltip="Stop this run"
                (click)="abandon(r, $event)"
              >
                <app-icon name="circle-slash" />
              </button>
            </a>
          }
        </div>
      }

      @if (running().length) {
        <div class="text-muted-foreground mt-6 text-sm font-semibold tracking-wide uppercase">
          Running · newest first
        </div>
        <div class="divide-border border-border bg-card mt-2 divide-y overflow-hidden rounded-xl border">
          @for (r of running(); track r.id) {
            @let v = view(r.state);
            <a
              [routerLink]="['/runs', r.id]"
              class="hover:bg-foreground/5 flex flex-wrap items-center gap-3 px-4 py-3 transition"
            >
              <span [class]="v.badge">
                <app-icon [name]="v.icon" [class.spin]="v.icon === 'loader'" />
                {{ v.label }}
              </span>
              <span class="font-medium">{{ r.workflow }}</span>
              <span class="text-muted-foreground font-mono text-[11px]">{{ r.id }}</span>
              <span class="flex-auto"></span>
              <span class="text-muted-foreground text-xs">started {{ ago(r.createdAt) }}</span>
              <button
                hlmBtn
                variant="destructive"
                size="icon-sm"
                type="button"
                hlmTooltip="Stop this run"
                (click)="abandon(r, $event)"
              >
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
