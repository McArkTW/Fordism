import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
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
  imports: [DatePipe, Icon, Markdown],
  templateUrl: './skills.html',
})
export class Skills {
  private service = inject(SkillsService);
  private toasts = inject(Toasts);

  readonly skills = signal<SkillSummary[]>([]);
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
    this.service.list().subscribe({
      next: (list) => this.skills.set(list),
      error: (e) => this.toasts.error(apiError(e, 'Could not load skills')),
    });
    this.service.source().subscribe({
      next: (s) => this.source.set(s ?? {}),
      error: () => {
        // The source strip just stays blank.
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
    // Optimistic: flip immediately so the checkbox never lags, revert if core refuses.
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
    this.skills.update((list) => list.map((s) => (s.name === name ? { ...s, enabled } : s)));
    const d = this.detail();
    if (d && d.name === name) {
      this.detail.set({ ...d, enabled });
    }
  }
}
