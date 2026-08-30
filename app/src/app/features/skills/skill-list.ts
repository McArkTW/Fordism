import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCheckbox } from '@spartan-ng/helm/checkbox';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { HlmTooltip } from '@spartan-ng/helm/tooltip';
import { apiError } from '../../core/api-error';
import {
  SkillPlugin,
  SkillSource,
  SkillSummary,
  SkillsService,
} from '../../core/api/skills.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';
import { SKILL_EDIT, SKILL_NEW, SKILL_PLUGINS, SKILL_VIEW } from './skill-nav';

/** What the list can be ordered by. */
export type SortKey = 'name' | 'owner' | 'fileCount' | 'updatedAt' | 'enabled';

/** One plugin's skills, or — with a null plugin — every skill no plugin owns. */
export type SkillGroup = { plugin: SkillPlugin | null; skills: SkillSummary[] };

/**
 * Skills — the library, searchable, sortable, paged, and selectable.
 *
 * <p>Two ways to read the same fetch: flat, or grouped by the plugin whose folder each skill sits
 * in. Managing the plugins themselves lives on its own page, so each action has one home.
 */
@Component({
  selector: 'app-skill-list',
  imports: [
    DatePipe,
    RouterLink,
    Icon,
    HlmButton,
    HlmCheckbox,
    HlmInput,
    HlmSelectImports,
    HlmSpinner,
    HlmSwitch,
    HlmTooltip,
  ],
  templateUrl: './skill-list.html',
})
export class SkillList {
  private service = inject(SkillsService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  protected readonly viewPath = SKILL_VIEW;
  protected readonly editPath = SKILL_EDIT;
  protected readonly newPath = SKILL_NEW;
  protected readonly pluginsPath = SKILL_PLUGINS;

  readonly skills = signal<SkillSummary[]>([]);
  readonly plugins = signal<SkillPlugin[]>([]);
  /** The first list request is still out — an empty list means nothing yet. */
  readonly loading = signal(true);
  /** Why the library could not be read. Rendered instead of the "nothing synced" empty state. */
  readonly loadError = signal('');
  readonly source = signal<SkillSource>({});

  readonly filter = signal('');
  /** Flat list, or grouped by the plugin that owns each skill. */
  readonly view = signal<'skill' | 'plugin'>('skill');
  readonly sortBy = signal<SortKey>('name');
  readonly sortDescending = signal(false);
  readonly page = signal(0);
  readonly pageSize = signal(25);

  /**
   * The checked skills, shared by both views so switching does not silently drop a selection the
   * user has been building.
   */
  readonly checked = signal<ReadonlySet<string>>(new Set());
  readonly deleting = signal(false);

  readonly filtered = computed(() => {
    const q = this.filter().trim().toLowerCase();
    const matched = this.skills().filter(
      (s) =>
        !q ||
        s.name.toLowerCase().includes(q) ||
        (s.description ?? '').toLowerCase().includes(q) ||
        (s.owner ?? '').toLowerCase().includes(q),
    );
    const key = this.sortBy();
    const direction = this.sortDescending() ? -1 : 1;
    // A copy: sort() mutates, and the source array is the signal's own value.
    return [...matched].sort((a, b) => direction * compare(a, b, key));
  });

  /** How many pages the filter currently yields — at least one, so "1 of 1" is never "1 of 0". */
  readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.filtered().length / this.pageSize())),
  );

  /** The rows actually rendered. Clamped, because a filter can shrink the list under the page. */
  readonly paged = computed(() => {
    const size = this.pageSize();
    const start = Math.min(this.page(), this.pageCount() - 1) * size;
    return this.filtered().slice(start, start + size);
  });

  /**
   * The filtered skills grouped by owner, plugins first in registry order and the user's own
   * skills last. Every skill appears in exactly one group, so the view accounts for the whole
   * library rather than hiding the ones no plugin owns.
   */
  readonly grouped = computed<SkillGroup[]>(() => {
    const byOwner = new Map<string, SkillSummary[]>();
    for (const skill of this.filtered()) {
      const owner = skill.owner ?? '';
      const group = byOwner.get(owner);
      if (group) {
        group.push(skill);
      } else {
        byOwner.set(owner, [skill]);
      }
    }
    const groups: SkillGroup[] = [];
    for (const plugin of this.plugins()) {
      groups.push({ plugin, skills: byOwner.get(plugin.name) ?? [] });
      byOwner.delete(plugin.name);
    }
    // Anything left is owned by no plugin in the registry: the user's own skills, plus any folder
    // a removed plugin left behind — which is exactly what "yours" now means.
    const mine: SkillSummary[] = [];
    for (const rest of byOwner.values()) {
      mine.push(...rest);
    }
    if (mine.length) {
      groups.push({ plugin: null, skills: mine.sort((a, b) => a.name.localeCompare(b.name)) });
    }
    return groups;
  });

  /** Every checked name that is still in the library — a delete elsewhere must not linger here. */
  readonly checkedNames = computed(() => {
    const live = this.checked();
    return this.skills()
      .map((s) => s.name)
      .filter((name) => live.has(name));
  });

  /** True when every skill the current filter matches is checked (and there is at least one). */
  readonly allFilteredChecked = computed(() => {
    const rows = this.filtered();
    const live = this.checked();
    return rows.length > 0 && rows.every((s) => live.has(s.name));
  });

  /**
   * Whether the Owner column is worth its width.
   *
   * <p>A library fed by one plugin gives every row the same owner, and a column whose every value
   * is identical carries no information while costing the same space as one that does. It comes
   * back the moment a second owner exists.
   */
  readonly showOwner = computed(() => {
    const owners = new Set(this.skills().map((s) => s.owner ?? ''));
    return owners.size > 1;
  });

  /** The grid template both the header and every row are laid out on, so the columns line up. */
  readonly columns = computed(() =>
    this.showOwner()
      ? 'grid-cols-[1.5rem_minmax(9rem,18rem)_minmax(0,1fr)_6rem_3rem_5rem_4.5rem]'
      : 'grid-cols-[1.5rem_minmax(9rem,18rem)_minmax(0,1fr)_3rem_5rem_4.5rem]',
  );

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
        // The grouping falls back to one "yours" group; the library itself still lists.
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

  sortOn(key: SortKey): void {
    if (this.sortBy() === key) {
      this.sortDescending.update((descending) => !descending);
    } else {
      this.sortBy.set(key);
      this.sortDescending.set(false);
    }
    this.page.set(0);
  }

  arrow(key: SortKey): string {
    // A fixed-width slot either way, so sorting a column does not shift the header text.
    return this.sortBy() === key ? (this.sortDescending() ? '↓' : '↑') : ' ';
  }

  /** The namespace, shown muted — it repeats on every skill a plugin installed. */
  namespaceOf(name: string): string {
    const cut = name.indexOf('/');
    return cut < 0 ? '' : name.slice(0, cut + 1);
  }

  /** The part of the name that actually differs between rows. */
  leafOf(name: string): string {
    const cut = name.indexOf('/');
    return cut < 0 ? name : name.slice(cut + 1);
  }

  /**
   * Compact age. A library installed in one sync gives every row the same calendar date, which
   * reads as noise; "2h" against "3d" is the difference the column exists to show.
   */
  ago(iso: string): string {
    if (!iso) {
      return '—';
    }
    const then = Date.parse(iso);
    if (Number.isNaN(then)) {
      return '—';
    }
    const minutes = Math.round((Date.now() - then) / 60000);
    if (minutes < 1) {
      return 'now';
    }
    if (minutes < 60) {
      return `${minutes}m`;
    }
    const hours = Math.round(minutes / 60);
    if (hours < 24) {
      return `${hours}h`;
    }
    const days = Math.round(hours / 24);
    if (days < 30) {
      return `${days}d`;
    }
    return new Date(then).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }

  setFilter(value: string): void {
    this.filter.set(value);
    // A filter that shrinks the list under the current page would otherwise render nothing.
    this.page.set(0);
  }

  setPageSize(value: string): void {
    this.pageSize.set(Number(value) || 25);
    this.page.set(0);
  }

  turnPage(delta: number): void {
    this.page.update((p) => Math.min(Math.max(0, p + delta), this.pageCount() - 1));
  }

  isChecked(name: string): boolean {
    return this.checked().has(name);
  }

  check(name: string, on: boolean): void {
    this.checked.update((live) => {
      const next = new Set(live);
      if (on) {
        next.add(name);
      } else {
        next.delete(name);
      }
      return next;
    });
  }

  /**
   * Check or clear every skill the filter matches — not just the visible page. The button says how
   * many, because "select all" across an invisible remainder is how people delete more than they
   * meant to.
   */
  checkAllFiltered(on: boolean): void {
    this.checkNames(
      this.filtered().map((s) => s.name),
      on,
    );
  }

  checkGroup(group: SkillGroup, on: boolean): void {
    this.checkNames(
      group.skills.map((s) => s.name),
      on,
    );
  }

  groupChecked(group: SkillGroup): boolean {
    const live = this.checked();
    return group.skills.length > 0 && group.skills.every((s) => live.has(s.name));
  }

  clearChecked(): void {
    this.checked.set(new Set());
  }

  /**
   * Delete every checked skill in one request.
   *
   * <p>A plugin-owned skill is deleted like any other, but the dialog says the next sync brings it
   * back — refusing would be defensible, but silently restoring twelve skills the user deleted is
   * not something they should have to discover.
   */
  async removeChecked(): Promise<void> {
    const names = this.checkedNames();
    if (!names.length) {
      return;
    }
    const owned = this.skills().filter((s) => names.includes(s.name) && s.owner).length;
    const plugins = owned
      ? `\n\n${owned} of them ${owned === 1 ? 'was' : 'were'} installed by a plugin. Deleting them here is undone the next time that plugin syncs — remove the plugin instead to be rid of them for good.`
      : '';
    const ok = await this.confirm.ask(
      `Delete ${names.length} skill${names.length === 1 ? '' : 's'}?`,
      `${names.slice(0, 8).join(', ')}${names.length > 8 ? `, and ${names.length - 8} more` : ''}.\n\nTheir files are removed from the library. A template that names one will log a warning and run without it.${plugins}`,
      `Delete ${names.length}`,
      true,
    );
    if (!ok) {
      return;
    }
    this.deleting.set(true);
    this.service.removeMany(names).subscribe({
      next: (result) => {
        this.deleting.set(false);
        this.clearChecked();
        if (result.failed.length) {
          this.toasts.error(
            `Deleted ${result.deleted.length}; ${result.failed.length} failed — ${result.failed[0].name}: ${result.failed[0].error}`,
          );
        } else {
          this.toasts.ok(`Deleted ${result.deleted.length} skills`);
        }
        this.reload();
      },
      error: (e) => {
        this.deleting.set(false);
        this.toasts.error(apiError(e, 'Could not delete the skills'));
      },
    });
  }

  private checkNames(names: string[], on: boolean): void {
    this.checked.update((live) => {
      const next = new Set(live);
      for (const name of names) {
        if (on) {
          next.add(name);
        } else {
          next.delete(name);
        }
      }
      return next;
    });
  }

  private applyEnabled(name: string, enabled: boolean): void {
    // New object identity so the switch's [checked] input actually changes — that is what
    // makes the revert visibly snap the control back when core refuses the update.
    this.skills.update((list) => list.map((s) => (s.name === name ? { ...s, enabled } : s)));
  }
}

/** Ascending order for one column; the caller flips the sign for descending. */
function compare(a: SkillSummary, b: SkillSummary, key: SortKey): number {
  switch (key) {
    case 'owner':
      // Hand-written skills sort together at the end rather than scattered under "".
      return (a.owner ?? '￿').localeCompare(b.owner ?? '￿') || a.name.localeCompare(b.name);
    case 'fileCount':
      return (a.fileCount ?? 0) - (b.fileCount ?? 0) || a.name.localeCompare(b.name);
    case 'updatedAt':
      return (a.updatedAt ?? '').localeCompare(b.updatedAt ?? '') || a.name.localeCompare(b.name);
    case 'enabled':
      return Number(a.enabled) - Number(b.enabled) || a.name.localeCompare(b.name);
    default:
      return a.name.localeCompare(b.name);
  }
}
