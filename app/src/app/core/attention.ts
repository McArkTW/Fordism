import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

/**
 * The one always-on poll: how many runs are parked waiting on a human. Drives the badge on the
 * Live nav item so a question is noticed from anywhere in the app.
 */
@Injectable({ providedIn: 'root' })
export class Attention {
  private http = inject(HttpClient);
  readonly askedCount = signal(0);

  constructor() {
    this.refresh();
    setInterval(() => this.refresh(), 10_000);
  }

  refresh(): void {
    this.http.get<unknown[]>('/api/runs', { params: { state: 'ASKED' } }).subscribe({
      next: (runs) => this.askedCount.set(runs.length),
      error: () => this.askedCount.set(0),
    });
  }
}
