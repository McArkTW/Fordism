import { Injectable } from '@angular/core';

/**
 * Every concrete permission the backend checks, for previews when editing grant patterns.
 * A pattern like `run.*` is only as meaningful as the leaves it covers.
 */
export const PERMISSION_LEAVES = [
  'workflow.read',
  'workflow.write',
  'workflow.run',
  'run.read',
  'run.answer',
  'run.control',
  'run.workspace.download',
  'template.read',
  'template.write',
  'skill.read',
  'skill.write',
  'profile.read',
  'profile.write',
  'credential.read',
  'credential.write',
  'user.read',
  'user.write',
  'group.read',
  'group.write',
] as const;

/**
 * Mirrors the backend's grant matcher exactly — the UI only uses it to hide what a request
 * would be refused for, never to authorize anything. A grant matches a required permission
 * when it is identical, is the global `*`, or ends in `.*` and the requirement continues past
 * that prefix at a dot boundary (`admin.*` covers `admin.a` and `admin.a.b`, not `admin`
 * or `administrator.x`). Wildcards anywhere else (`*.read`) match nothing.
 */
@Injectable({ providedIn: 'root' })
export class PermissionService {
  matches(grant: string, required: string): boolean {
    if (grant === required || grant === '*') {
      return true;
    }
    if (grant.endsWith('.*')) {
      return required.startsWith(grant.slice(0, -2) + '.');
    }
    return false;
  }

  /** True when any of `grants` covers `required`. */
  anyMatches(grants: readonly string[], required: string): boolean {
    return grants.some((g) => this.matches(g, required));
  }
}
