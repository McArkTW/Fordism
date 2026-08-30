import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { apiError } from '../../core/api-error';
import { SkillDetail, SkillFile, SkillsService } from '../../core/api/skills.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';
import { Markdown } from '../../shared/markdown';
import { SKILL_EDIT, SKILL_LIST, SKILL_PLUGINS } from './skill-nav';

/**
 * One skill, read-only: its SKILL.md rendered, and every other file in the folder readable beside
 * it — a skill is whatever was uploaded, and its scripts are the half the contract does not show.
 */
@Component({
  selector: 'app-skill-detail',
  imports: [DatePipe, RouterLink, Icon, Markdown, HlmButton, HlmSpinner, HlmSwitch],
  templateUrl: './skill-detail.html',
})
export class SkillDetailPage {
  private service = inject(SkillsService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  protected readonly listPath = SKILL_LIST;
  protected readonly editPath = SKILL_EDIT;
  protected readonly pluginsPath = SKILL_PLUGINS;

  readonly name = signal('');
  readonly skill = signal<SkillDetail | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal('');

  /** The file open below the header; null is SKILL.md, which is rendered as markdown. */
  readonly openFile = signal<string | null>(null);
  readonly fileContent = signal<SkillFile | null>(null);
  readonly fileError = signal('');
  readonly fileLoading = signal(false);

  /** SKILL.md body with the YAML frontmatter stripped — the metadata is already shown as fields. */
  readonly body = computed(() => {
    const raw = this.skill()?.content ?? '';
    // A leading BOM would hide the frontmatter fence from the regex.
    const content = raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
    return content.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n?/, '');
  });

  constructor() {
    // A query parameter, not a path segment: a skill name is namespaced and can hold slashes.
    this.route.queryParamMap.subscribe((params) => {
      const name = params.get('name') ?? '';
      this.name.set(name);
      this.load(name);
    });
  }

  load(name: string): void {
    if (!name) {
      this.loadError.set('No skill named in the link.');
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.showSkillMd();
    this.service.get(name).subscribe({
      next: (d) => {
        this.skill.set(d);
        this.loadError.set('');
        this.loading.set(false);
      },
      error: (e) => {
        this.skill.set(null);
        this.loadError.set(apiError(e, 'Could not load the skill'));
        this.loading.set(false);
      },
    });
  }

  /** Back to the rendered SKILL.md, which the detail response already carries. */
  showSkillMd(): void {
    this.openFile.set(null);
    this.fileContent.set(null);
    this.fileError.set('');
    this.fileLoading.set(false);
  }

  /**
   * Read one file inside this skill. SKILL.md is served from the response already in hand rather
   * than fetched again.
   */
  openSkillFile(path: string): void {
    if (path === 'SKILL.md') {
      this.showSkillMd();
      return;
    }
    this.openFile.set(path);
    this.fileContent.set(null);
    this.fileError.set('');
    this.fileLoading.set(true);
    this.service.file(this.name(), path).subscribe({
      next: (file) => {
        // A slow read for a file the user has already navigated away from must not overwrite it.
        if (this.openFile() === path) {
          this.fileContent.set(file);
          this.fileLoading.set(false);
        }
      },
      error: (e) => {
        if (this.openFile() === path) {
          this.fileError.set(apiError(e, 'Could not read the file'));
          this.fileLoading.set(false);
        }
      },
    });
  }

  toggle(enabled: boolean): void {
    const skill = this.skill();
    if (!skill) {
      return;
    }
    // Optimistic: flip immediately so the switch never lags, revert if core refuses.
    this.skill.set({ ...skill, enabled });
    this.service.setEnabled(skill.name, enabled).subscribe({
      next: () => this.toasts.ok(`${enabled ? 'Enabled' : 'Disabled'} “${skill.name}”`),
      error: (e) => {
        this.skill.set({ ...skill, enabled: !enabled });
        this.toasts.error(apiError(e, 'Could not update the skill'));
      },
    });
  }

  async remove(): Promise<void> {
    const skill = this.skill();
    if (!skill) {
      return;
    }
    const owned = skill.owner
      ? `\n\nThis skill was installed by the ${skill.owner} plugin — deleting it here is undone the next time that plugin syncs. Remove the plugin instead to be rid of it for good.`
      : '';
    const ok = await this.confirm.ask(
      `Delete “${skill.name}”?`,
      `The skill and its ${skill.fileCount} file${skill.fileCount === 1 ? '' : 's'} are removed from the library. A template that names it will log a warning and run without it.${owned}`,
      'Delete',
      true,
    );
    if (!ok) {
      return;
    }
    this.service.remove(skill.name).subscribe({
      next: () => {
        this.toasts.ok(`Deleted “${skill.name}”`);
        this.router.navigate([SKILL_LIST]);
      },
      error: (e) => this.toasts.error(apiError(e, 'Could not delete the skill')),
    });
  }
}
