import { Injectable } from '@angular/core';
import leaves from './permission-leaves.json';

/**
 * Every concrete permission the backend checks, for previews when editing grant patterns.
 * A pattern like `run.*` is only as meaningful as the leaves it covers.
 *
 * Read from a file rather than typed out here, for the reason the matcher vectors are: this list
 * was a hand-maintained copy of core's `Permission` enum, and a copy drifts. Core pins the same
 * file to its enum, and CI compares the two — so a preview can no longer tell an administrator
 * that `run.*` covers a permission that was renamed three releases ago.
 */
export const PERMISSION_LEAVES: readonly string[] = leaves;

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
