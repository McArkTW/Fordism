import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTableImports } from '@spartan-ng/helm/table';
import { HlmTooltip } from '@spartan-ng/helm/tooltip';
import { apiError } from '../../core/api-error';
import { ApiTokenView, ApiTokensService } from '../../core/api/api-tokens.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { AuthService } from '../../auth/auth.service';
import { PERMISSION_LEAVES, PermissionService } from '../../auth/permission';
import { Confirm } from '../../shared/confirm';

/** The create form. `grants` is the raw text; one pattern per line or comma-separated. */
type Draft = { name: string; grants: string; expiresInDays: string };

/**
 * API tokens — how a script, a CI job or a cron calls this API without a browser.
 *
 * Two things this page has to make unmissable, because both are irreversible in one direction.
 * The value appears exactly once, when the token is created: the server keeps only a hash, so a
 * value that is not copied now is gone and the token has to be replaced. And a token NARROWS —
 * what it may do is the intersection of its grants and the account's — so a token cannot be used
 * to gain a permission, only to hold fewer than you do.
 */
@Component({
  selector: 'app-api-tokens',
  imports: [DatePipe, Icon, HlmButton, HlmInput, HlmLabel, HlmSpinner, HlmTableImports, HlmTooltip],
  templateUrl: './api-tokens.html',
})
export class ApiTokens {
  private service = inject(ApiTokensService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);
  private auth = inject(AuthService);
  private matcher = inject(PermissionService);

  readonly items = signal<ApiTokenView[]>([]);
  readonly draft = signal<Draft | null>(null);
  readonly busy = signal(false);
  readonly loading = signal(true);
  readonly loadError = signal('');

  /**
   * The value of the token just created. Held only in this component and cleared the moment the
   * banner is dismissed or another token is made — nothing stores it, because nothing can get it
   * back if the person loses it, and a value lingering on screen is a value on a shared monitor.
   */
  readonly minted = signal<{ name: string; value: string } | null>(null);

  /** What the account can actually do — the ceiling every token here sits under. */
  readonly myGrants = computed(() => this.auth.user()?.permissions ?? []);

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: (list) => {
        this.items.set(list);
        this.loadError.set('');
        this.loading.set(false);
      },
      error: (e) => {
        // Not "you have no tokens": that reading invites minting a second one for a job that
        // already has a working first.
        this.loadError.set(apiError(e, 'Could not load your API tokens'));
        this.loading.set(false);
      },
    });
  }

  startCreate(): void {
    this.minted.set(null);
    this.draft.set({ name: '', grants: '', expiresInDays: '' });
  }

  cancel(): void {
    this.draft.set(null);
  }

  patch(change: Partial<Draft>): void {
    this.draft.update((d) => (d ? { ...d, ...change } : d));
  }

  dismissMinted(): void {
    this.minted.set(null);
  }

  copy(value: string): void {
    navigator.clipboard.writeText(value).then(
      () => this.toasts.ok('Copied'),
      () => this.toasts.error('Could not copy — select the value and copy it by hand.'),
    );
  }

  /** The permissions a grant pattern would actually cover, so a typo is visible before saving. */
  covered(pattern: string): string[] {
    return PERMISSION_LEAVES.filter((leaf) => this.matcher.matches(pattern, leaf));
  }

  create(): void {
    const d = this.draft();
    if (!d) {
      return;
    }
    if (!d.name.trim()) {
      this.toasts.error('Give the token a name — it is how you will recognise it later.');
      return;
    }
    this.busy.set(true);
    this.service
      .create({
        name: d.name.trim(),
        grants: d.grants
          .split(/[\s,]+/)
          .map((g) => g.trim())
          .filter(Boolean),
        expiresInDays: Number(d.expiresInDays) || 0,
      })
      .subscribe({
        next: (created) => {
          this.busy.set(false);
          this.draft.set(null);
          this.minted.set({ name: created.token.name, value: created.value });
          this.refresh();
        },
        error: (e) => {
          this.busy.set(false);
          this.toasts.error(apiError(e, 'Could not create the token'));
        },
      });
  }

  async revoke(token: ApiTokenView): Promise<void> {
    const ok = await this.confirm.ask(
      'Revoke token',
      `Revoke "${token.name}"? Anything using it stops working immediately, and it cannot be restored.`,
      'Revoke',
      true,
    );
    if (!ok) {
      return;
    }
    this.service.revoke(token.id).subscribe({
      next: () => {
        this.toasts.ok(`Revoked ${token.name}`);
        this.refresh();
      },
      error: (e) => this.toasts.error(apiError(e, 'Could not revoke the token')),
    });
  }
}
