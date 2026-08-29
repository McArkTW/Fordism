import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { apiError } from '../core/api-error';
import { Icon } from '../core/icon';
import { AuthService } from './auth.service';

/** A draft of the first administrator, held together so one signal carries the whole form. */
type Draft = { secret: string; email: string; displayName: string; password: string };

/**
 * First run only. A fresh instance has no accounts, so there is nobody who could be allowed to
 * create one — the deploy-time admin secret stands in for that first authorization. Posting it
 * with an account mints the initial administrator and its session in one step; from then on
 * /api/auth/providers reports bootstrapRequired: false and this page sends you to /login.
 */
@Component({
  selector: 'app-bootstrap',
  imports: [HlmButton, HlmInput, HlmLabel, Icon],
  templateUrl: './bootstrap.html',
})
export class Bootstrap {
  private auth = inject(AuthService);
  private router = inject(Router);

  readonly draft = signal<Draft>({ secret: '', email: '', displayName: '', password: '' });
  /** null while /api/auth/providers is still out; false sends us to /login. */
  readonly required = signal<boolean | null>(null);
  readonly loadFailed = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    this.auth.providers().subscribe({
      next: (p) => {
        if (!p.bootstrapRequired) {
          // Someone already claimed this instance — the ordinary way in is the only way in.
          this.router.navigateByUrl('/login');
          return;
        }
        this.required.set(true);
      },
      error: () => this.loadFailed.set(true),
    });
  }

  patch(change: Partial<Draft>): void {
    this.draft.update((d) => ({ ...d, ...change }));
  }

  complete(): boolean {
    const d = this.draft();
    return !!(d.secret.trim() && d.email.trim() && d.displayName.trim() && d.password);
  }

  submit(): void {
    if (this.busy() || !this.complete()) {
      return;
    }
    const d = this.draft();
    this.busy.set(true);
    this.error.set(null);
    this.auth.bootstrap(d.secret.trim(), d.email.trim(), d.password, d.displayName.trim()).then(
      () => this.router.navigateByUrl('/'),
      (e: unknown) => {
        this.busy.set(false);
        // Whatever core says — a wrong secret and an already-claimed instance read
        // very differently, and only it knows which happened.
        this.error.set(apiError(e, 'Could not create the first administrator'));
      },
    );
  }
}
