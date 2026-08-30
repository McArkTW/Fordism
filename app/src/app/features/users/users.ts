import { Component, inject, signal } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { apiError } from '../../core/api-error';
import { User, UsersService } from '../../core/api/users.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';

/** A new account. A blank password means the account can only arrive through an OAuth provider. */
type Draft = { email: string; displayName: string; password: string };

/**
 * Users — the accounts that may sign in. What each one may *do* is decided by group
 * membership, on the Groups page: an account with no groups can sign in and see nothing.
 * Passwords are write-only; the identities column is the honest answer to "how does this
 * person actually get in".
 */
@Component({
  selector: 'app-users',
  imports: [Icon, HlmButton, HlmInput, HlmLabel],
  templateUrl: './users.html',
})
export class Users {
  private service = inject(UsersService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  /** null until the first answer arrives — an empty list and "not loaded yet" are not the same. */
  readonly users = signal<User[] | null>(null);
  readonly loadError = signal<string | null>(null);
  readonly draft = signal<Draft | null>(null);
  readonly busy = signal(false);

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loadError.set(null);
    this.service.list().subscribe({
      next: (list) => this.users.set(list),
      error: (e) => {
        // Leave `users` as it was: a failed reload must not blank the table into an
        // empty state that would read as "there are no users".
        this.loadError.set(apiError(e, 'Could not load users'));
      },
    });
  }

  startCreate(): void {
    this.draft.set({ email: '', displayName: '', password: '' });
  }

  cancel(): void {
    this.draft.set(null);
  }

  patch(change: Partial<Draft>): void {
    this.draft.update((d) => (d ? { ...d, ...change } : d));
  }

  identityLabel(user: User): string[] {
    return user.identities.map((i) => (i.provider === 'local' ? 'password' : i.provider));
  }

  save(): void {
    const d = this.draft();
    if (!d) {
      return;
    }
    if (!d.email.trim() || !d.displayName.trim()) {
      this.toasts.error('An account needs an email and a display name.');
      return;
    }
    this.busy.set(true);
    this.service
      .create({
        email: d.email.trim(),
        displayName: d.displayName.trim(),
        // Omitted rather than sent empty: an empty string is a password, "no password" is not.
        ...(d.password ? { password: d.password } : {}),
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.draft.set(null);
          this.toasts.ok(`Created ${d.email.trim()}`);
          this.refresh();
        },
        error: (e) => {
          this.busy.set(false);
          this.toasts.error(apiError(e, 'Could not create the account'));
        },
      });
  }

  async remove(user: User): Promise<void> {
    const ok = await this.confirm.ask(
      'Delete user',
      `Delete ${user.email}? They lose access immediately, and any group listing them drops the membership.`,
      'Delete',
      true,
    );
    if (!ok) {
      return;
    }
    this.service.remove(user.id).subscribe({
      next: () => {
        this.toasts.ok(`Deleted ${user.email}`);
        this.refresh();
      },
      error: (e) => this.toasts.error(apiError(e, 'Could not delete the account')),
    });
  }
}
