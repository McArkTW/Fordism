import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatStepperModule } from '@angular/material/stepper';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Icon } from '../../core/icon';
import { formatDuration, statusView } from '../../core/status';
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

/** A content modal, as distinct from the one-question confirm. */
@Component({
  selector: 'app-theme-modal',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>A content modal</h2>
    <mat-dialog-content>
      <p class="text-sm">
        Bigger than a confirm: it carries real content — a form, a preview, a diff. Escape or the
        backdrop closes it; the actions row commits.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton mat-dialog-close>Close</button>
      <button matButton="filled" [mat-dialog-close]="true">Apply</button>
    </mat-dialog-actions>
  `,
})
export class ThemeModal {}

/**
 * The design audit page (not in the nav — open /theme directly): every token, shared
 * class, widget and icon rendered by the real components, so a change to the theme
 * is checked here in both schemes before it ships.
 */
@Component({
  selector: 'app-theme',
  imports: [
    DatePipe, Icon, Markdown, MatButtonModule, MatCheckboxModule, MatDialogModule, MatExpansionModule,
    MatFormFieldModule, MatInputModule, MatMenuModule, MatPaginatorModule,
    MatProgressBarModule, MatProgressSpinnerModule, MatSelectModule, MatSliderModule,
    MatSlideToggleModule, MatSortModule, MatStepperModule, MatTabsModule, MatTooltipModule,
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
  private dialog = inject(MatDialog);

  protected readonly icons = ICON_NAMES;
  protected readonly markdownSample = MARKDOWN_SAMPLE;
  protected readonly toggleOn = signal(true);
  protected readonly checked = signal(true);
  protected readonly sliderValue = signal(35);
  protected readonly progress = signal(65);

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

  protected duration(ms: number): string {
    return formatDuration(ms);
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

  protected openModal(): void {
    this.dialog.open(ThemeModal, { width: '30rem' });
  }

  /* --- data table demo: search + sort + pagination over a static sample --- */

  protected readonly tableQuery = signal('');
  protected readonly tableSort = signal<Sort>({ active: 'createdAt', direction: 'desc' });
  protected readonly pageIndex = signal(0);
  protected readonly pageSize = signal(5);

  private readonly sampleRuns = [
    { workflow: 'linear-example', state: 'DONE', createdAt: 1787780321000, durationMs: 64000 },
    { workflow: 'graph-example', state: 'RUNNING', createdAt: 1787780521000, durationMs: 12000 },
    { workflow: 'rework-example', state: 'FAILED', createdAt: 1787770321000, durationMs: 220000 },
    { workflow: 'map-reduce-example', state: 'DONE', createdAt: 1787760321000, durationMs: 480000 },
    { workflow: 'conditional-example', state: 'ASKED', createdAt: 1787750321000, durationMs: 30000 },
    { workflow: 'reconciler-example', state: 'DONE', createdAt: 1787740321000, durationMs: 900000 },
    { workflow: 'linear-example', state: 'ABANDONED', createdAt: 1787730321000, durationMs: 5000 },
    { workflow: 'graph-example', state: 'DONE', createdAt: 1787720321000, durationMs: 150000 },
    { workflow: 'rework-example', state: 'DONE', createdAt: 1787710321000, durationMs: 310000 },
    { workflow: 'linear-example', state: 'FAILED', createdAt: 1787700321000, durationMs: 45000 },
    { workflow: 'map-reduce-example', state: 'DONE', createdAt: 1787690321000, durationMs: 610000 },
    { workflow: 'conditional-example', state: 'DONE', createdAt: 1787680321000, durationMs: 95000 },
  ];

  /** Everything that survives the filter, sorted — paging slices from this. */
  protected filteredRuns() {
    const query = this.tableQuery().trim().toLowerCase();
    const { active, direction } = this.tableSort();
    const rows = this.sampleRuns.filter(
      (run) => !query || run.workflow.includes(query) || run.state.toLowerCase().includes(query),
    );
    if (direction) {
      const sign = direction === 'asc' ? 1 : -1;
      rows.sort((a, b) => {
        const left = a[active as keyof typeof a];
        const right = b[active as keyof typeof b];
        return (left < right ? -1 : left > right ? 1 : 0) * sign;
      });
    }
    return rows;
  }

  protected pagedRuns() {
    const start = this.pageIndex() * this.pageSize();
    return this.filteredRuns().slice(start, start + this.pageSize());
  }

  protected onTableSearch(value: string): void {
    this.tableQuery.set(value);
    this.pageIndex.set(0); // a new search starts from the first page
  }

  protected onSort(sort: Sort): void {
    this.tableSort.set(sort);
    this.pageIndex.set(0);
  }

  protected onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }
}
