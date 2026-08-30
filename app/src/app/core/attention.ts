import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';

/**
 * The one always-on poll: how many runs are parked waiting on a human. Drives the badge on the
 * Live nav item so a question is noticed from anywhere in the app.
 *
 * A failed poll used to set the count to 0 — which renders as no badge at all, i.e. the most
 * reassuring reading of an unreachable core there is: nothing distinguished "nobody is waiting"
 * from "nobody could be asked". A failure now leaves the last count core actually reported
 * standing and raises {@link unknown}, so the number shown is only ever one core said, and
 * `unknown` says whether it is still true. Read the two together: show `askedCount()` while
 * `!unknown()`, and an unknown marker — never a zero — otherwise.
 */
@Injectable({ providedIn: 'root' })
export class Attention {
  private http = inject(HttpClient);
  private auth = inject(AuthService);

  /** The last count core reported. Held through a failed poll rather than reset to zero. */
  readonly askedCount = signal(0);

  /** No answer from core — `askedCount` is stale, and means nothing before the first poll lands. */
  readonly unknown = signal(true);

  constructor() {
    this.refresh();
    setInterval(() => this.refresh(), 10_000);
  }

  refresh(): void {
    // Signed out (the login page, say) the poll would only earn a 401 every ten seconds, and
    // the interceptor would answer each one with a redirect we are already at. Nothing is waiting
    // on a signed-out visitor, so that is a known zero rather than an unknown.
    if (!this.auth.user()) {
      this.askedCount.set(0);
      this.unknown.set(false);
      return;
    }
    this.http.get<unknown[]>('/api/runs', { params: { state: 'ASKED' } }).subscribe({
      next: (runs) => {
        this.askedCount.set(runs.length);
        this.unknown.set(false);
      },
      error: () => this.unknown.set(true),
    });
  }
}
