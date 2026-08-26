import { Component, computed, inject } from '@angular/core';
import { MatPseudoCheckbox } from '@angular/material/core';
import { MatIcon } from '@angular/material/icon';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { AuthService } from '@/app/core/auth/auth.service';
import { Scheme, Theming } from '@/app/core/theming';

/**
 * The sidebar's bottom control — who is signed in, plus the colour scheme and sign-out.
 *
 * The identity comes from `/api/auth/me`, which verifies the Heimdall token server-side; the
 * browser never decodes the token itself, so what is shown is what the backend accepted.
 */
@Component({
  selector: 'user',
  imports: [MatIcon, MatMenu, MatMenuItem, MatPseudoCheckbox, MatMenuTrigger],
  host: {
    class: 'block',
  },
  template: `
    <button
      class="flex w-full cursor-pointer items-center gap-x-3 rounded-xl p-2 text-left hover:bg-neutral-700/10 dark:hover:bg-neutral-300/10"
      [matMenuTriggerFor]="accountMenu"
    >
      <!-- primary-600 on primary-50 is what material.css maps primary/on-primary to; there is
           no unnumbered --color-primary token, so a bare bg-primary paints nothing. -->
      <div
        class="bg-primary-600 text-primary-50 flex size-8 shrink-0 items-center justify-center rounded-full text-xs font-semibold uppercase select-none"
      >
        {{ initials() }}
      </div>
      <div class="flex min-w-0 flex-auto flex-col">
        <div class="truncate text-sm font-medium select-none">
          {{ user()?.displayName || 'Signing in…' }}
        </div>
        @if (user(); as account) {
          <div class="text-secondary truncate text-xs select-none">
            {{ account.email }}
          </div>
        }
      </div>
      <mat-icon
        class="size-4"
        svgIcon="ellipsis-vertical"
      />
    </button>

    <mat-menu
      class="min-w-48"
      xPosition="before"
      yPosition="above"
      #accountMenu="matMenu"
    >
      <button
        mat-menu-item
        [matMenuTriggerFor]="appearanceMenu"
      >
        <mat-icon
          class="mr-2 size-5"
          svgIcon="sun-moon"
        />
        <span>Appearance</span>
      </button>
      <button
        mat-menu-item
        (click)="signOut()"
      >
        <mat-icon
          class="mr-2 size-5"
          svgIcon="log-out"
        />
        <span>Sign out</span>
      </button>
    </mat-menu>

    <mat-menu
      class="min-w-48"
      #appearanceMenu="matMenu"
    >
      @for (item of schemes; track item.value) {
        <button
          mat-menu-item
          (click)="updateScheme(item.value)"
        >
          <mat-pseudo-checkbox
            appearance="minimal"
            class="mr-2"
            [state]="scheme() === item.value ? 'checked' : 'unchecked'"
          />
          <span>{{ item.label }}</span>
        </button>
      }
    </mat-menu>
  `,
})
export class User {
  // Dependencies
  private theming = inject(Theming);
  private auth = inject(AuthService);

  // State
  protected user = this.auth.user;
  protected scheme = computed(() => this.theming.scheme());
  protected schemes: { label: string; value: Scheme }[] = [
    { label: 'Light', value: 'light' },
    { label: 'Dark', value: 'dark' },
    { label: 'System', value: 'system' },
  ];

  /** Up to two initials from the display name — the fallback dot avoids an empty circle. */
  protected initials = computed(() => {
    const name = this.user()?.displayName || '';
    const parts = name.split(/[\s._-]+/).filter(Boolean);
    return parts.slice(0, 2).map((p) => p[0]).join('') || '·';
  });

  constructor() {
    this.auth.loadUser();
  }

  updateScheme(scheme: Scheme) {
    this.theming.scheme.set(scheme);
  }

  signOut() {
    this.auth.logout();
  }
}
