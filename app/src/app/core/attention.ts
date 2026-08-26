import { HttpClient } from '@angular/common/http';
import { Injectable, OnDestroy, inject, signal } from '@angular/core';

/** Slower than a page poll: this is ambient, and a few seconds late costs nothing. */
const REFRESH_MS = 10000;

/**
 * How many runs are waiting on a human, app-wide.
 *
 * The Live page is where you see *what* is waiting, but you are usually on Workflows or Templates
 * when an agent stops to ask — and a signal you have to go looking for is not a signal. This is the
 * one always-on request in the app, and it exists to put a number on the Live nav item.
 */
@Injectable({ providedIn: 'root' })
export class Attention implements OnDestroy {
  private http = inject(HttpClient);
  private timer: ReturnType<typeof setInterval>;

  /** Runs parked waiting on an answer. Zero means nothing wants you. */
  readonly waiting = signal<number>(0);

  constructor() {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), REFRESH_MS);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  /** Call after answering, so the badge drops without waiting for the next tick. */
  refresh(): void {
    this.http
      .get<unknown[]>('/api/runs', { params: { state: 'ASKED' } })
      .subscribe({
        next: (runs) => this.waiting.set(runs.length),
        error: () => {
          /* a failed count is not worth showing an error for; the next tick tries again */
        },
      });
  }
}
