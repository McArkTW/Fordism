import { Component, OnDestroy, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { apiError } from '../../core/api-error';
import { RunDetail, RunsService, TaskView } from '../../core/api/runs.service';
import { Icon } from '../../core/icon';
import { formatDuration, statusView } from '../../core/status';
import { Toasts } from '../../core/toast';

/** A finished run never changes again, so there is nothing to poll for. */
const REFRESH_MS = 5_000;
const TERMINAL = ['DONE', 'FAILED', 'ABANDONED'];

/**
 * One run: its steps, what each produced, and a pointer to the reply box when one stopped to ask.
 *
 * The single destination both Live and History link to, so there is only one place a run is ever
 * rendered and the two lists cannot drift apart in what they show.
 */
@Component({
  selector: 'app-run-detail',
  imports: [RouterLink, Icon, DatePipe],
  host: { class: 'block' },
  templateUrl: './run-detail.html',
})
export class RunDetailPage implements OnDestroy {
  readonly id = input.required<string>();

  private service = inject(RunsService);
  private toasts = inject(Toasts);
  private timer: ReturnType<typeof setInterval>;

  readonly detail = signal<RunDetail | null>(null);
  readonly error = signal<string>('');
  readonly showSnapshot = signal<boolean>(false);
  readonly abandoning = signal<boolean>(false);

  readonly sortedTasks = computed<TaskView[]>(() =>
    [...(this.detail()?.tasks ?? [])].sort((a, b) => a.step - b.step),
  );

  readonly askedTasks = computed<TaskView[]>(() =>
    this.sortedTasks().filter((t) => t.state === 'ASKED'),
  );

  /** Only a live run is worth stopping, and only a live run is worth polling. */
  readonly isLive = computed(() => {
    const state = this.detail()?.state;
    return !!state && !TERMINAL.includes(state);
  });

  private loaded = '';

  constructor() {
    // Routed inputs bind after the constructor, so load from an effect rather than reading id().
    effect(() => {
      const id = this.id();
      if (this.loaded === id) {
        return;
      }
      this.loaded = id;
      this.detail.set(null);
      this.refresh();
    });
    this.timer = setInterval(() => {
      if (this.isLive()) {
        this.refresh();
      }
    }, REFRESH_MS);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  refresh(): void {
    this.service.get(this.id()).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.error.set('');
      },
      error: (e) => this.error.set(apiError(e, 'Could not load the run')),
    });
  }

  abandon(): void {
    const run = this.detail();
    if (!run || !window.confirm(`Stop ${run.workflow}? Anything still running is killed.`)) {
      return;
    }
    this.abandoning.set(true);
    this.service.abandon(run.id).subscribe({
      next: () => {
        this.abandoning.set(false);
        this.toasts.ok('Run stopped');
        this.refresh();
      },
      error: (e) => {
        this.abandoning.set(false);
        this.toasts.error(apiError(e, 'Could not stop the run'));
      },
    });
  }

  copy(text: string): void {
    navigator.clipboard.writeText(text).then(
      () => this.toasts.ok('Copied to clipboard'),
      () => this.toasts.error('Could not copy'),
    );
  }

  view(state: string) {
    return statusView(state, this.detail()?.state);
  }

  dur(ms: number | null): string {
    return formatDuration(ms);
  }

  oneLine(text: string | null): string {
    if (!text) {
      return '';
    }
    const collapsed = text.replace(/\s+/g, ' ').trim();
    return collapsed.length > 160 ? collapsed.slice(0, 160) + '…' : collapsed;
  }
}
