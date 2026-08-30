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
  /** Whether this account has a second factor enrolled. The secret itself never reaches the app. */
  mfaEnabled: boolean;
};

/** Raised by login when the password was right but a second factor is still needed. */
export class MfaRequired extends Error {
  constructor() {
    super('mfaRequired');
  }
}

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

  /**
   * Sign in. A second factor is supplied in the same call once the login page has collected it:
   * `code` for an authenticator, or `recoveryCode` for a backup code.
   *
   * Rejects with {@link MfaRequired} when the password was right but a factor is still needed —
   * the page then shows the code field — and with the HttpErrorResponse otherwise (401 = wrong
   * credentials or wrong code), which the caller renders.
   */
  async login(email: string, password: string, second?: { code?: string; recoveryCode?: string }): Promise<void> {
    try {
      await firstValueFrom(this.http.post('/api/auth/login',
        { email, password, code: second?.code, recoveryCode: second?.recoveryCode }));
    } catch (e: unknown) {
      if (isMfaRequired(e)) {
        throw new MfaRequired();
      }
      throw e;
    }
    await this.refresh();
  }

  /** First-run: mint the initial admin (and its session) with the deploy-time secret. */
  async bootstrap(secret: string, email: string, password: string, displayName: string): Promise<void> {
    await firstValueFrom(this.http.post('/api/auth/bootstrap', { secret, email, password, displayName }));
    await this.refresh();
  }

  /** Begin TOTP enrolment: the secret and the otpauth URI to turn into a QR. */
  beginMfa(): Observable<{ secret: string; otpauthUri: string }> {
    return this.http.post<{ secret: string; otpauthUri: string }>('/api/auth/mfa/begin', {});
  }

  /** Confirm enrolment with a code from the app; returns the one-time recovery codes. */
  async confirmMfa(code: string): Promise<string[]> {
    const result = await firstValueFrom(
      this.http.post<{ recoveryCodes: string[] }>('/api/auth/mfa/confirm', { code }),
    );
    await this.refresh();
    return result.recoveryCodes;
  }

  /** Turn the second factor off, confirming with the password or a current code. */
  async disableMfa(proof: { password?: string; code?: string }): Promise<void> {
    await firstValueFrom(this.http.post('/api/auth/mfa/disable', proof));
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

/** The 401 that carries `{ mfaRequired: true }` rather than an error message. */
function isMfaRequired(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'status' in error &&
    (error as { status: number }).status === 401 &&
    'error' in error &&
    (error as { error?: { mfaRequired?: boolean } }).error?.mfaRequired === true
  );
}
