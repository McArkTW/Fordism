import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { PermissionService } from './permission';

/** What ways in the instance offers — asked unauthenticated, so the login page can draw itself. */
export type ProvidersInfo = {
  providers: { id: string }[];
  bootstrapRequired: boolean;
};

/** The session's owner as /api/auth/me reports them. `permissions` are grant patterns. */
export type AuthUser = {
  id: string;
  email: string;
  displayName: string;
  groups: string[];
  permissions: string[];
};

/**
 * Who is logged in. The session itself is an HttpOnly cookie the browser carries on every
 * same-origin request — the SPA never sees a token; this service only mirrors what the
 * backend says via /api/auth/me.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);
  private matcher = inject(PermissionService);

  readonly user = signal<AuthUser | null>(null);
  private pending: Promise<void>;

  constructor() {
    this.pending = this.refresh();
  }

  /** Resolves once the session state is known — what guards await before deciding. */
  whenLoaded(): Promise<void> {
    return this.pending;
  }

  providers(): Observable<ProvidersInfo> {
    return this.http.get<ProvidersInfo>('/api/auth/providers');
  }

  /** Re-asks /api/auth/me; any failure (401 included) means "not logged in". */
  refresh(): Promise<void> {
    this.pending = firstValueFrom(this.http.get<AuthUser>('/api/auth/me')).then(
      (u) => this.user.set(u),
      () => this.user.set(null),
    );
    return this.pending;
  }

  /** Rejects with the HttpErrorResponse (401 = bad credentials) — the caller renders it. */
  async login(email: string, password: string): Promise<void> {
    await firstValueFrom(this.http.post('/api/auth/login', { email, password }));
    await this.refresh();
  }

  /** First-run: mint the initial admin (and its session) with the deploy-time secret. */
  async bootstrap(secret: string, email: string, password: string, displayName: string): Promise<void> {
    await firstValueFrom(this.http.post('/api/auth/bootstrap', { secret, email, password, displayName }));
    await this.refresh();
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post('/api/auth/logout', {}));
    } finally {
      this.user.set(null);
      this.router.navigateByUrl('/login');
    }
  }

  /** True when the current user's grants cover `permission`. UI hint only — the server decides. */
  can(permission: string): boolean {
    const u = this.user();
    return u !== null && this.matcher.anyMatches(u.permissions, permission);
  }
}
