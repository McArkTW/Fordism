import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { apiError } from '../../core/api-error';
import { WorkflowSummary } from '../../core/api/workflows.model';
import { WorkflowsService } from '../../core/api/workflows.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';

/** Browse the workflows: what the agents run, and how their steps are ordered. */
@Component({
  selector: 'workflow-list',
  imports: [RouterLink, Icon, MatButtonModule],
  host: { class: 'block' },
  template: `
    <div class="mx-auto w-full max-w-6xl p-6">
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 class="page-h">Workflows</h1>
          <p class="mt-1 text-sm text-muted">What the agents run, and how their steps are ordered.</p>
        </div>
        <a matButton="filled" routerLink="/workflows/new"><app-icon name="plus" />New workflow</a>
      </div>

      <div class="relative mt-5">
        <app-icon name="search" class="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted" />
        <input
          class="input pl-9"
          placeholder="Filter by name, description, tag, strategy or template…"
          [value]="filter()"
          (input)="filter.set($any($event.target).value)"
        />
      </div>

      @if (tags().length) {
        <div class="mt-3 flex flex-wrap gap-2">
          @for (t of tags(); track t) {
            <button
              type="button"
              (click)="toggleTag(t)"
              class="rounded-full border px-2.5 py-1 text-xs transition"
              [class]="filter().includes(t) ? 'border-accent bg-accent/15 text-accent' : 'border-edge bg-panel text-muted hover:text-ink'"
            >
              {{ t }}
            </button>
          }
        </div>
      }

      @if (loading()) {
        <div class="mt-10 flex justify-center text-muted"><app-icon name="loader" class="spin text-xl" /></div>
      } @else {
        <div class="mt-4 text-xs text-muted">{{ shown().length }} of {{ all().length }}</div>

        <div class="mt-2 grid grid-cols-1 gap-3 lg:grid-cols-2">
          @for (w of shown(); track w.name) {
            <section class="card p-4 transition hover:border-muted">
              <div class="flex flex-wrap items-center gap-2">
                <a class="font-medium hover:underline" [routerLink]="['/workflows', w.name, 'edit']">{{ w.name }}</a>
                <span class="badge-muted">{{ w.strategy }}</span>
                <span class="text-xs text-muted">{{ w.steps }} {{ w.steps === 1 ? 'step' : 'steps' }}</span>
                <span class="flex-auto"></span>
                <!-- Outlined, not filled: a filled Run in every card would drown the page's one primary action. -->
                <a matButton="outlined" [routerLink]="['/workflows', w.name, 'run']"><app-icon name="play" />Run</a>
                <a matButton="outlined" [routerLink]="['/workflows', w.name, 'edit']"><app-icon name="pencil" />Edit</a>
              </div>
              @if (w.description) {
                <p class="mt-1.5 line-clamp-2 text-sm text-muted">{{ w.description }}</p>
              }
              @if (w.tags.length || w.templates.length) {
                <div class="mt-2 flex flex-wrap items-center gap-1.5">
                  @for (t of w.tags; track t) {
                    <span class="rounded-full bg-ink/10 px-2 py-0.5 text-[11px]">{{ t }}</span>
                  }
                  @for (t of w.templates; track t) {
                    <span class="rounded bg-ink/5 px-1.5 py-0.5 font-mono text-[11px] text-muted">{{ t }}</span>
                  }
                </div>
              }
            </section>
          }
        </div>

        @if (shown().length === 0) {
          <div class="card mt-4 border-dashed px-6 py-16 text-center">
            @if (all().length === 0) {
              <p class="text-sm text-muted">
                No workflows yet. A workflow is the YAML that tells the agents what to run and in what order.
              </p>
              <a matButton="filled" class="mt-4" routerLink="/workflows/new"><app-icon name="plus" />Create your first workflow</a>
            } @else {
              <p class="text-sm text-muted">Nothing matches "{{ filter() }}".</p>
            }
          </div>
        }
      }
    </div>
  `,
})
export class WorkflowList {
  private service = inject(WorkflowsService);
  private toasts = inject(Toasts);

  readonly all = signal<WorkflowSummary[]>([]);
  readonly filter = signal('');
  readonly loading = signal(true);

  /** Every tag in use, for the quick chips. */
  readonly tags = computed(() => {
    const seen = new Set<string>();
    for (const w of this.all()) {
      for (const t of w.tags ?? []) {
        seen.add(t);
      }
    }
    return [...seen].sort();
  });

  /** One box over name, description, strategy, tags and templates — all the ways you'd look. */
  readonly shown = computed(() => {
    const query = this.filter().trim().toLowerCase();
    if (!query) {
      return this.all();
    }
    const terms = query.split(/\s+/);
    return this.all().filter((w) => {
      const hay = [w.name, w.description, w.strategy, ...(w.tags ?? []), ...(w.templates ?? [])]
        .join(' ')
        .toLowerCase();
      return terms.every((t) => hay.includes(t));
    });
  });

  constructor() {
    this.service.list().subscribe({
      next: (list) => {
        this.all.set(list);
        this.loading.set(false);
      },
      error: (e) => {
        this.loading.set(false);
        this.toasts.error(apiError(e, 'Could not load workflows'));
      },
    });
  }

  toggleTag(tag: string): void {
    const current = this.filter().trim();
    this.filter.set(current.includes(tag) ? current.replace(tag, '').trim() : `${current} ${tag}`.trim());
  }
}
