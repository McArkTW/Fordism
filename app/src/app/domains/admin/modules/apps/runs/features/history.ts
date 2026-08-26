import {
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import { format, formatDistanceToNow } from 'date-fns';
import { errorMessage } from '@/app/core/api-error';
import { formatDuration, statusView } from '../data/run-status';
import { RunFilter, RunSummary, RunsService } from '../data/runs.service';

/** Relative windows, in hours. Anything finer than an hour is not how anyone looks for a run. */
const WINDOWS: { label: string; hours: number | null }[] = [
  { label: 'Any time', hours: null },
  { label: '1h', hours: 1 },
  { label: '24h', hours: 24 },
  { label: '7d', hours: 24 * 7 },
  { label: '30d', hours: 24 * 30 },
];

const STATES = ['ACTIVE', 'ASKED', 'DONE', 'FAILED', 'ABANDONED'];
const PAGE_SIZE = 25;

/**
 * Every run there has ever been — searchable, filterable, paged.
 *
 * It does NOT poll. A searched list that rearranges itself while you read it is unusable: the row
 * you were reaching for moves as runs finish. This is a query result, and it sits still until you
 * ask again. Live is the screen that moves.
 *
 * The filter lives in the URL so it survives a refresh, the back button, and opening a run and
 * coming back — which is exactly the moment you need it, because triage is filter, open, dismiss,
 * open the next one.
 */
@Component({
  selector: 'history',
  imports: [MatButton, MatIcon, RouterLink],
  host: { class: 'block' },
  templateUrl: './history.html',
})
export default class History {
  /**
   * Query parameters, bound by withComponentInputBinding.
   *
   * Typed `string | undefined` on purpose: the router binds an ABSENT parameter as undefined and
   * that overrides the declared default, so `state()` is undefined on a bare /runs. Reading it as
   * a string threw and took the whole page down with it — hence the normalised accessors below,
   * which are what the rest of the class uses.
   */
  workflow = input<string | undefined>();
  state = input<string | undefined>();
  since = input<string | undefined>();
  q = input<string | undefined>();

  /** Read by the template too, so a missing parameter never reaches string methods. */
  protected workflowParam = computed(() => this.workflow() ?? '');
  private stateParam = computed(() => this.state() ?? '');
  private sinceParam = computed(() => this.since() ?? '');
  private queryParam = computed(() => this.q() ?? '');

  private service = inject(RunsService);
  private router = inject(Router);

  runs = signal<RunSummary[]>([]);
  nextCursor = signal<string | null>(null);
  loading = signal<boolean>(false);
  error = signal<string>('');
  workflows = signal<string[]>([]);
  search = signal<string>('');

  readonly windows = WINDOWS;
  readonly states = STATES;

  /** The filter as the URL currently describes it — the single source of truth. */
  private filter = computed<RunFilter>(() => ({
    workflow: this.workflowParam() || undefined,
    state: this.stateParam() ? this.stateParam().split(',') : undefined,
    since: this.sinceParam() ? Number(this.sinceParam()) : undefined,
    q: this.queryParam() || undefined,
    limit: PAGE_SIZE,
  }));

  active = computed(
    () =>
      !!(
        this.workflowParam() ||
        this.stateParam() ||
        this.sinceParam() ||
        this.queryParam()
      )
  );

  constructor() {
    this.service.workflowsSeen().subscribe({
      next: (names) => this.workflows.set(names),
      error: () => {
        /* the picker just stays empty; the rest of the page still works */
      },
    });
    // Re-query whenever the URL changes, and only then.
    effect(() => {
      const filter = this.filter();
      this.search.set(this.queryParam());
      this.load(filter);
    });
  }

  private load(filter: RunFilter): void {
    this.loading.set(true);
    this.error.set('');
    this.service.page(filter).subscribe({
      next: (page) => {
        this.runs.set(page.runs);
        this.nextCursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  more(): void {
    const cursor = this.nextCursor();
    if (!cursor) {
      return;
    }
    this.loading.set(true);
    this.service.page({ ...this.filter(), before: cursor }).subscribe({
      next: (page) => {
        this.runs.update((current) => [...current, ...page.runs]);
        this.nextCursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  /** Every control writes to the URL; nothing filters in place. */
  private apply(change: Record<string, string | null>): void {
    this.router.navigate([], {
      queryParams: change,
      queryParamsHandling: 'merge',
    });
  }

  setWorkflow(name: string): void {
    this.apply({ workflow: name || null });
  }

  toggleState(state: string): void {
    const current = this.stateParam() ? this.stateParam().split(',') : [];
    const next = current.includes(state)
      ? current.filter((s) => s !== state)
      : [...current, state];
    this.apply({ state: next.length ? next.join(',') : null });
  }

  hasState(state: string): boolean {
    return this.stateParam().split(',').includes(state);
  }

  setWindow(hours: number | null): void {
    this.apply({ since: hours ? String(Date.now() - hours * 3600_000) : null });
  }

  windowActive(hours: number | null): boolean {
    return hours === null ? !this.sinceParam() : !!this.sinceParam();
  }

  submitSearch(): void {
    this.apply({ q: this.search().trim() || null });
  }

  clear(): void {
    this.router.navigate([], { queryParams: {} });
  }

  label(state: string): string {
    return statusView(state).label;
  }

  badge(state: string): string {
    return statusView(state).badge;
  }

  icon(state: string): string {
    return statusView(state).icon;
  }

  dur(ms: number | null): string {
    return formatDuration(ms);
  }

  when(ms: number): string {
    return ms ? formatDistanceToNow(ms, { addSuffix: true }) : '';
  }

  absTime(ms: number): string {
    return ms ? format(ms, 'MMM d, yyyy · h:mm:ss a') : '';
  }
}
