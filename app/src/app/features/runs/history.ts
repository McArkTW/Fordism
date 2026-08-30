import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmTableImports } from '@spartan-ng/helm/table';
import { apiError } from '../../core/api-error';
import { RunFilter, RunSummary, RunsService } from '../../core/api/runs.service';
import { Icon } from '../../core/icon';
import { formatDuration, statusView } from '../../core/status';

const STATES = ['DONE', 'FAILED', 'ASKED', 'ACTIVE', 'ABANDONED'];
const PAGE_SIZE = 25;
const SEARCH_DEBOUNCE_MS = 300;

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
 *
 * Params are read off queryParamMap, not bound inputs: an absent parameter is simply not there
 * (never a string to split), and a repeated one (?state=A&state=B) comes back whole via getAll —
 * both have broken this page before.
 */
@Component({
  selector: 'app-history',
  imports: [
    RouterLink,
    Icon,
    DatePipe,
    HlmButton,
    HlmInput,
    HlmSelectImports,
    HlmSkeleton,
    HlmTableImports,
  ],
  host: { class: 'block' },
  templateUrl: './history.html',
})
export class History {
  private service = inject(RunsService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  readonly runs = signal<RunSummary[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal<boolean>(false);
  readonly error = signal<string>('');
  readonly workflows = signal<string[]>([]);
  readonly search = signal<string>('');
  /** The filter as the URL currently describes it — the single source of truth. */
  readonly filter = signal<RunFilter>({ limit: PAGE_SIZE });

  readonly states = STATES;
  /** Fixed-count placeholder rows while the first page is in flight. */
  readonly skeletonRows = [0, 1, 2];

  readonly filterActive = computed(() => {
    const f = this.filter();
    return !!(f.workflow || f.state?.length || f.q || f.sort === 'oldest');
  });

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      if (this.searchTimer) {
        clearTimeout(this.searchTimer);
      }
    });
    this.service.workflowsSeen().subscribe({
      next: (names) => this.workflows.set(names),
      error: () => {
        // The picker just stays empty; the rest of the page still works.
      },
    });
    // Re-query whenever the URL changes, and only then.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const states = params.getAll('state');
      const filter: RunFilter = {
        workflow: params.get('workflow') ?? undefined,
        state: states.length ? states : undefined,
        q: params.get('q') ?? undefined,
        sort: params.get('sort') === 'oldest' ? 'oldest' : 'newest',
        limit: PAGE_SIZE,
      };
      this.filter.set(filter);
      // Don't stomp the box while a debounce is still pending — the URL is behind the keyboard.
      if (this.searchTimer === null) {
        this.search.set(params.get('q') ?? '');
      }
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
        this.error.set(apiError(e, 'Could not load runs'));
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
        this.error.set(apiError(e, 'Could not load more runs'));
        this.loading.set(false);
      },
    });
  }

  /** Every control writes to the URL; nothing filters in place. */
  private apply(change: Record<string, string | string[] | null>): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: change,
      queryParamsHandling: 'merge',
    });
  }

  onSearch(value: string): void {
    this.search.set(value);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => {
      this.searchTimer = null;
      this.applySearch();
    }, SEARCH_DEBOUNCE_MS);
  }

  flushSearch(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }
    this.applySearch();
  }

  private applySearch(): void {
    this.apply({ q: this.search().trim() || null });
  }

  setWorkflow(name: string): void {
    this.apply({ workflow: name || null });
  }

  toggleState(state: string): void {
    const current = this.filter().state ?? [];
    const next = current.includes(state)
      ? current.filter((s) => s !== state)
      : [...current, state];
    this.apply({ state: next.length ? next : null });
  }

  hasState(state: string): boolean {
    return this.filter().state?.includes(state) ?? false;
  }

  setSort(sort: 'newest' | 'oldest'): void {
    this.apply({ sort: sort === 'oldest' ? 'oldest' : null });
  }

  clear(): void {
    this.router.navigate([], { relativeTo: this.route, queryParams: {} });
  }

  view(state: string) {
    return statusView(state);
  }

  dur(ms: number | null): string {
    return formatDuration(ms);
  }
}
