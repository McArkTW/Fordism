import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { apiError } from '../core/api-error';
import { Icon } from '../core/icon';
import { AuthService, MfaRequired } from './auth.service';

/** Friendly renderings of the ?error=<code> a failed OAuth callback lands with. */
const CALLBACK_ERRORS: Record<string, string> = {
  not_allowed: 'That account is not allowed here. Ask an administrator to invite you.',
  exchange_failed: 'The provider would not complete the sign-in. Please try again.',
  invalid_state: 'The sign-in attempt expired or was tampered with. Please start again.',
  access_denied: 'Sign-in was cancelled at the provider.',
};

/**
 * The OAuth providers this screen can render, and what each button says.
 *
 * A table rather than a chain of comparisons: it is also the filter, so a provider core offers
 * that the app has no wording for is left off the screen rather than rendered as a bare id — and
 * adding one is this one line. `local` is deliberately absent; it is the form, not a button.
 */
const OAUTH_LABELS: Record<string, string> = {
  google: 'Continue with Google',
  github: 'Continue with GitHub',
  microsoft: 'Continue with Microsoft',
};

/**
 * Sign in. What it offers comes from /api/auth/providers: a local email/password form,
 * buttons for OAuth providers, or both. OAuth is a full-page round trip — the server owns
 * the redirect dance; a failure comes back here as ?error=<code>.
 */
@Component({
  selector: 'app-login',
  imports: [HlmButton, HlmInput, HlmLabel, Icon],
  templateUrl: './login.html',
})
export class Login {
  private auth = inject(AuthService);
  private router = inject(Router);

  /** Bound from the query string by withComponentInputBinding. */
  readonly error = input<string>();
  readonly returnUrl = input<string>();

  readonly info = signal<{ local: boolean; oauth: string[] } | null>(null);
  readonly loadFailed = signal(false);
  readonly email = signal('');
  readonly password = signal('');
  readonly busy = signal(false);
  /** The password checked out and a second factor is now being asked for. */
  readonly mfaStep = signal(false);
  /** The authenticator code, or a recovery code when the phone is gone. */
  readonly code = signal('');
  readonly recoveryCode = signal('');
  readonly useRecovery = signal(false);
  /** A failure from the form itself; once set it replaces the callback error. */
  readonly localError = signal<string | null>(null);

  readonly message = computed(() => {
    const local = this.localError();
    if (local) {
      return local;
    }
    const code = this.error();
    if (!code) {
      return null;
    }
    return CALLBACK_ERRORS[code] ?? `Sign-in failed (${code}). Please try again.`;
  });

  constructor() {
    // A fresh /me first: if the session is alive this page has no business showing, and if
    // it died (the interceptor sent us here) this clears the stale user before guards trust it.
    this.auth.refresh().then(() => {
      if (this.auth.user()) {
        this.router.navigateByUrl('/');
        return;
      }
      this.auth.providers().subscribe({
        next: (p) => {
          if (p.bootstrapRequired) {
            this.router.navigateByUrl('/bootstrap');
            return;
          }
          const ids = p.providers.map((x) => x.id);
          this.info.set({
            local: ids.includes('local'),
            oauth: ids.filter((id) => id in OAUTH_LABELS),
          });
        },
        error: () => this.loadFailed.set(true),
      });
    });
  }

  providerLabel(id: string): string {
    return OAUTH_LABELS[id] ?? id;
  }

  startOauth(id: string): void {
    // A full-page navigation, not an XHR: the server redirects to the provider and back.
    window.location.assign(`/api/auth/${id}/login`);
  }

  submit(): void {
    if (this.busy() || !this.email().trim() || !this.password()) {
      return;
    }
    // On the second step the code (or recovery code) is required before we bother the server.
    if (this.mfaStep() && !this.code().trim() && !this.recoveryCode().trim()) {
      return;
    }
    this.busy.set(true);
    this.localError.set(null);
    const second = this.mfaStep()
      ? { code: this.code().trim() || undefined, recoveryCode: this.recoveryCode().trim() || undefined }
      : undefined;
    this.auth.login(this.email().trim(), this.password(), second).then(
      () => this.router.navigateByUrl(this.returnUrl() || '/'),
      (e: unknown) => {
        this.busy.set(false);
        if (e instanceof MfaRequired) {
          // The password was right; ask for the second factor and keep the form.
          this.mfaStep.set(true);
          this.localError.set(null);
          return;
        }
        const wrongCredentials = e instanceof HttpErrorResponse && e.status === 401;
        this.localError.set(
          !this.mfaStep() && wrongCredentials
            ? 'Wrong email or password.'
            : this.mfaStep() && wrongCredentials
              ? 'That code did not match. Try again, or use a recovery code.'
              : apiError(e, 'Could not sign in'),
        );
      },
    );
  }

  /** Switch the second-factor field between an authenticator code and a recovery code. */
  toggleRecovery(): void {
    this.useRecovery.set(!this.useRecovery());
    this.code.set('');
    this.recoveryCode.set('');
    this.localError.set(null);
  }
}
