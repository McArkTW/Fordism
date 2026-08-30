import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCheckbox } from '@spartan-ng/helm/checkbox';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
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
    HlmButton,
    HlmCheckbox,
    HlmInput,
    HlmLabel,
    HlmSelectImports,
    HlmTextarea,
  ],
  templateUrl: './template-edit.html',
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
  // Per picker, because they are three independent requests: one failing says nothing about the
  // other two, and an empty picker must never be read as "there are none" until its own answered.
  readonly profileOptionsLoading = signal(true);
  readonly skillOptionsLoading = signal(true);
  readonly credentialOptionsLoading = signal(true);
  readonly profileOptionsError = signal('');
  readonly skillOptionsError = signal('');
  readonly credentialOptionsError = signal('');
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
    this.loadOptions();
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

  /**
   * The three pickers' option lists.
   *
   * These used to swallow their errors (`error: () => {}`), so an unreachable core produced
   * three empty pickers under three confident sentences: no profiles, no skills, no credentials —
   * "add one on the X page". That sends an operator off to recreate things that already exist. A
   * failure now names itself under the picker it belongs to; the form still saves either way,
   * which is why this never blocked the page and still does not.
   */
  private loadOptions(): void {
    this.profileOptionsLoading.set(true);
    this.service.sources().subscribe({
      next: (list) => {
        this.profileOptions.set(list);
        this.profileOptionsError.set('');
        this.profileOptionsLoading.set(false);
      },
      error: (e) => {
        this.profileOptionsError.set(apiError(e, 'Could not load agent profiles'));
        this.profileOptionsLoading.set(false);
      },
    });
    this.skillOptionsLoading.set(true);
    this.service.skills().subscribe({
      next: (list) => {
        this.skillOptions.set(list);
        this.skillOptionsError.set('');
        this.skillOptionsLoading.set(false);
      },
      error: (e) => {
        this.skillOptionsError.set(apiError(e, 'Could not load the skills library'));
        this.skillOptionsLoading.set(false);
      },
    });
    this.credentialOptionsLoading.set(true);
    this.service.credentials().subscribe({
      next: (list) => {
        this.credentialOptions.set(list);
        this.credentialOptionsError.set('');
        this.credentialOptionsLoading.set(false);
      },
      error: (e) => {
        this.credentialOptionsError.set(apiError(e, 'Could not load credentials'));
        this.credentialOptionsLoading.set(false);
      },
    });
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

  /** brn-select emits null when nothing is selected; the form stores '' for "sole profile / default". */
  setProfile(value: unknown): void {
    this.agentProfile.set(typeof value === 'string' ? value : '');
  }

  /** Closed-trigger label: "name · model", like the option rows (the value itself is just the name). */
  readonly profileLabel = (name: string): string => {
    const p = this.profileOptions().find((o) => o.name === name);
    return p ? `${p.name} · ${p.model || 'no model'}` : name;
  };

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
