import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * A stored credential as the browser sees it. There is no `value` field and no endpoint that
 * returns one — the only reader of a value is the launcher, in core. `usedBy` is computed from the
 * templates that declare the key, so it cannot drift from what agents actually receive.
 */
export type CredentialView = {
  key: string;
  note: string;
  hasValue: boolean;
  usedBy: string[];
  updatedAt: number;
};

/** What a save sends. A blank `value` keeps the stored one. */
export type CredentialSave = {
  value: string;
  note: string;
};

/** Credential CRUD, keyed by environment-variable name. Values are write-only. */
@Injectable({ providedIn: 'root' })
export class CredentialsService {
  private http = inject(HttpClient);

  list(): Observable<CredentialView[]> {
    return this.http.get<CredentialView[]>('/api/credentials');
  }

  save(key: string, credential: CredentialSave): Observable<{ key: string }> {
    return this.http.put<{ key: string }>(
      `/api/credentials/${encodeURIComponent(key)}`,
      credential
    );
  }

  remove(key: string): Observable<void> {
    return this.http.delete<void>(
      `/api/credentials/${encodeURIComponent(key)}`
    );
  }
}
