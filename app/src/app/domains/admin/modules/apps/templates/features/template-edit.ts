import {
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatCheckbox } from '@angular/material/checkbox';
import { MatFormField } from '@angular/material/form-field';
import { MatIcon } from '@angular/material/icon';
import { MatInput } from '@angular/material/input';
import { MatOption, MatSelect } from '@angular/material/select';
import { Router } from '@angular/router';
import { errorMessage } from '@/app/core/api-error';
import {
  AgentProfileOption,
  CredentialOption,
  SkillSummary,
  TemplatesService,
} from '../data/templates.service';

type SkillGroup = { prefix: string; skills: SkillSummary[] };

/**
 * Agent Templates — the reusable presets a workflow step runs with. Each template picks an
 * agent profile (which carries its own model), library skills, credentials, and standing instructions.
 */
@Component({
  selector: 'template-edit',
  imports: [
    MatButton,
    MatIcon,
    MatFormField,
    MatInput,
    MatSelect,
    MatOption,
    MatCheckbox,
  ],
  host: { class: 'block' },
  templateUrl: './template-edit.html',
})
export default class TemplateEdit {
  /** Route parameter; absent on /templates/new. Bound after the constructor, so load in an effect. */
  id = input<string | undefined>();

  private service = inject(TemplatesService);
  private router = inject(Router);

  name = signal<string>('');
  agentProfile = signal<string>('');
  model = signal<string>('');
  skills = signal<string[]>([]);
  credentials = signal<string[]>([]);
  instructions = signal<string>('');
  sources = signal<AgentProfileOption[]>([]);
  skillOptions = signal<SkillSummary[]>([]);
  credentialOptions = signal<CredentialOption[]>([]);
  credentialFilter = signal<string>('');
  skillFilter = signal<string>('');
  error = signal<string>('');
  message = signal<string>('');
  busy = signal<boolean>(false);

  /** Skills filtered by the search box and grouped by their category prefix. */
  filteredGroups = computed<SkillGroup[]>(() => {
    const q = this.skillFilter().trim().toLowerCase();
    const groups = new Map<string, SkillSummary[]>();
    for (const s of this.skillOptions()) {
      if (
        q &&
        !s.name.toLowerCase().includes(q) &&
        !(s.description ?? '').toLowerCase().includes(q)
      ) {
        continue;
      }
      const prefix = s.name.includes('-')
        ? s.name.slice(0, s.name.indexOf('-'))
        : 'other';
      (groups.get(prefix) ?? groups.set(prefix, []).get(prefix)!).push(s);
    }
    return [...groups.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([prefix, skills]) => ({ prefix, skills }));
  });

  /** Credentials filtered by the search box. */
  filteredCredentials = computed<CredentialOption[]>(() => {
    const q = this.credentialFilter().trim().toLowerCase();
    return this.credentialOptions().filter(
      (c) =>
        !q ||
        c.key.toLowerCase().includes(q) ||
        (c.note ?? '').toLowerCase().includes(q)
    );
  });

  /** What the form looked like when it was loaded — anything else means unsaved work. */
  private pristine = signal<string>('');

  /** Read by the deactivate guard, so leaving with edits in flight asks first. */
  dirty = computed(() => this.snapshot() !== this.pristine());

  isNew = computed(() => !this.id());

  private snapshot = computed(() =>
    JSON.stringify({
      name: this.name(),
      agentProfile: this.agentProfile(),
      model: this.model(),
      skills: [...this.skills()].sort(),
      credentials: [...this.credentials()].sort(),
      instructions: this.instructions(),
    })
  );

  constructor() {
    this.service.sources().subscribe({
      next: (list) => this.sources.set(list),
      error: () => {
        /* the picker stays empty; the form still saves */
      },
    });
    this.service.skills().subscribe({
      next: (list) => this.skillOptions.set(list),
      error: () => {
        /* the picker stays empty; the form still saves */
      },
    });
    this.service.credentials().subscribe({
      next: (list) =>
        this.credentialOptions.set(list.filter((c) => c.hasValue)),
      error: () => {
        /* the picker stays empty; the form still saves */
      },
    });
    // Routed inputs bind after the constructor, so the load happens here rather than above.
    effect(() => {
      const id = this.id();
      if (!id || this.loaded === id) {
        return;
      }
      this.loaded = id;
      this.load(id);
    });
  }

  private loaded = '';

  /** Remember the loaded shape so `dirty` can tell a real edit from an untouched form. */
  private markPristine(): void {
    this.pristine.set(this.snapshot());
  }

  private load(id: string): void {
    this.clear();
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
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  /** Picking a source binds the template to that source's single model. */
  onSourceChange(source: string): void {
    this.agentProfile.set(source);
    const match = this.sources().find((s) => s.name === source);
    this.model.set(match ? match.model : '');
  }

  toggleCredential(key: string): void {
    const current = this.credentials();
    this.credentials.set(
      current.includes(key)
        ? current.filter((c) => c !== key)
        : [...current, key]
    );
  }

  toggleSkill(skill: string): void {
    const current = this.skills();
    this.skills.set(
      current.includes(skill)
        ? current.filter((s) => s !== skill)
        : [...current, skill]
    );
  }

  save(): void {
    this.clear();
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
    const request = id
      ? this.service.update(id, payload)
      : this.service.create(payload);
    request.subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.markPristine(); // saved work is not unsaved work; the guard must let go
        if (!id) {
          this.router.navigate(['/templates', saved.id]);
          return;
        }
        this.message.set('Saved “' + this.name() + '”');
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  remove(): void {
    const target = this.id();
    if (!target || !confirm(`Delete “${this.name()}”?`)) {
      return;
    }
    this.clear();
    this.busy.set(true);
    this.service.remove(target).subscribe({
      next: () => {
        this.busy.set(false);
        this.markPristine();
        this.router.navigate(['/templates']);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  private clear(): void {
    this.error.set('');
    this.message.set('');
  }
}
