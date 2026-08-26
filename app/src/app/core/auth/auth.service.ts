import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

const HEIMDALL = 'https://heimdall.local';
const TOKEN_KEY = 'fordism_id_token';
const VERIFIER_KEY = 'fordism_pkce_verifier';

/** The profile `/api/auth/me` returns — the backend owns it, the browser only displays it. */
export type UserProfile = {
  email: string;
  displayName: string;
  firstSeen: string;
  lastSeen: string;
};

/**
 * OAuth (PKCE) client for TDS Heimdall. The browser gets an identity token from
 * Heimdall and sends it as a Bearer; Fordism's backend verifies it. No secret in
 * the browser — the PKCE code_verifier proves this is the same browser.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);

  /**
   * Heimdall registers one service per environment, each pinned to one exact callback URL that
   * it string-matches, so the name has to follow the host we are served from. A dev server
   * matches no registration at all and gets a 400 — log in on the real host.
   */
  private service(): string {
    return location.hostname === 'fordism.local' ? 'fordism' : 'fordism-uat';
  }

  /** Who is signed in — null until loaded, and null again if the token stopped verifying. */
  readonly user = signal<UserProfile | null>(null);

  token(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  /**
   * Ask the backend who this token belongs to. A 401 means the token no longer verifies, so
   * drop it — the next page load bounces to Heimdall. Deliberately no immediate redirect: a
   * token Heimdall keeps issuing but the backend keeps rejecting would loop forever.
   */
  loadUser(): void {
    if (!this.token()) {
      return;
    }
    this.http.get<UserProfile>('/api/auth/me').subscribe({
      next: (user) => this.user.set(user),
      error: (err: { status?: number }) => {
        this.user.set(null);
        if (err?.status === 401) {
          localStorage.removeItem(TOKEN_KEY);
        }
      },
    });
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    this.user.set(null);
    this.login('/');
  }

  private b64url(buf: ArrayBuffer): string {
    return btoa(String.fromCharCode(...new Uint8Array(buf)))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  }

  /** Kick off login: make a PKCE pair, then bounce to Heimdall. */
  async login(returnTo: string): Promise<void> {
    const verifier = this.b64url(crypto.getRandomValues(new Uint8Array(32)).buffer);
    sessionStorage.setItem(VERIFIER_KEY, verifier);
    const challenge = this.b64url(
      await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
    );
    const redirect = `${location.origin}/auth/callback`;
    const url =
      `${HEIMDALL}/authorize?service=${this.service()}` +
      `&redirect_uri=${encodeURIComponent(redirect)}` +
      `&state=${encodeURIComponent(returnTo)}` +
      `&code_challenge=${challenge}`;
    location.href = url;
  }

  /** Handle the Heimdall redirect back: swap code + verifier for a token. */
  async handleCallback(code: string, state: string): Promise<void> {
    const verifier = sessionStorage.getItem(VERIFIER_KEY) || '';
    try {
      const res = await fetch(`${HEIMDALL}/api/pkce/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, code_verifier: verifier }),
      });
      const data = await res.json();
      if (data.token) {
        localStorage.setItem(TOKEN_KEY, data.token);
      }
    } catch {
      // fall through — user lands unauthenticated and the guard retries
    }
    sessionStorage.removeItem(VERIFIER_KEY);
    location.replace(state && state.startsWith('/') ? state : '/');
  }
}
