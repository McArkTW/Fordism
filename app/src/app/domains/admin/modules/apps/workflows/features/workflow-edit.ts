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
import { errorMessage } from '@/app/core/api-error';
import { WorkflowParsed } from '../data/model';
import { WorkflowsService } from '../data/workflows.service';
import { YamlEditor } from '../ui/yaml-editor';

const STARTER = `name: new-workflow
description: What this workflow is for.
strategy: linear
tags: []
parameters:
  - name: goal
    label: Goal
    type: textarea
    required: true
steps:
  - id: work
    template: generic
    task: |
      \${goal}
`;

/**
 * Edit one workflow's YAML.
 *
 * The outline beside the editor is core's own parse, fetched on validate and save — the app never
 * parses YAML, so what you see is what the engine read and the two cannot disagree.
 */
@Component({
  selector: 'workflow-edit',
  imports: [MatButton, MatIcon, RouterLink, YamlEditor],
  host: { class: 'block h-full' },
  template: `
    <div class="flex h-full w-full flex-auto flex-col p-6 lg:px-10 lg:py-6">
      <div class="flex flex-wrap items-center gap-3">
        <a
          mat-button
          routerLink="/workflows"
          ><mat-icon svgIcon="arrow-left" />Workflows</a
        >
        <h1 class="text-xl font-semibold tracking-tight">
          {{ isNew() ? 'New workflow' : name() }}
        </h1>
        <span class="flex-auto"></span>
        @if (!isNew()) {
          <a
            mat-button
            [routerLink]="['/workflows', name(), 'run']"
            ><mat-icon svgIcon="play" />Run</a
          >
        }
        <button
          mat-button
          [disabled]="busy()"
          (click)="validate()"
        >
          <mat-icon svgIcon="check-check" />Validate
        </button>
        <button
          mat-flat-button
          color="primary"
          [disabled]="busy()"
          (click)="save(false)"
        >
          <mat-icon svgIcon="save" />Save
        </button>
        @if (!isNew()) {
          <button
            mat-button
            (click)="remove()"
          >
            <mat-icon svgIcon="trash-2" />Delete
          </button>
        }
      </div>

      @if (error()) {
        <div
          class="mt-3 rounded-lg border border-red-500/30 bg-red-500/[0.07] px-3 py-2 text-sm text-red-300"
        >
          {{ error() }}
          @if (renameFrom()) {
            <button
              mat-button
              class="ml-2"
              (click)="save(true)"
            >
              Save as a new workflow
            </button>
          }
        </div>
      }
      @if (message()) {
        <div
          class="mt-3 rounded-lg border border-emerald-500/30 bg-emerald-500/[0.07] px-3 py-2 text-sm text-emerald-300"
        >
          {{ message() }}
        </div>
      }

      <div
        class="mt-4 grid min-h-0 flex-auto grid-cols-1 gap-4 xl:grid-cols-[1fr_20rem]"
      >
        <yaml-editor
          class="min-h-[28rem]"
          [value]="yaml()"
          (changed)="yaml.set($event)"
        />

        <aside class="flex min-h-0 flex-col gap-3 overflow-y-auto">
          @if (outline(); as o) {
            <div class="rounded-xl border border-white/5 bg-white/[0.02] p-3">
              <div
                class="text-secondary text-[10px] font-semibold tracking-wide uppercase"
              >
                Parsed by core
              </div>
              <div class="mt-2 flex flex-wrap items-center gap-2 text-xs">
                <span class="font-mono">{{ o.name }}</span>
                <span class="rounded bg-white/10 px-1.5 py-0.5">{{
                  o.strategy
                }}</span>
                @for (t of o.tags; track t) {
                  <span class="rounded-full bg-white/10 px-2 py-0.5">{{
                    t
                  }}</span>
                }
              </div>
            </div>

            @if (o.parameters.length) {
              <div class="rounded-xl border border-white/5 bg-white/[0.02] p-3">
                <div
                  class="text-secondary text-[10px] font-semibold tracking-wide uppercase"
                >
                  Parameters
                </div>
                @for (p of o.parameters; track p.name) {
                  <div class="mt-1.5 flex items-center gap-2 text-xs">
                    <span class="font-mono">{{ p.name }}</span>
                    <span class="text-secondary">{{ p.type }}</span>
                    @if (p.required) {
                      <span class="text-amber-300">required</span>
                    }
                  </div>
                }
              </div>
            }

            <div class="rounded-xl border border-white/5 bg-white/[0.02] p-3">
              <div
                class="text-secondary text-[10px] font-semibold tracking-wide uppercase"
              >
                Steps · {{ o.steps.length }}
              </div>
              @for (s of o.steps; track s.id; let i = $index) {
                <div class="mt-2 border-l-2 border-white/10 pl-2.5">
                  <div class="flex flex-wrap items-center gap-1.5 text-xs">
                    <span class="text-secondary">{{ i }}</span>
                    <span class="font-mono">{{ s.id }}</span>
                  </div>
                  <div
                    class="text-secondary mt-0.5 flex flex-wrap items-center gap-1.5 text-[11px]"
                  >
                    @if (s.template) {
                      <span
                        class="rounded bg-white/[0.06] px-1.5 py-0.5 font-mono"
                        >{{ s.template }}</span
                      >
                    }
                    @if (s.dependsOn.length) {
                      <span>after {{ s.dependsOn.join(', ') }}</span>
                    }
                    @if (s.forEach) {
                      <span>for each {{ s.forEach }}</span>
                    }
                    @if (s.when) {
                      <span>when {{ s.when }}</span>
                    }
                    @if (s.includePreviousResult) {
                      <span>+prev result</span>
                    }
                    <span>{{ s.timeoutSeconds }}s</span>
                  </div>
                </div>
              }
              @if (o.generator) {
                <div
                  class="mt-2 border-l-2 border-indigo-400/40 pl-2.5 text-xs"
                >
                  <span class="text-secondary">generator ·</span>
                  <span class="font-mono">{{ o.generator }}</span>
                  <span class="text-secondary"
                    >· max {{ o.maxIterations }}</span
                  >
                </div>
              }
            </div>
          } @else {
            <div
              class="text-secondary rounded-xl border border-dashed border-white/10 p-4 text-xs"
            >
              Press Validate to see how core reads this.
            </div>
          }
        </aside>
      </div>
    </div>
  `,
})
export default class WorkflowEdit {
  /** Route parameter; absent for /workflows/new. */
  name = input<string>('');

