import { Component, computed, inject, signal } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCheckbox } from '@spartan-ng/helm/checkbox';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { PERMISSION_LEAVES, PermissionService } from '../../auth/permission';
import { apiError } from '../../core/api-error';
import { Group, GroupsService } from '../../core/api/groups.service';
import { User, UsersService } from '../../core/api/users.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';

/** A group being edited or created. `id` is null while it is still only a draft. */
type Draft = { id: string | null; name: string; members: string[]; grants: string[] };

/** One grant pattern beside the permissions it actually reaches. */
type GrantPreview = { pattern: string; leaves: string[] };

/**
 * Groups — where permissions actually come from. A grant is a pattern (`*`, `run.*`, or a bare
 * leaf like `run.answer`), and a pattern is easy to write wrong: `*.read` looks like it grants
 * every read and grants nothing at all. So every pattern is shown beside the permissions it
 * reaches, checked with the same matcher the backend uses.
 */
@Component({
  selector: 'app-groups',
  imports: [Icon, HlmButton, HlmCheckbox, HlmInput, HlmLabel],
  templateUrl: './groups.html',
})
export class Groups {
  private service = inject(GroupsService);
  private usersService = inject(UsersService);
  private matcher = inject(PermissionService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  /** null until the first answer — an empty list and "not loaded yet" must not look alike. */
  readonly groups = signal<Group[] | null>(null);
  readonly users = signal<User[] | null>(null);
  readonly loadError = signal<string | null>(null);
  /** A refusal from a save or a delete — kept on screen, unlike the toast. */
  readonly actionError = signal<string | null>(null);

  readonly draft = signal<Draft | null>(null);
  readonly newGrant = signal('');
  readonly memberFilter = signal('');
  readonly busy = signal(false);

  readonly leaves = PERMISSION_LEAVES;

  /** Each pattern with what it covers — an empty `leaves` is the warning worth having. */
  readonly preview = computed<GrantPreview[]>(() =>
    (this.draft()?.grants ?? []).map((pattern) => ({
      pattern,
      leaves: PERMISSION_LEAVES.filter((leaf) => this.matcher.matches(pattern, leaf)),
    })),
  );

  /** The union — what a member of this group would end up able to do. */
  readonly covered = computed(() => {
    const grants = this.draft()?.grants ?? [];
    return PERMISSION_LEAVES.filter((leaf) => this.matcher.anyMatches(grants, leaf));
  });

  readonly candidates = computed(() => {
    const q = this.memberFilter().trim().toLowerCase();
    return (this.users() ?? []).filter(
      (u) => !q || u.email.toLowerCase().includes(q) || u.displayName.toLowerCase().includes(q),
    );
  });

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loadError.set(null);
    this.service.list().subscribe({
      // Keep whatever is on screen if a reload fails — blanking it would read as "no groups".
      next: (list) => this.groups.set(list),
      error: (e) => this.loadError.set(apiError(e, 'Could not load groups')),
    });
    this.usersService.list().subscribe({
      next: (list) => this.users.set(list),
      error: () => {
        // Members become unpickable, which the panel says; the grants half still works.
        this.users.set([]);
      },
    });
  }

  memberName(id: string): string {
    const u = (this.users() ?? []).find((x) => x.id === id);
    return u ? u.displayName || u.email : id;
  }

  startCreate(): void {
    this.actionError.set(null);
    this.newGrant.set('');
    this.memberFilter.set('');
    this.draft.set({ id: null, name: '', members: [], grants: [] });
  }

  startEdit(group: Group): void {
    this.actionError.set(null);
    this.newGrant.set('');
    this.memberFilter.set('');
    this.draft.set({
      id: group.id,
      name: group.name,
      members: [...group.members],
      grants: [...group.grants],
    });
  }

  cancel(): void {
    this.draft.set(null);
    this.actionError.set(null);
  }

  patch(change: Partial<Draft>): void {
    this.draft.update((d) => (d ? { ...d, ...change } : d));
  }

  toggleMember(id: string): void {
    this.draft.update((d) =>
      d
        ? {
            ...d,
            members: d.members.includes(id)
              ? d.members.filter((m) => m !== id)
              : [...d.members, id],
          }
        : d,
    );
  }

  isMember(id: string): boolean {
    return this.draft()?.members.includes(id) ?? false;
  }

  addGrant(): void {
    const pattern = this.newGrant().trim();
    if (!pattern) {
      return;
    }
    this.draft.update((d) =>
      d && !d.grants.includes(pattern) ? { ...d, grants: [...d.grants, pattern] } : d,
    );
    this.newGrant.set('');
  }

  removeGrant(pattern: string): void {
    this.draft.update((d) => (d ? { ...d, grants: d.grants.filter((g) => g !== pattern) } : d));
  }

  save(): void {
    const d = this.draft();
    if (!d) {
      return;
    }
    if (!d.name.trim()) {
      this.toasts.error('A group needs a name.');
      return;
    }
    const body = { name: d.name.trim(), members: d.members, grants: d.grants };
    const request = d.id ? this.service.update(d.id, body) : this.service.create(body);
    this.busy.set(true);
    this.actionError.set(null);
    request.subscribe({
      next: () => {
        this.busy.set(false);
        this.draft.set(null);
        this.toasts.ok(`Saved ${body.name}`);
        this.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        // Core refuses an edit that would strip the last `*` — its reason is the whole message.
        const message = apiError(e, 'Could not save the group');
        this.actionError.set(message);
        this.toasts.error(message);
      },
    });
  }

  async remove(group: Group): Promise<void> {
    const ok = await this.confirm.ask(
      'Delete group',
      `Delete ${group.name}? Its ${group.members.length} member(s) lose everything it granted.`,
      'Delete',
      true,
    );
    if (!ok) {
      return;
    }
    this.actionError.set(null);
    this.service.remove(group.id).subscribe({
      next: () => {
        this.toasts.ok(`Deleted ${group.name}`);
        if (this.draft()?.id === group.id) {
          this.draft.set(null);
        }
        this.refresh();
      },
      error: (e) => {
        // The one refusal worth reading in full: deleting the last group granting `*` would
        // leave nobody able to administer the instance.
        const message = apiError(e, 'Could not delete the group');
        this.actionError.set(message);
        this.toasts.error(message);
      },
    });
  }
}
