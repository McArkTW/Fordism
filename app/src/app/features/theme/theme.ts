import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Icon } from '../../core/icon';
import { statusView } from '../../core/status';
import { Theme } from '../../core/theme';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';
import { Markdown } from '../../shared/markdown';

/** Every icon the sprite carries — keep in step with scripts/build-icons.mjs. */
const ICON_NAMES = [
  'activity', 'alert-triangle', 'arrow-left', 'book-open', 'check', 'chevron-down',
  'chevron-left', 'chevron-right', 'circle-check', 'circle-dot', 'circle-slash', 'circle-x',
  'clock', 'copy', 'cpu', 'download', 'external-link', 'file', 'folder', 'git-branch',
  'history', 'info', 'key', 'layers', 'list', 'loader', 'menu',
  'message-circle-question-mark', 'moon', 'pencil', 'play', 'plus', 'radio', 'refresh-cw',
  'save', 'search', 'server', 'sparkles', 'square-pen', 'sun', 'trash-2', 'upload',
  'workflow', 'x',
];

const MARKDOWN_SAMPLE = `## Markdown rendering

Result files and SKILL.md render through this. **Bold**, *italic*, \`inline code\`, [a link](#), and:

- a list item
- another item

\`\`\`
a code block
\`\`\`
`;

/**
 * The design audit page (not in the nav — open /theme directly): every token, shared
 * class, widget and icon rendered by the real components, so a change to the theme
 * is checked here in both schemes before it ships.
 */
@Component({
  selector: 'app-theme',
  imports: [
    Icon, Markdown, MatButtonModule, MatCheckboxModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatSlideToggleModule,
  ],
  templateUrl: './theme.html',
  styles: `
    .btn-danger-mat {
      --mat-button-filled-container-color: var(--color-red-600, #dc2626);
      --mat-button-filled-label-text-color: #fff;
    }
  `,
})
export class ThemePage {
  protected readonly theme = inject(Theme);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  protected readonly icons = ICON_NAMES;
  protected readonly markdownSample = MARKDOWN_SAMPLE;
  protected readonly toggleOn = signal(true);
  protected readonly checked = signal(true);

  protected readonly tokens = [
    { name: 'bg', varName: '--bg', note: 'page background' },
    { name: 'panel', varName: '--panel', note: 'cards, sidebar' },
    { name: 'edge', varName: '--border', note: 'borders' },
    { name: 'ink', varName: '--ink', note: 'text' },
    { name: 'muted', varName: '--muted', note: 'secondary text' },
    { name: 'accent', varName: '--accent', note: 'brand indigo' },
  ];

  protected readonly taskStates = ['PENDING', 'RUNNING', 'COLLECTED', 'REAPED', 'ASKED', 'FAILED'];
  protected readonly runStates = ['ACTIVE', 'DONE', 'FAILED', 'ASKED', 'ABANDONED', 'WAITING_ON_CHILD'];

  protected view(state: string) {
    return statusView(state);
  }

  protected stoppedView() {
    return statusView('REAPED', 'ABANDONED');
  }

  protected toastOk(): void {
    this.toasts.ok('Saved — everything went fine.');
  }

  protected toastError(): void {
    this.toasts.error('Could not save: the backend refused the request.');
  }

  protected async openConfirm(): Promise<void> {
    const yes = await this.confirm.ask(
      'Delete this thing?',
      'This is the themed confirm dialog. Nothing actually happens either way.',
      'Delete',
      true,
    );
    this.toasts.ok(yes ? 'Confirmed.' : 'Cancelled.');
  }
}
