import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * One API token as the list shows it. There is no `value` field, and there is no endpoint that
 * would fill one in — the server keeps a hash, so the value below exists for exactly one response.
 */
export type ApiTokenView = {
  id: string;
  name: string;
  grants: string[];
  createdAt: number;
  /** 0 when the token never expires. */
  expiresAt: number;
  /** 0 when it has never been used. */
  lastUsedAt: number;
};

/** What POST answers: the row, plus the only copy of the token there will ever be. */
export type MintedApiToken = { token: ApiTokenView; value: string };

/** What the create form collects. Empty grants means "no narrower than my account". */
export type NewApiToken = { name: string; grants: string[]; expiresInDays: number };

/**
 * /api/api-tokens — the tokens that let a script call this API without a browser.
 *
 * Always your own: there is no endpoint for anyone else's, and the server refuses these routes to
 * a caller that is itself using a token.
 */
@Injectable({ providedIn: 'root' })
export class ApiTokensService {
  private http = inject(HttpClient);

  list(): Observable<ApiTokenView[]> {
    return this.http.get<ApiTokenView[]>('/api/api-tokens');
  }

  create(draft: NewApiToken): Observable<MintedApiToken> {
    return this.http.post<MintedApiToken>('/api/api-tokens', draft);
  }

  revoke(id: string): Observable<void> {
    return this.http.delete<void>(`/api/api-tokens/${encodeURIComponent(id)}`);
  }
}
