import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTooltip } from '@spartan-ng/helm/tooltip';
import { apiError } from '../../core/api-error';
import { CredentialView, CredentialsService } from '../../core/api/credentials.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';

/** A row being edited or created. `value` is only ever outbound — nothing populates it from core. */
type Draft = { key: string; value: string; note: string };

/**
 * Credentials — the secrets the instance hands to agents as environment variables. Values are
 * write-only: no endpoint returns one, so the field is blank even when a value is stored, and
 * saving it blank keeps what is stored. Who receives a credential is decided on the Agent
 * Template that declares its key; this page only reports which templates ask for it.
 */
@Component({
  selector: 'app-credentials',
  imports: [DatePipe, Icon, HlmButton, HlmInput, HlmLabel, HlmSpinner, HlmTooltip],
  templateUrl: './credentials.html',
})
export class Credentials {
  private service = inject(CredentialsService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  readonly items = signal<CredentialView[]>([]);
  readonly draft = signal<Draft | null>(null);
  readonly editing = signal(false);
  readonly busy = signal(false);
  /** The first list request is still out — an empty table means nothing yet. */
  readonly loading = signal(true);
  /** Why the list could not be read. Rendered instead of the "none stored" empty state. */
  readonly loadError = signal('');

  constructor() {
    this.refresh();
  }

  /**
   * A failed list used to leave the table empty, which the page explained as "No credentials
   * stored" — the one reading an operator must not be given, because it invites re-entering
   * secrets that are already there. Show the failure instead.
   */
  refresh(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: (list) => {
        this.items.set(list);
        this.loadError.set('');
        this.loading.set(false);
      },
      error: (e) => {
        this.loadError.set(apiError(e, 'Could not load credentials'));
        this.loading.set(false);
      },
    });
  }

  startCreate(): void {
    this.editing.set(false);
    this.draft.set({ key: '', value: '', note: '' });
  }

  startEdit(c: CredentialView): void {
    this.editing.set(true);
    // value stays blank: core never returns it, and blank on save means "keep what is stored".
    this.draft.set({ key: c.key, value: '', note: c.note });
  }

  cancel(): void {
    this.draft.set(null);
  }

  patch(change: Partial<Draft>): void {
    this.draft.update((d) => (d ? { ...d, ...change } : d));
  }

  validKey(key: string): boolean {
    return /^[A-Z][A-Z0-9_]*$/.test(key);
  }

  save(): void {
    const d = this.draft();
    if (!d) {
      return;
    }
    if (!this.validKey(d.key)) {
      this.toasts.error('Key must be an UPPERCASE environment-variable name (A–Z, digits, _).');
      return;
    }
    if (!this.editing() && !d.value.trim()) {
      // A new credential with no value would be a key that silently grants nothing.
      this.toasts.error('A new credential needs a value.');
      return;
    }
    this.busy.set(true);
    this.service.save(d.key, { value: d.value, note: d.note }).subscribe({
      next: () => {
        this.busy.set(false);
        this.draft.set(null);
        this.toasts.ok(`Saved ${d.key}`);
        this.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'Could not save the credential'));
      },
    });
  }

  async remove(c: CredentialView): Promise<void> {
    const warning = c.usedBy.length
      ? ` It is still granted by: ${c.usedBy.join(', ')} — runs from those templates will lose it.`
      : '';
    const ok = await this.confirm.ask(
      'Delete credential',
      `Delete ${c.key}?${warning}`,
      'Delete',
      true,
    );
    if (!ok) {
      return;
    }
    this.service.remove(c.key).subscribe({
      next: () => {
        this.toasts.ok(`Deleted ${c.key}`);
        this.refresh();
      },
      error: (e) => this.toasts.error(apiError(e, 'Could not delete the credential')),
    });
  }
}
