import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { HlmTooltip } from '@spartan-ng/helm/tooltip';
import { apiError } from '../../core/api-error';
import {
  SkillDetail,
  SkillPlugin,
  SkillSource,
  SkillSummary,
  SkillsService,
} from '../../core/api/skills.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';
import { Markdown } from '../../shared/markdown';

/**
 * Skills — the library a user maintains. Write a skill here or upload its folder, edit it, delete
 * it; add a plugin by git URL and its skills arrive under the plugin's own name. Disable one to
 * keep it out of future runs without losing it.
 */
@Component({
  selector: 'app-skills',
  imports: [
    DatePipe,
    FormsModule,
    Icon,
    Markdown,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmSpinner,
    HlmSwitch,
    HlmTextarea,
    HlmTooltip,
  ],
  templateUrl: './skills.html',
})
export class Skills {
  private service = inject(SkillsService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  readonly skills = signal<SkillSummary[]>([]);
  /** The first list request is still out — an empty list means nothing yet. */
  readonly loading = signal(true);
  /** Why the library could not be read. Rendered instead of the "nothing synced" empty state. */
  readonly loadError = signal('');
  readonly filter = signal('');
  readonly selected = signal<string | null>(null);
  readonly detail = signal<SkillDetail | null>(null);
  readonly source = signal<SkillSource>({});

  readonly plugins = signal<SkillPlugin[]>([]);
  readonly busyPlugin = signal('');
  readonly addingPlugin = signal(false);
  readonly pluginUrl = signal('');
  readonly pluginRef = signal('');

  /** The open editor: a new skill when creating, otherwise the selected one. */
  readonly editing = signal(false);
  readonly creating = signal(false);
  readonly saving = signal(false);
  readonly draftName = signal('');
  readonly draftContent = signal('');

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
    this.service.plugins().subscribe({
      next: (list) => this.plugins.set(list),
      error: () => {
        // The plugins strip stays empty; the library itself still lists.
      },
    });
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

  startCreate(): void {
    this.creating.set(true);
    this.editing.set(true);
    this.draftName.set('');
    this.draftContent.set(NEW_SKILL);
  }

  startEdit(): void {
    const d = this.detail();
    if (!d) {
      return;
    }
    this.creating.set(false);
    this.editing.set(true);
    this.draftName.set(d.name);
    this.draftContent.set(d.content);
  }

  cancelEdit(): void {
    this.editing.set(false);
    this.creating.set(false);
  }

  save(): void {
    const name = this.draftName().trim();
    if (!name) {
      this.toasts.error('A skill needs a name');
      return;
    }
    this.saving.set(true);
    this.service.save(name, this.draftContent()).subscribe({
      next: () => {
        this.saving.set(false);
        this.editing.set(false);
        this.creating.set(false);
        this.toasts.ok(`Saved “${name}”`);
        this.reload();
        this.select(name);
      },
      error: (e) => {
        this.saving.set(false);
        this.toasts.error(apiError(e, 'Could not save the skill'));
      },
    });
  }

  /**
   * A picked folder. The user names the skill, so the folder's own name is dropped from every
   * path — what matters is that SKILL.md lands at the root of the skill.
   */
  uploadFolder(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (!files.length) {
      return;
    }
    const name = (this.draftName().trim() || folderOf(files[0])).trim();
    if (!name) {
      this.toasts.error('A skill needs a name');
      return;
    }
    this.saving.set(true);
    this.service.upload(name, files).subscribe({
      next: () => {
        this.saving.set(false);
        this.editing.set(false);
        this.creating.set(false);
        this.toasts.ok(`Uploaded “${name}” (${files.length} files)`);
        this.reload();
        this.select(name);
      },
      error: (e) => {
        this.saving.set(false);
        this.toasts.error(apiError(e, 'Could not upload the folder'));
      },
    });
  }

  async remove(name: string): Promise<void> {
    const ok = await this.confirm.ask(
      `Delete “${name}”?`,
      'The skill and its files are removed from the library. A template that names it will log a warning and run without it.',
      'Delete',
      true,
    );
    if (!ok) {
      return;
    }
    this.service.remove(name).subscribe({
      next: () => {
        this.toasts.ok(`Deleted “${name}”`);
        this.selected.set(null);
        this.detail.set(null);
        this.editing.set(false);
        this.reload();
      },
      error: (e) => this.toasts.error(apiError(e, 'Could not delete the skill')),
    });
  }

  addPlugin(): void {
    const url = this.pluginUrl().trim();
    if (!url) {
      this.toasts.error('Paste the plugin git URL');
      return;
    }
    this.addingPlugin.set(true);
    this.service.addPlugin(url, this.pluginRef().trim()).subscribe({
      next: (plugin) => {
        this.addingPlugin.set(false);
        this.pluginUrl.set('');
        this.pluginRef.set('');
        this.reportPlugin(plugin, `Added “${plugin.name}”`);
      },
      error: (e) => {
        this.addingPlugin.set(false);
        this.toasts.error(apiError(e, 'Could not add the plugin'));
      },
    });
  }

  syncPlugin(plugin: SkillPlugin): void {
    this.busyPlugin.set(plugin.id);
    this.service.syncPlugin(plugin.id).subscribe({
      next: (synced) => {
        this.busyPlugin.set('');
        this.reportPlugin(synced, `Synced “${synced.name}”`);
      },
      error: (e) => {
        this.busyPlugin.set('');
        this.toasts.error(apiError(e, 'Could not sync the plugin'));
      },
    });
  }

  async removePlugin(plugin: SkillPlugin): Promise<void> {
    const owned = this.skills().filter((s) => s.name.startsWith(`${plugin.name}/`)).length;
    const ok = await this.confirm.ask(
      `Remove “${plugin.name}”?`,
      `${owned} skill${owned === 1 ? '' : 's'} installed by this plugin will be deleted. Skills you wrote yourself are untouched.`,
      'Remove',
      true,
    );
    if (!ok) {
      return;
    }
    this.busyPlugin.set(plugin.id);
    this.service.removePlugin(plugin.id).subscribe({
      next: () => {
        this.busyPlugin.set('');
        this.toasts.ok(`Removed “${plugin.name}”`);
        this.selected.set(null);
        this.detail.set(null);
        this.reload();
      },
      error: (e) => {
        this.busyPlugin.set('');
        this.toasts.error(apiError(e, 'Could not remove the plugin'));
      },
    });
  }

  /**
   * A sync that fails answers 200 with the reason on the plugin — a plugin that quietly stopped
   * updating looks exactly like one that is current, so the failure is raised as an error here.
   */
  private reportPlugin(plugin: SkillPlugin, success: string): void {
    if (plugin.lastError) {
      this.toasts.error(`${plugin.name}: ${plugin.lastError}`);
    } else {
      this.toasts.ok(success);
    }
    this.reload();
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

/** The skeleton a new skill opens with: the frontmatter Claude matches on, and nothing else. */
const NEW_SKILL = ['---', 'description: ', '---', '', ''].join('\n');

/** The picked folder own name, used when the user did not type one. */
function folderOf(file: File): string {
  const full = (file as File & { webkitRelativePath?: string }).webkitRelativePath ?? '';
  return full.includes('/') ? full.slice(0, full.indexOf('/')) : '';
}
