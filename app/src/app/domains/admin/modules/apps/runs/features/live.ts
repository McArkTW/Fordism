import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { formatDistanceToNow } from 'date-fns';
import { errorMessage } from '@/app/core/api-error';
import { statusView } from '../data/run-status';
import { RunSummary, RunsService } from '../data/runs.service';

const REFRESH_MS = 3000;

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
  selector: 'live',
  imports: [MatButton, MatIcon, MatTooltip, RouterLink],
  host: { class: 'block' },
  templateUrl: './live.html',
})
export default class Live implements OnDestroy {
  private service = inject(RunsService);
  private timer: ReturnType<typeof setInterval>;

  runs = signal<RunSummary[]>([]);
  error = signal<string>('');
  loaded = signal<boolean>(false);

  asked = computed(() =>
    this.runs()
      .filter((run) => run.state === 'ASKED')
      .sort((a, b) => a.createdAt - b.createdAt)
  );

  running = computed(() =>
    this.runs()
      .filter((run) => run.state !== 'ASKED')
      .sort((a, b) => b.createdAt - a.createdAt)
  );

  idle = computed(() => this.loaded() && this.runs().length === 0);

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
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  abandon(run: RunSummary, event: Event): void {
    event.stopPropagation();
    if (!confirm(`Stop ${run.workflow}? Anything still running is killed.`)) {
      return;
    }
    this.service.abandon(run.id).subscribe({
      next: () => this.refresh(),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  badge(state: string): string {
    return statusView(state).badge;
  }

  icon(state: string): string {
    return statusView(state).icon;
  }

  when(ms: number): string {
    return ms ? formatDistanceToNow(ms, { addSuffix: true }) : '';
  }
}
