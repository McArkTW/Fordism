import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** One way a user can sign in: a local password (`local`) or an OAuth account. */
export type Identity = {
  provider: string;
  subject: string;
};

/**
 * An account. `password` is never among the fields core returns — it only ever travels
 * outbound, on a create or an update that sets one.
 */
export type User = {
  id: string;
  email: string;
  displayName: string;
  identities: Identity[];
};

/** What a create sends. A blank `password` leaves the account without a local identity. */
export type UserSave = {
  email: string;
  displayName: string;
  password?: string;
};

/** Account CRUD. Everything here needs `user.read`/`user.write` server-side. */
@Injectable({ providedIn: 'root' })
export class UsersService {
  private http = inject(HttpClient);

  list(): Observable<User[]> {
    return this.http.get<User[]>('/api/users');
  }

  create(user: UserSave): Observable<User> {
    return this.http.post<User>('/api/users', user);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`/api/users/${encodeURIComponent(id)}`);
  }
}
