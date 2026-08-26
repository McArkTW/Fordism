import { Injectable, signal } from '@angular/core';

export type Scheme = 'light' | 'dark' | 'system';
const SCHEME_KEY = 'fordism_scheme';

/**
 * Light/dark scheme. The choice persists per browser; 'system' follows the OS. The stylesheet
 * keys everything off a `dark` class on <html>, so applying is one classList toggle.
 */
@Injectable({ providedIn: 'root' })
export class Theme {
  readonly scheme = signal<Scheme>(stored());

  constructor() {
    this.apply();
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => this.apply());
  }

  set(scheme: Scheme): void {
    this.scheme.set(scheme);
    try {
      localStorage.setItem(SCHEME_KEY, scheme);
    } catch {
      // storage can be unavailable (private mode); the choice just won't persist
    }
    this.apply();
  }

  private apply(): void {
    const wantsDark =
      this.scheme() === 'dark' ||
      (this.scheme() === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.classList.toggle('dark', wantsDark);
  }
}

function stored(): Scheme {
  try {
    const value = localStorage.getItem(SCHEME_KEY);
    return value === 'light' || value === 'dark' ? value : 'system';
  } catch {
    return 'system';
  }
}