  private service = inject(WorkflowsService);
  private router = inject(Router);

  yaml = signal<string>('');
  outline = signal<WorkflowParsed | null>(null);
  error = signal<string>('');
  message = signal<string>('');
  busy = signal<boolean>(false);
  /** Set when core refused a rename, so the page can offer "save as new". */
  renameFrom = signal<string>('');

  isNew = computed(() => !this.name());

  /** The name this page has already loaded, so a re-render does not refetch or clobber edits. */
  private loaded = signal<string>('');

  constructor() {
    // Routed inputs are bound during change detection, after the constructor — reading name()
    // here would always see '' and every page would show the starter template.
    effect(() => {
      const name = this.name();
      if (this.loaded() === name) {
        return;
      }
      this.loaded.set(name);
      if (!name) {
        this.yaml.set(STARTER);
        this.outline.set(null);
        return;
      }
      this.service.get(name).subscribe({
        next: (w) => {
          this.yaml.set(w.yaml ?? '');
          this.outline.set(w);
        },
        error: (e) => this.error.set(errorMessage(e)),
      });
    });
  }

  validate(): void {
    this.clear();
    this.service.validate(this.yaml()).subscribe({
      next: (parsed) => {
        this.outline.set(parsed);
        this.message.set('Valid.');
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  save(saveAs: boolean): void {
    this.clear();
    this.busy.set(true);
    this.service.save(this.yaml(), this.name(), saveAs).subscribe({
      next: (parsed) => {
        this.busy.set(false);
        this.outline.set(parsed);
        this.message.set(`Saved “${parsed.name}”.`);
        if (parsed.name !== this.name()) {
          this.router.navigate(['/workflows', parsed.name, 'edit']);
        }
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
        if (e?.status === 409) {
          this.renameFrom.set(e?.error?.was ?? this.name());
        }
      },
    });
  }

  remove(): void {
    if (!confirm(`Delete workflow “${this.name()}”?`)) {
      return;
    }
    this.service.remove(this.name()).subscribe({
      next: () => this.router.navigate(['/workflows']),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  private clear(): void {
    this.error.set('');
    this.message.set('');
    this.renameFrom.set('');
  }
}
