import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { apiError } from '../../core/api-error';
import { CredentialView, CredentialsService } from '../../core/api/credentials.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';

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
  imports: [DatePipe, Icon],
  templateUrl: './credentials.html',
})
export class Credentials {
  private service = inject(CredentialsService);
  private toasts = inject(Toasts);

  readonly items = signal<CredentialView[]>([]);
  readonly draft = signal<Draft | null>(null);
  readonly editing = signal(false);
  readonly busy = signal(false);

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.service.list().subscribe({
      next: (list) => this.items.set(list),
      error: (e) => this.toasts.error(apiError(e, 'Could not load credentials')),
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

  remove(c: CredentialView): void {
    const warning = c.usedBy.length
      ? ` It is still granted by: ${c.usedBy.join(', ')} — runs from those templates will lose it.`
      : '';
    if (!window.confirm(`Delete ${c.key}?${warning}`)) {
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
