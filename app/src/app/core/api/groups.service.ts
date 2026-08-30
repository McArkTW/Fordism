import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * A group is the only place permissions are handed out: `grants` are patterns (`run.*`, `*`,
 * or a bare leaf), `members` are user ids. A user's permissions are the union of the grants of
 * every group they belong to.
 */
export type Group = {
  id: string;
  name: string;
  members: string[];
  grants: string[];
};

/** What a create or an update sends — the same shape minus the server-assigned id. */
export type GroupSave = {
  name: string;
  members: string[];
  grants: string[];
};

/**
 * Group CRUD. Core refuses to delete (or strip) the last group granting `*` — that would lock
 * everyone out of administration — and answers with the reason, which this page shows verbatim.
 */
@Injectable({ providedIn: 'root' })
export class GroupsService {
  private http = inject(HttpClient);

  list(): Observable<Group[]> {
    return this.http.get<Group[]>('/api/groups');
  }

  create(group: GroupSave): Observable<Group> {
    return this.http.post<Group>('/api/groups', group);
  }

  update(id: string, group: GroupSave): Observable<Group> {
    return this.http.put<Group>(`/api/groups/${encodeURIComponent(id)}`, group);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`/api/groups/${encodeURIComponent(id)}`);
  }
}
