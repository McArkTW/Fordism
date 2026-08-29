import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Attention } from './core/attention';
import { Icon } from './core/icon';
import { lucideSprite } from './core/icons';
import { Theme } from './core/theme';

type NavItem = { path: string; label: string; icon: string };

const OPERATE: NavItem[] = [
  { path: '/live', label: 'Live', icon: 'radio' },
  { path: '/runs', label: 'History', icon: 'history' },
];
const CONFIGURE: NavItem[] = [
  { path: '/workflows', label: 'Workflows', icon: 'workflow' },
  { path: '/templates', label: 'Templates', icon: 'layers' },
  { path: '/skills', label: 'Skills', icon: 'sparkles' },
  { path: '/agent-profiles', label: 'Agent Profiles', icon: 'cpu' },
  { path: '/credentials', label: 'Credentials', icon: 'key' },
];

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, MatButtonModule],
  templateUrl: './app.html',
})
export class App {
  protected readonly theme = inject(Theme);
  protected readonly attention = inject(Attention);
  protected readonly operate = OPERATE;
  protected readonly configure = CONFIGURE;
  protected readonly sprite: SafeHtml;

  constructor() {
    // The sprite is our own generated markup (scripts/build-icons.mjs), not user input.
    this.sprite = inject(DomSanitizer).bypassSecurityTrustHtml(lucideSprite);
  }

  protected toggleScheme(): void {
    this.theme.set(document.documentElement.classList.contains('dark') ? 'light' : 'dark');
  }
}
