import { Component, computed, inject, signal } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { apiError } from '../../core/api-error';
import { AuthService } from '../../auth/auth.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';

/**
 * The account's own security — today, just two-factor. Self-service: it acts on whoever is signed
 * in, never on another account.
 *
 * The one screen that must not be misread is the recovery-code list: it is shown exactly once,
 * after enrolment, because the server keeps only hashes. So it stays on screen until the person
 * confirms they have saved it, and nothing here can bring it back.
 */
@Component({
  selector: 'app-security',
  imports: [Icon, HlmButton, HlmInput, HlmLabel, HlmSpinner],
  templateUrl: './security.html',
})
export class Security {
  private auth = inject(AuthService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  readonly enabled = computed(() => this.auth.user()?.mfaEnabled ?? false);
  readonly busy = signal(false);

  /** Enrolment in progress: the secret/URI to scan, and the code the person types back. */
  readonly enrolling = signal<{ secret: string; otpauthUri: string } | null>(null);
  readonly code = signal('');

  /** The recovery codes, shown once after a successful enrolment until dismissed. */
  readonly recoveryCodes = signal<string[] | null>(null);

  /** A rough QR via a public chart is NOT used — offline installs have no internet. Show the secret. */
  begin(): void {
    this.busy.set(true);
    this.auth.beginMfa().subscribe({
      next: (started) => {
        this.busy.set(false);
        this.enrolling.set(started);
        this.code.set('');
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'Could not start enrolment'));
      },
    });
  }

  confirm2fa(): void {
    const code = this.code().trim();
    if (!code) {
      return;
    }
    this.busy.set(true);
    this.auth.confirmMfa(code).then(
      (codes) => {
        this.busy.set(false);
        this.enrolling.set(null);
        this.recoveryCodes.set(codes);
        this.toasts.ok('Two-factor is on');
      },
      (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'That code did not match'));
      },
    );
  }

  cancelEnrol(): void {
    this.enrolling.set(null);
    this.code.set('');
  }

  dismissRecovery(): void {
    this.recoveryCodes.set(null);
  }

  async disable(): Promise<void> {
    const password = await this.confirm.ask(
      'Turn off two-factor',
      'This removes the second factor from your account. Confirm with your password on the next screen.',
      'Continue',
      true,
    );
    if (!password) {
      return;
    }
    // The server also accepts a current code; the password prompt is the simpler path here.
    const entered = window.prompt('Enter your password to turn off two-factor:');
    if (!entered) {
      return;
    }
    this.busy.set(true);
    this.auth.disableMfa({ password: entered }).then(
      () => {
        this.busy.set(false);
        this.toasts.ok('Two-factor is off');
      },
      (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'Could not turn off two-factor'));
      },
    );
  }
}
