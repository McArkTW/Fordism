import { Component, inject, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';
import { format } from 'date-fns';
import { errorMessage } from '@/app/core/api-error';
import {
  CredentialView,
  CredentialsService,
} from '../data/credentials.service';

/** A row being edited or created. `value` is only ever outbound — nothing populates it from core. */
type Draft = { key: string; value: string; note: string };

/**
 * Credentials — the secrets the instance hands to agents as environment variables.
 *
 * A value can be written and never read: no endpoint returns one, so the field is blank even for a
 * credential that is set, and saving it blank keeps what is stored. Who receives it is decided on
 * the Agent template that needs it; this page only reports which templates ask for it.
 */
@Component({
  selector: 'credentials',
  imports: [MatButton, MatIcon, MatTooltip],
  host: { class: 'block' },
  templateUrl: './credentials.html',
})
export default class Credentials {
  private service = inject(CredentialsService);

  items = signal<CredentialView[]>([]);
  draft = signal<Draft | null>(null);
  editing = signal<boolean>(false);
  busy = signal<boolean>(false);
  error = signal<string>('');

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.service.list().subscribe({ next: (list) => this.items.set(list) });
  }

  startCreate(): void {
    this.error.set('');
    this.editing.set(false);
    this.draft.set({ key: '', value: '', note: '' });
  }

  startEdit(c: CredentialView): void {
    this.error.set('');
    this.editing.set(true);
    // value stays blank: core never returns it, and blank on save means "keep what is stored".
    this.draft.set({ key: c.key, value: '', note: c.note });
  }

  cancel(): void {
    this.draft.set(null);
    this.error.set('');
  }

  patch(change: Partial<Draft>): void {
    this.draft.update((d) => (d ? { ...d, ...change } : d));
  }

  validKey(key: string): boolean {
    return /^[A-Za-z_][A-Za-z0-9_]*$/.test(key);
  }

  canSave(): boolean {
    const d = this.draft();
    if (!d || !this.validKey(d.key)) {
      return false;
    }
    // A new credential with no value would be a key that silently grants nothing.
    return this.editing() || !!d.value.trim();
  }

  save(): void {
    const d = this.draft();
    if (!d) {
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.service.save(d.key, { value: d.value, note: d.note }).subscribe({
      next: () => {
        this.busy.set(false);
        this.draft.set(null);
        this.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  remove(c: CredentialView): void {
    if (
      !confirm(
        `Delete ${c.key}? Templates granting it will fail to start a run.`
      )
    ) {
      return;
    }
    this.service.remove(c.key).subscribe({ next: () => this.refresh() });
  }

  absTime(ms: number): string {
    return ms ? format(ms, 'MMM d, yyyy · h:mm a') : '';
  }
}
