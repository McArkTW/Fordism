import {
  Component,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { format } from 'date-fns';
import { errorMessage } from '@/app/core/api-error';
import { copyText } from '@/app/core/clipboard';
import { formatDuration, statusView } from '../data/run-status';
import {
  RunDetail as RunDetailModel,
  RunsService,
  TaskView,
} from '../data/runs.service';
import { TaskDetail } from './task-detail';

/** A finished run never changes again, so there is nothing to poll for. */
const REFRESH_MS = 3000;
const TERMINAL = ['DONE', 'FAILED', 'ABANDONED'];

/**
 * One run: its steps, what each produced, and the reply box when one stopped to ask.
 *
 * The single destination both Live and History link to, so there is only one place a run is ever
 * rendered and the two lists cannot drift apart in what they show.
 */
@Component({
  selector: 'run-detail',
  imports: [MatButton, MatIcon, MatTooltip, RouterLink, TaskDetail],
  host: { class: 'block' },
  templateUrl: './run-detail.html',
})
export default class RunDetailPage implements OnDestroy {
  /** Route parameters, bound by withComponentInputBinding. */
  id = input<string>('');
  taskId = input<string>('');

  private service = inject(RunsService);
  private timer: ReturnType<typeof setInterval>;
  private copyTimer: ReturnType<typeof setTimeout> | null = null;

  detail = signal<RunDetailModel | null>(null);
  open = signal<string | null>(null);
  showSnapshot = signal<boolean>(false);
  copied = signal<string | null>(null);
  error = signal<string>('');
  abandoning = signal<boolean>(false);

  sortedTasks = computed<TaskView[]>(() =>
    [...(this.detail()?.tasks ?? [])].sort((a, b) => a.step - b.step)
  );

  /** Only a live run is worth stopping, and only a live run is worth polling. */
  isLive = computed(() => {
    const state = this.detail()?.state;
    return !!state && !TERMINAL.includes(state);
  });

  private loaded = '';

  constructor() {
    // Routed inputs bind after the constructor, so load from an effect rather than reading id().
    effect(() => {
      const id = this.id();
      if (!id || this.loaded === id) {
        return;
      }
      this.loaded = id;
      this.refresh();
    });
    // A task deep-link opens that step; the run still has to arrive first.
    effect(() => {
      const task = this.taskId();
      if (task) {
        this.open.set(task);
      }
    });
    this.timer = setInterval(() => {
      if (this.isLive()) {
        this.refresh();
      }
    }, REFRESH_MS);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    if (this.copyTimer) {
      clearTimeout(this.copyTimer);
    }
  }

  refresh(): void {
    const id = this.id();
    if (!id) {
      return;
    }
    this.service.get(id).subscribe({
      next: (detail) => this.detail.set(detail),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  abandon(): void {
    const run = this.detail();
    if (
      !run ||
      !confirm(`Stop ${run.workflow}? Anything still running is killed.`)
    ) {
      return;
    }
    this.abandoning.set(true);
    this.service.abandon(run.id).subscribe({
      next: () => {
        this.abandoning.set(false);
        this.refresh();
      },
      error: (e) => {
        this.abandoning.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  toggle(taskId: string): void {
    this.open.set(this.open() === taskId ? null : taskId);
  }

  copy(id: string): void {
    copyText(id);
    this.copied.set(id);
    if (this.copyTimer) {
      clearTimeout(this.copyTimer);
    }
    this.copyTimer = setTimeout(() => this.copied.set(null), 1500);
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

  label(state: string): string {
    return statusView(state, this.detail()?.state).label;
  }

  badge(state: string): string {
    return statusView(state, this.detail()?.state).badge;
  }

  icon(state: string): string {
    return statusView(state, this.detail()?.state).icon;
  }

  absTime(ms: number): string {
    return ms ? format(ms, 'MMM d, yyyy · h:mm:ss a') : '';
  }
}
