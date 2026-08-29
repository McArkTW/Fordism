import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { HlmTooltip } from '@spartan-ng/helm/tooltip';
import { apiError } from '../../core/api-error';
import {
  SkillDetail,
  SkillSource,
  SkillSummary,
  SkillsService,
} from '../../core/api/skills.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Markdown } from '../../shared/markdown';

/**
 * Skills — a read-only browse of the skills library, a mirror of an external repo the operator
 * syncs (pinned to a tag). List every skill, read its SKILL.md rendered, see its files, and
 * disable one to keep it out of future runs. Editing happens upstream, not here.
 */
@Component({
  selector: 'app-skills',
  imports: [DatePipe, Icon, Markdown, HlmButton, HlmInput, HlmSpinner, HlmSwitch, HlmTooltip],
  templateUrl: './skills.html',
})
export class Skills {
  private service = inject(SkillsService);
  private toasts = inject(Toasts);

  readonly skills = signal<SkillSummary[]>([]);
  /** The first list request is still out — an empty list means nothing yet. */
  readonly loading = signal(true);
  /** Why the library could not be read. Rendered instead of the "nothing synced" empty state. */
  readonly loadError = signal('');
  readonly filter = signal('');
  readonly selected = signal<string | null>(null);
  readonly detail = signal<SkillDetail | null>(null);
  readonly source = signal<SkillSource>({});

  readonly filtered = computed(() => {
    const q = this.filter().trim().toLowerCase();
    return this.skills().filter(
      (s) =>
        !q || s.name.toLowerCase().includes(q) || (s.description ?? '').toLowerCase().includes(q),
    );
  });

  /** SKILL.md body with the YAML frontmatter stripped — the metadata is already shown as fields. */
  readonly body = computed(() => {
    const raw = this.detail()?.content ?? '';
    // A leading BOM would hide the frontmatter fence from the regex.
    const content = raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
    return content.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n?/, '');
  });

  constructor() {
    this.reload();
    this.service.source().subscribe({
      next: (s) => this.source.set(s ?? {}),
      error: () => {
        // The source strip just stays blank.
      },
    });
  }

  /**
   * (Re)read the library.
   *
   * The failure used to leave the list empty and say so with a toast, which the page then
   * explained as "No skills synced yet" — a statement about the library, made when the library
   * could not be read at all. The error is rendered in its place instead.
   */
  reload(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: (list) => {
        this.skills.set(list);
        this.loadError.set('');
        this.loading.set(false);
      },
      error: (e) => {
        this.loadError.set(apiError(e, 'Could not load skills'));
        this.loading.set(false);
      },
    });
  }

  repoUrl(): string {
    const repo = this.source().repo ?? '';
    return repo.startsWith('http') ? repo : `https://github.com/${repo}`;
  }

  repoLabel(): string {
    const repo = this.source().repo ?? '';
    return repo.replace(/^https?:\/\/github\.com\//, '').replace(/\.git$/, '') || repo;
  }

  select(name: string): void {
    this.service.get(name).subscribe({
      next: (d) => {
        this.selected.set(d.name);
        this.detail.set(d);
      },
      error: (e) => this.toasts.error(apiError(e, 'Could not load the skill')),
    });
  }

  toggle(skill: SkillSummary, enabled: boolean): void {
    // Optimistic: flip immediately so the switch never lags, revert if core refuses.
    this.applyEnabled(skill.name, enabled);
    this.service.setEnabled(skill.name, enabled).subscribe({
      next: () => this.toasts.ok(`${enabled ? 'Enabled' : 'Disabled'} “${skill.name}”`),
      error: (e) => {
        this.applyEnabled(skill.name, !enabled);
        this.toasts.error(apiError(e, 'Could not update the skill'));
      },
    });
  }

  private applyEnabled(name: string, enabled: boolean): void {
    // New object identity so the switch's [checked] input actually changes — that is what
    // makes the revert visibly snap the control back when core refuses the update.
    this.skills.update((list) => list.map((s) => (s.name === name ? { ...s, enabled } : s)));
    const d = this.detail();
    if (d && d.name === name) {
      this.detail.set({ ...d, enabled });
    }
  }
}
