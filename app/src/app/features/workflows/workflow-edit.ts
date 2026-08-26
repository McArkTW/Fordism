import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterLink } from '@angular/router';
import { apiError } from '../../core/api-error';
import { WorkflowParsed } from '../../core/api/workflows.model';
import { WorkflowsService } from '../../core/api/workflows.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';
import { YamlEditor } from '../../shared/yaml-editor';

/** What /workflows/new opens with — the smallest workflow that runs. */
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
 * The outline beside the editor is core's own parse, refreshed by a debounced validate on every
 * edit — the app never parses YAML itself, so the outline is exactly what the engine will read
 * and the two cannot disagree. While the current text fails to parse, the last good outline stays.
 *
 * Unsaved edits only feed the header hint; a route guard against navigating away is out of scope.
 */
@Component({
  selector: 'workflow-edit',
  imports: [RouterLink, Icon, YamlEditor, MatButtonModule],
  host: { class: 'block' },
  templateUrl: './workflow-edit.html',
  // Material's filled button has no destructive variant — repaint its tokens red for Delete.
  styles: `
    .btn-danger-mat {
      --mat-button-filled-container-color: var(--color-red-600, #dc2626);
      --mat-button-filled-label-text-color: #fff;
    }
  `,
})
export class WorkflowEdit {
  /** Route parameter; absent for /workflows/new. */
  readonly name = input<string>();

  private service = inject(WorkflowsService);
  private router = inject(Router);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  readonly yaml = signal('');
  /** The last good parse — kept on screen while the current text is broken. */
  readonly outline = signal<WorkflowParsed | null>(null);
  readonly parseError = signal('');
  readonly saveError = signal('');
  /** Core refused a changed name; "Save as new" is how you say you meant it. */
  readonly renameRefused = signal(false);
  readonly busy = signal(false);
  readonly dirty = signal(false);

  readonly isNew = computed(() => !this.name());

  /** The name already loaded, so a re-render neither refetches nor clobbers edits. */
  private loadedFor = signal<string | null>(null);
  private validateTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // Routed inputs bind after construction — reading name() here would always see undefined.
    effect(() => {
      const name = this.name() ?? '';
      if (this.loadedFor() === name) {
        return;
      }
      this.loadedFor.set(name);
      if (!name) {
        this.yaml.set(STARTER);
        this.dirty.set(false);
        this.validateNow(); // seed the outline so the page never opens blank
        return;
      }
      this.service.get(name).subscribe({
        next: (w) => {
          this.yaml.set(w.yaml ?? '');
          this.outline.set(w);
          this.dirty.set(false);
        },
        error: (e) => this.toasts.error(apiError(e, `Could not load "${name}"`)),
      });
    });
  }

  onYamlChange(text: string): void {
    // The editor echoes external sets back through (changed); only a real edit counts.
    if (text === this.yaml()) {
      return;
    }
    this.yaml.set(text);
    this.dirty.set(true);
    if (this.validateTimer) {
      clearTimeout(this.validateTimer);
    }
    // Debounced so a keystroke burst becomes one round-trip to core.
    this.validateTimer = setTimeout(() => this.validateNow(), 500);
  }

  private validateNow(): void {
    this.service.validate(this.yaml()).subscribe({
      next: (parsed) => {
        this.outline.set(parsed);
        this.parseError.set('');
      },
      error: (e) => this.parseError.set(apiError(e, 'The YAML does not parse')),
    });
  }

  save(saveAs = false): void {
    this.saveError.set('');
    this.renameRefused.set(false);
    this.busy.set(true);
    this.service.save(this.yaml(), this.name() ?? '', saveAs).subscribe({
      next: (parsed) => {
        this.busy.set(false);
        this.dirty.set(false);
        this.toasts.ok(`Saved "${parsed.name}".`);
        this.router.navigate(['/workflows']);
      },
      error: (e) => {
        this.busy.set(false);
        const message = apiError(e, 'Save failed');
        this.saveError.set(message);
        // A workflow is keyed by its name, so core refuses a rename unless saveAs says you meant it.
        const conflict = e instanceof HttpErrorResponse && e.status === 409;
        if (!this.isNew() && (conflict || message.toLowerCase().includes('name'))) {
          this.renameRefused.set(true);
        }
      },
    });
  }

  async remove(): Promise<void> {
    const name = this.name();
    if (!name) {
      return;
    }
    const sure = await this.confirm.ask(
      'Delete workflow?',
      'This removes the stored YAML. Runs already made are kept.',
      'Delete',
      true,
    );
    if (!sure) {
      return;
    }
    this.service.remove(name).subscribe({
      next: () => {
        this.toasts.ok(`Deleted "${name}".`);
        this.router.navigate(['/workflows']);
      },
      error: (e) => this.toasts.error(apiError(e, 'Delete failed')),
    });
  }
}
