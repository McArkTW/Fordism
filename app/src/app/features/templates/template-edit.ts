import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { apiError } from '../../core/api-error';
import {
  AgentProfileOption,
  CredentialOption,
  SkillSummary,
  TemplatesService,
} from '../../core/api/templates.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';

/**
 * One Agent Template — the preset a workflow step runs with: an agent profile (referenced by
 * name), an optional model override, library skills, granted credentials, and standing
 * instructions. Serves both /templates/new (no id) and /templates/:id.
 */
@Component({
  selector: 'app-template-edit',
  imports: [
    RouterLink,
    Icon,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './template-edit.html',
  // The stock filled button is the accent color; delete must read as destructive.
  styles: `
    .btn-danger-mat {
      --mat-button-filled-container-color: var(--color-red-600, #dc2626);
      --mat-button-filled-label-text-color: #fff;
    }
  `,
})
export class TemplateEdit {
  /** Route param; absent on /templates/new. Bound after construction, so loading happens in an effect. */
  readonly id = input<string>();

  private service = inject(TemplatesService);
  private router = inject(Router);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  readonly name = signal('');
  readonly agentProfile = signal('');
  readonly model = signal('');
  readonly skills = signal<string[]>([]);
  readonly credentials = signal<string[]>([]);
  readonly instructions = signal('');

  readonly profileOptions = signal<AgentProfileOption[]>([]);
  readonly skillOptions = signal<SkillSummary[]>([]);
  readonly credentialOptions = signal<CredentialOption[]>([]);
  readonly skillFilter = signal('');
  readonly credentialFilter = signal('');
  readonly busy = signal(false);

  readonly isNew = computed(() => !this.id());

  readonly filteredSkills = computed(() => {
    const q = this.skillFilter().trim().toLowerCase();
    return this.skillOptions().filter(
      (s) =>
        !q || s.name.toLowerCase().includes(q) || (s.description ?? '').toLowerCase().includes(q),
    );
  });

  readonly filteredCredentials = computed(() => {
    const q = this.credentialFilter().trim().toLowerCase();
    return this.credentialOptions().filter(
      (c) => !q || c.key.toLowerCase().includes(q) || (c.note ?? '').toLowerCase().includes(q),
    );
  });

  /** The form as loaded — anything different is unsaved work, flagged in the header. */
  private readonly pristine = signal('');
  private readonly snapshot = computed(() =>
    JSON.stringify({
      name: this.name(),
      agentProfile: this.agentProfile(),
      model: this.model(),
      skills: [...this.skills()].sort(),
      credentials: [...this.credentials()].sort(),
      instructions: this.instructions(),
    }),
  );
  readonly dirty = computed(() => this.snapshot() !== this.pristine());

  private loaded = '';

  constructor() {
    // Option lists fail silently: a picker stays empty but the form still saves.
    this.service.sources().subscribe({
      next: (list) => this.profileOptions.set(list),
      error: () => {},
    });
    this.service.skills().subscribe({
      next: (list) => this.skillOptions.set(list),
      error: () => {},
    });
    this.service.credentials().subscribe({
      next: (list) => this.credentialOptions.set(list),
      error: () => {},
    });
    // Routed inputs bind after the constructor, so the record loads in an effect.
    effect(() => {
      const id = this.id();
      if (id && this.loaded !== id) {
        this.loaded = id;
        this.load(id);
      }
    });
    this.markPristine();
  }

  private markPristine(): void {
    this.pristine.set(this.snapshot());
  }

  private load(id: string): void {
    this.service.get(id).subscribe({
      next: (t) => {
        this.name.set(t.name);
        this.agentProfile.set(t.agentProfile ?? '');
        this.model.set(t.model ?? '');
        this.skills.set(t.skills ?? []);
        this.credentials.set(t.credentials ?? []);
        this.instructions.set(t.instructions ?? '');
        this.markPristine();
      },
      error: (e) => {
        // Nothing to edit (deleted or bad link) — say so and go back to the list.
        this.toasts.error(apiError(e, 'Template not found'));
        this.router.navigate(['/templates']);
      },
    });
  }

  toggleSkill(name: string): void {
    const current = this.skills();
    this.skills.set(
      current.includes(name) ? current.filter((s) => s !== name) : [...current, name],
    );
  }

  toggleCredential(key: string): void {
    const current = this.credentials();
    this.credentials.set(
      current.includes(key) ? current.filter((c) => c !== key) : [...current, key],
    );
  }

  save(): void {
    if (!this.name().trim()) {
      this.toasts.error('Name the template first.');
      return;
    }
    this.busy.set(true);
    const payload = {
      name: this.name(),
      agentProfile: this.agentProfile(),
      model: this.model(),
      skills: this.skills(),
      credentials: this.credentials(),
      instructions: this.instructions(),
    };
    const id = this.id();
    const request = id ? this.service.update(id, payload) : this.service.create(payload);
    request.subscribe({
      next: () => {
        this.busy.set(false);
        this.markPristine();
        this.toasts.ok(`Saved “${this.name()}”`);
        this.router.navigate(['/templates']);
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'Could not save the template'));
      },
    });
  }

  async remove(): Promise<void> {
    const id = this.id();
    if (!id) {
      return;
    }
    const ok = await this.confirm.ask(
      'Delete template',
      `Delete “${this.name()}”?`,
      'Delete',
      true,
    );
    if (!ok) {
      return;
    }
    this.busy.set(true);
    this.service.remove(id).subscribe({
      next: () => {
        this.busy.set(false);
        this.toasts.ok(`Deleted “${this.name()}”`);
        this.router.navigate(['/templates']);
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'Could not delete the template'));
      },
    });
  }
}
