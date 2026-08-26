import { Component, computed, inject, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import { WorkflowSummary } from '../data/model';
import { WorkflowsService } from '../data/workflows.service';

/** Colour per strategy, so a list of thirty is scannable by shape rather than by reading. */
const STRATEGY_STYLE: Record<string, string> = {
  linear: 'bg-sky-500/15 text-sky-300',
  graph: 'bg-violet-500/15 text-violet-300',
  'map-reduce': 'bg-amber-500/15 text-amber-300',
  conditional: 'bg-emerald-500/15 text-emerald-300',
  rework: 'bg-rose-500/15 text-rose-300',
  reconciler: 'bg-indigo-500/15 text-indigo-300',
};

@Component({
  selector: 'workflow-list',
  imports: [MatButton, MatIcon, RouterLink],
  host: { class: 'block' },
  template: `
    <div
      class="mx-auto flex w-full max-w-[80rem] flex-auto flex-col p-6 lg:px-10 lg:py-8"
    >
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div class="flex items-center gap-x-3.5">
          <div
            class="flex size-10 shrink-0 items-center justify-center rounded-lg border border-white/5 bg-white/[0.03]"
          >
            <mat-icon
              svgIcon="list-todo"
              class="text-primary icon-size-6"
            />
          </div>
          <div>
            <h1 class="leading-tight text-2xl font-semibold tracking-tight">
              Workflows
            </h1>
            <p class="text-secondary text-sm">
              What the agents run, and how their steps are ordered.
            </p>
          </div>
        </div>
        <button
          mat-flat-button
          color="primary"
          routerLink="/workflows/new"
        >
          <mat-icon svgIcon="plus" />New workflow
        </button>
      </div>

      <div class="relative mt-6">
        <mat-icon
          svgIcon="search"
          class="text-secondary icon-size-4 pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
        />
        <input
          [value]="filter()"
          (input)="filter.set($any($event.target).value)"
          placeholder="Filter by name, description, tag, strategy or template…"
          class="focus:border-primary h-11 w-full rounded-lg border border-white/10 bg-white/[0.03] pr-3 pl-9 text-sm text-current focus:outline-none"
        />
      </div>

      @if (tags().length) {
        <div class="mt-3 flex flex-wrap gap-2">
          @for (t of tags(); track t) {
            <button
              type="button"
              (click)="toggleTag(t)"
              [class]="chip(filter().includes(t))"
              class="rounded-full border px-2.5 py-1 text-xs"
            >
              {{ t }}
            </button>
          }
        </div>
      }

      <div class="text-secondary mt-4 text-xs">
        {{ shown().length }} of {{ all().length }}
      </div>

      <div class="mt-2 grid grid-cols-1 gap-3 lg:grid-cols-2">
        @for (w of shown(); track w.name) {
          <section
            class="flex flex-col rounded-xl border border-white/5 bg-white/[0.02] p-4 transition-colors hover:border-white/15"
          >
            <div class="flex flex-wrap items-center gap-2">
              <a
                [routerLink]="['/workflows', w.name, 'edit']"
                class="font-medium hover:underline"
                >{{ w.name }}</a
              >
              <span
                class="rounded px-1.5 py-0.5 text-[10px] font-medium"
                [class]="strategyStyle(w.strategy)"
                >{{ w.strategy }}</span
              >
              <span class="text-secondary text-xs"
                >{{ w.steps }} {{ w.steps === 1 ? 'step' : 'steps' }}</span
              >
              <span class="flex-auto"></span>
              <a
                mat-button
                [routerLink]="['/workflows', w.name, 'run']"
                ><mat-icon svgIcon="play" />Run</a
              >
              <a
                mat-button
                [routerLink]="['/workflows', w.name, 'edit']"
                ><mat-icon svgIcon="pencil" />Edit</a
              >
            </div>
            @if (w.description) {
              <p class="text-secondary mt-1.5 line-clamp-2 text-sm">
                {{ w.description }}
              </p>
            }
            <div class="mt-2 flex flex-wrap items-center gap-1.5 text-[11px]">
              @for (t of w.tags; track t) {
                <span class="rounded-full bg-white/10 px-2 py-0.5">{{
                  t
                }}</span>
              }
              @for (t of w.templates; track t) {
                <span
                  class="text-secondary rounded bg-white/[0.04] px-1.5 py-0.5 font-mono"
                  >{{ t }}</span
                >
              }
            </div>
          </section>
        }
      </div>

      @if (shown().length === 0) {
        <div
          class="text-secondary mt-6 rounded-xl border border-dashed border-white/10 px-6 py-16 text-center text-sm"
        >
          @if (all().length === 0) {
            No workflows yet.
          } @else {
            Nothing matches “{{ filter() }}”.
          }
        </div>
      }
    </div>
  `,
})
export default class WorkflowList {
  private service = inject(WorkflowsService);
  private router = inject(Router);

  all = signal<WorkflowSummary[]>([]);
  filter = signal<string>('');

  /** Every tag in use, for the quick chips. */
  tags = computed(() => {
    const seen = new Set<string>();
    for (const w of this.all()) {
      for (const t of w.tags ?? []) {
        seen.add(t);
      }
    }
    return [...seen].sort();
  });

  /** One box over name, description, strategy, tags and templates — all the ways you'd look. */
  shown = computed(() => {
    const q = this.filter().trim().toLowerCase();
    if (!q) {
      return this.all();
    }
    const terms = q.split(/\s+/);
    return this.all().filter((w) => {
      const hay = [
        w.name,
        w.description,
        w.strategy,
        ...(w.tags ?? []),
        ...(w.templates ?? []),
      ]
        .join(' ')
        .toLowerCase();
      return terms.every((t) => hay.includes(t));
    });
  });

  constructor() {
    this.service.list().subscribe({ next: (list) => this.all.set(list) });
  }

  toggleTag(tag: string): void {
    const current = this.filter().trim();
    this.filter.set(
      current.includes(tag)
        ? current.replace(tag, '').trim()
        : `${current} ${tag}`.trim()
    );
  }

  strategyStyle(strategy: string): string {
    return STRATEGY_STYLE[strategy] ?? 'bg-white/10 text-neutral-300';
  }

  chip(active: boolean): string {
    return active
      ? 'border-primary/50 bg-primary/20 text-primary'
      : 'border-white/10 bg-white/[0.03] hover:bg-white/10';
  }
}
