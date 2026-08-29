import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { apiError } from '../../core/api-error';
import { WorkflowSummary } from '../../core/api/workflows.model';
import { WorkflowsService } from '../../core/api/workflows.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';

/** Browse the workflows: what the agents run, and how their steps are ordered. */
@Component({
  selector: 'app-workflow-list',
  imports: [RouterLink, Icon, HlmButton, HlmBadge, HlmInput, HlmSpinner],
  host: { class: 'block' },
  template: `
    <div class="mx-auto flex w-full max-w-6xl flex-col gap-6">
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 class="page-h">Workflows</h1>
          <p class="text-muted-foreground text-sm">What the agents run, and how their steps are ordered.</p>
        </div>
        <!-- The page's one filled button: creating a workflow is what you come here to do next. -->
        <a hlmBtn routerLink="/workflows/new"><app-icon name="plus" />New workflow</a>
      </div>

      <div class="flex flex-col gap-3">
        <div class="relative">
          <app-icon
            name="search"
            class="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2"
          />
          <input
            hlmInput
            class="pl-8"
            placeholder="Filter by name, description, tag, strategy or template…"
            [value]="filter()"
            (input)="filter.set($any($event.target).value)"
          />
        </div>

        @if (tags().length) {
          <div class="flex flex-wrap gap-2">
            @for (t of tags(); track t) {
              <!-- Chips are a toggle over the search text, not a second filter — hence hand-rolled,
                   not hlmBadge: the selected state needs the primary tint a badge variant lacks. -->
              <button
                type="button"
                (click)="toggleTag(t)"
                class="cursor-pointer rounded-full border px-2.5 py-1 text-xs transition"
                [class]="
                  filter().includes(t)
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border bg-card text-muted-foreground hover:text-foreground'
                "
              >
                {{ t }}
              </button>
            }
          </div>
        }
      </div>

      @if (loading()) {
        <div class="text-muted-foreground flex justify-center py-10"><hlm-spinner class="text-2xl" /></div>
      } @else {
        <div class="flex flex-col gap-2">
          <div class="text-muted-foreground text-xs">{{ shown().length }} of {{ all().length }}</div>

          <div class="grid grid-cols-1 gap-3 lg:grid-cols-2">
            @for (w of shown(); track w.name) {
              <section class="border-border bg-card hover:border-ring/40 rounded-xl border p-4 shadow-xs transition">
                <div class="flex flex-wrap items-center gap-2">
                  <a class="font-medium hover:underline" [routerLink]="['/workflows', w.name, 'edit']">{{ w.name }}</a>
                  <span hlmBadge variant="outline">{{ w.strategy }}</span>
                  <span class="text-muted-foreground text-xs">{{ w.steps }} {{ w.steps === 1 ? 'step' : 'steps' }}</span>
                  <span class="flex-auto"></span>
                  <!-- Outline, not filled: a filled Run in every card would drown the page's one primary action. -->
                  <a hlmBtn variant="outline" size="sm" [routerLink]="['/workflows', w.name, 'run']">
                    <app-icon name="play" />Run
                  </a>
                  <a hlmBtn variant="ghost" size="sm" [routerLink]="['/workflows', w.name, 'edit']">
                    <app-icon name="pencil" />Edit
                  </a>
                </div>
                @if (w.description) {
                  <p class="text-muted-foreground mt-1.5 line-clamp-2 text-sm">{{ w.description }}</p>
                }
                @if (w.tags.length || w.templates.length) {
                  <div class="mt-2 flex flex-wrap items-center gap-1.5">
                    @for (t of w.tags; track t) {
                      <span hlmBadge variant="secondary">{{ t }}</span>
                    }
                    @for (t of w.templates; track t) {
                      <span class="bg-muted text-muted-foreground rounded px-1.5 py-0.5 font-mono text-[11px]">
                        {{ t }}
                      </span>
                    }
                  </div>
                }
              </section>
            }
          </div>

          @if (shown().length === 0) {
            <div class="border-border bg-card mt-2 rounded-xl border border-dashed px-6 py-16 text-center">
              @if (all().length === 0) {
                <p class="text-muted-foreground text-sm">
                  No workflows yet. A workflow is the YAML that tells the agents what to run and in what order.
                </p>
                <a hlmBtn variant="outline" class="mt-4" routerLink="/workflows/new">
                  <app-icon name="plus" />Create your first workflow
                </a>
              } @else {
                <p class="text-muted-foreground text-sm">Nothing matches "{{ filter() }}".</p>
              }
            </div>
          }
        </div>
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
