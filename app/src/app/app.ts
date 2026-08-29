import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmToaster } from '@spartan-ng/helm/sonner';
import { AuthService } from './auth/auth.service';
import { Attention } from './core/attention';
import { Icon } from './core/icon';
import { lucideSprite } from './core/icons';
import { Theme } from './core/theme';

/** `permission` is the read grant the page needs — without it the entry is not drawn. */
type NavItem = { path: string; label: string; icon: string; permission: string };

const OPERATE: NavItem[] = [
  { path: '/live', label: 'Live', icon: 'radio', permission: 'run.read' },
  { path: '/runs', label: 'History', icon: 'history', permission: 'run.read' },
];
const CONFIGURE: NavItem[] = [
  { path: '/workflows', label: 'Workflows', icon: 'workflow', permission: 'workflow.read' },
  { path: '/templates', label: 'Templates', icon: 'layers', permission: 'template.read' },
  { path: '/skills', label: 'Skills', icon: 'sparkles', permission: 'skill.read' },
  { path: '/agent-profiles', label: 'Agent Profiles', icon: 'cpu', permission: 'profile.read' },
  { path: '/credentials', label: 'Credentials', icon: 'key', permission: 'credential.read' },
];
const ADMINISTER: NavItem[] = [
  { path: '/users', label: 'Users', icon: 'users', permission: 'user.read' },
  { path: '/groups', label: 'Groups', icon: 'shield', permission: 'group.read' },
];

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, HlmButton, HlmToaster],
  templateUrl: './app.html',
})
export class App {
  protected readonly theme = inject(Theme);
  protected readonly attention = inject(Attention);
  protected readonly auth = inject(AuthService);
  protected readonly sprite: SafeHtml;

  /** No session, no chrome: /login and /bootstrap draw themselves on a bare page. */
  protected readonly signedIn = computed(() => this.auth.user() !== null);

  // A nav entry the user cannot open is worse than a missing one — it only leads to a refusal.
  // The server still decides; this is the same answer, given before the click.
  protected readonly operate = computed(() => this.visible(OPERATE));
  protected readonly configure = computed(() => this.visible(CONFIGURE));
  protected readonly administer = computed(() => this.visible(ADMINISTER));

  constructor() {
    // The sprite is our own generated markup (scripts/build-icons.mjs), not user input.
    this.sprite = inject(DomSanitizer).bypassSecurityTrustHtml(lucideSprite);
  }

  protected toggleScheme(): void {
    this.theme.set(document.documentElement.classList.contains('dark') ? 'light' : 'dark');
  }

  protected logout(): void {
    this.auth.logout();
  }

  private visible(items: NavItem[]): NavItem[] {
    return items.filter((item) => this.auth.can(item.permission));
  }
}
