import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { apiError } from '../../core/api-error';
import { SkillPlugin, SkillSummary, SkillsService } from '../../core/api/skills.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';
import { SKILL_LIST, SKILL_VIEW } from './skill-nav';

/**
 * Skill plugins — the repos the library mirrors: add, sync, remove.
 *
 * <p>Each plugin owns one folder under the library, so a sync only ever replaces what that plugin
 * put there. Removing one asks whether its skills go with it.
 */
@Component({
  selector: 'app-skill-plugins',
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    Icon,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmSpinner,
  ],
  templateUrl: './skill-plugins.html',
})
export class SkillPlugins {
  private service = inject(SkillsService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  protected readonly listPath = SKILL_LIST;
  protected readonly viewPath = SKILL_VIEW;

  readonly plugins = signal<SkillPlugin[]>([]);
  readonly skills = signal<SkillSummary[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal('');

  readonly busyPlugin = signal('');
  readonly adding = signal(false);
  readonly url = signal('');
  readonly ref = signal('');
  /** Which plugin's skill list is expanded. Only one at a time; these lists run to 19 rows. */
  readonly expanded = signal('');

  /** Every skill each plugin owns, so a row can say how much a remove would take with it. */
  readonly owned = computed(() => {
    const byOwner = new Map<string, SkillSummary[]>();
    for (const skill of this.skills()) {
      if (!skill.owner) {
        continue;
      }
      const group = byOwner.get(skill.owner);
      if (group) {
        group.push(skill);
      } else {
        byOwner.set(skill.owner, [skill]);
      }
    }
    return byOwner;
  });

  constructor() {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.service.plugins().subscribe({
      next: (list) => {
        this.plugins.set(list);
        this.loadError.set('');
        this.loading.set(false);
      },
      error: (e) => {
        this.loadError.set(apiError(e, 'Could not load plugins'));
        this.loading.set(false);
      },
    });
    this.service.list().subscribe({
      next: (list) => this.skills.set(list),
      error: () => {
        // The rows lose their skill counts; the plugins themselves still list.
      },
    });
  }

  skillsOf(plugin: SkillPlugin): SkillSummary[] {
    return this.owned().get(plugin.name) ?? [];
  }

  expand(plugin: SkillPlugin): void {
    this.expanded.update((open) => (open === plugin.id ? '' : plugin.id));
  }

  add(): void {
    const url = this.url().trim();
    if (!url) {
      this.toasts.error('Paste the plugin git URL');
      return;
    }
    this.adding.set(true);
    this.service.addPlugin(url, this.ref().trim()).subscribe({
      next: (plugin) => {
        this.adding.set(false);
        this.url.set('');
        this.ref.set('');
        this.report(plugin, `Added “${plugin.name}”`);
      },
      error: (e) => {
        this.adding.set(false);
        this.toasts.error(apiError(e, 'Could not add the plugin'));
      },
    });
  }

  sync(plugin: SkillPlugin): void {
    this.busyPlugin.set(plugin.id);
    this.service.syncPlugin(plugin.id).subscribe({
      next: (synced) => {
        this.busyPlugin.set('');
        this.report(synced, `Synced “${synced.name}”`);
      },
      error: (e) => {
        this.busyPlugin.set('');
        this.toasts.error(apiError(e, 'Could not sync the plugin'));
      },
    });
  }

  /**
   * Remove a plugin, keeping or deleting the skills it installed.
   *
   * <p>Two buttons rather than a checkbox: this decides whether N skills survive, and the
   * consequence of keeping them — the same plugin can no longer be re-added over them — is worth a
   * sentence the user reads before choosing, not a box they tick past.
   */
  async remove(plugin: SkillPlugin): Promise<void> {
    const owned = this.skillsOf(plugin).length;
    const count = `${owned} skill${owned === 1 ? '' : 's'}`;
    const choice = await this.confirm.choose(
      `Remove “${plugin.name}”?`,
      `This plugin installed ${count}. Skills you wrote yourself are untouched either way.\n\nKeeping them makes them your own, editable and deletable like any other — but this plugin can then no longer be added back over them until they are renamed or deleted.`,
      [
        { key: 'keep', label: `Remove, keep ${count}` },
        { key: 'delete', label: `Remove and delete ${count}`, danger: true },
      ],
    );
    if (!choice) {
      return;
    }
    const keep = choice === 'keep';
    this.busyPlugin.set(plugin.id);
    this.service.removePlugin(plugin.id, keep).subscribe({
      next: () => {
        this.busyPlugin.set('');
        this.toasts.ok(
          keep ? `Removed “${plugin.name}” — kept ${count}` : `Removed “${plugin.name}”`,
        );
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
  private report(plugin: SkillPlugin, success: string): void {
    if (plugin.lastError) {
      this.toasts.error(`${plugin.name}: ${plugin.lastError}`);
    } else {
      this.toasts.ok(success);
    }
    this.reload();
  }
}
