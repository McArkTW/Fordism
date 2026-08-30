import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import {
  HlmTable,
  HlmTableContainer,
  HlmTBody,
  HlmTd,
  HlmTh,
  HlmTHead,
  HlmTr,
} from '@spartan-ng/helm/table';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { HlmTooltip } from '@spartan-ng/helm/tooltip';
import { apiError } from '../../core/api-error';
import { RunSummary } from '../../core/api/runs.service';
import { Preflight, StagedFile, WorkflowDetail } from '../../core/api/workflows.model';
import { WorkflowsService } from '../../core/api/workflows.service';
import { Icon } from '../../core/icon';
import { formatDuration, formatSize, statusView } from '../../core/status';
import { Toasts } from '../../core/toast';

/**
 * Start one workflow.
 *
 * Files are inputs. The workflow's own `task:` is the task — an upload named task.md is refused
 * rather than allowed to replace it, because two sources for the task is how you end up running
 * something other than what the workflow says.
 */
@Component({
  selector: 'app-workflow-run',
  imports: [
    RouterLink,
    Icon,
    DatePipe,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmSpinner,
    HlmTextarea,
    HlmTooltip,
    HlmTableContainer,
    HlmTable,
    HlmTHead,
    HlmTBody,
    HlmTr,
    HlmTh,
    HlmTd,
  ],
  host: { class: 'block' },
  templateUrl: './workflow-run.html',
})
export class WorkflowRun {
  readonly name = input<string>();

  private service = inject(WorkflowsService);
  private router = inject(Router);
  private toasts = inject(Toasts);

  readonly detail = signal<WorkflowDetail | null>(null);
  readonly preflight = signal<Preflight | null>(null);
  readonly recent = signal<RunSummary[]>([]);
  readonly files = signal<StagedFile[]>([]);
  readonly values = signal<Record<string, string>>({});
  readonly dragging = signal(false);
  readonly busy = signal(false);
  readonly loadError = signal('');
  /** Why the preflight check itself did not answer — distinct from a check that said "not ready". */
  readonly preflightError = signal('');
  readonly recentError = signal('');

  protected readonly sv = statusView;
  protected readonly duration = formatDuration;
  protected readonly size = formatSize;

  /** Required parameters still blank — shown beside the button rather than failing on press. */
  readonly missing = computed(() => {
    const w = this.detail();
    if (!w) {
      return [];
    }
    return w.parameters.filter((p) => p.required && !this.value(p.name).trim()).map((p) => p.name);
  });

  /**
   * Run is enabled only once preflight has said yes.
   *
   * This used to read `this.preflight()?.ready ?? true`, so a preflight request that FAILED left
   * the button enabled and the warning hidden — the app looked most confident exactly where it
   * knew least. An unanswered check is not a pass: while it is in flight or errored, Run stays
   * disabled, and the page says which of the two it is.
   */
  readonly canRun = computed(
    () =>
      this.missing().length === 0 &&
      !this.files().some((f) => this.isTaskMd(f.name)) &&
      this.preflight()?.ready === true,
  );

  /** The first poll is still out: Run is disabled, but nothing is wrong yet. */
  readonly checkingPreflight = computed(() => !this.preflight() && !this.preflightError());

  /** The name already loaded, so a re-render does not refetch. */
  private loadedFor = signal('');

  constructor() {
    // Routed inputs bind after construction — reading name() here would see undefined and fetch nothing.
    effect(() => {
      const name = this.name() ?? '';
      if (!name || this.loadedFor() === name) {
        return;
      }
      this.loadedFor.set(name);
      this.service.get(name).subscribe({
        next: (w) => {
          this.detail.set(w);
          const seeded: Record<string, string> = {};
          for (const p of w.parameters) {
            seeded[p.name] = p.defaultValue ?? '';
          }
          this.values.set(seeded);
        },
        error: (e) => this.loadError.set(apiError(e, `Could not load "${name}"`)),
      });
      this.service.preflight(name).subscribe({
        next: (p) => {
          this.preflight.set(p);
          this.preflightError.set('');
        },
        error: (e) => this.preflightError.set(apiError(e, 'the check did not answer')),
      });
      this.service.runs(name).subscribe({
        next: (list) => {
          this.recent.set(list.slice(0, 10));
          this.recentError.set('');
        },
        error: (e) => this.recentError.set(apiError(e, 'Could not load recent runs')),
      });
    });
  }

  value(name: string): string {
    return this.values()[name] ?? '';
  }

  set(name: string, value: string): void {
    this.values.update((v) => ({ ...v, [name]: value }));
  }

  isTaskMd(name: string): boolean {
    return name.trim().toLowerCase() === 'task.md';
  }

  drop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.add(event.dataTransfer?.files);
  }

  pick(event: Event): void {
    const picker = event.target as HTMLInputElement;
    this.add(picker.files);
    // So picking the same file again still fires (change).
    picker.value = '';
  }

  private add(list: FileList | null | undefined): void {
    if (!list) {
      return;
    }
    const staged: StagedFile[] = [];
    for (const file of Array.from(list)) {
      if (this.isTaskMd(file.name)) {
        this.toasts.error('task.md comes from the workflow itself and cannot be uploaded.');
        continue;
      }
      staged.push({ name: file.name, file });
    }
    if (staged.length) {
      this.files.update((current) => [...current, ...staged]);
    }
  }

  rename(index: number, name: string): void {
    this.files.update((list) => list.map((f, i) => (i === index ? { ...f, name } : f)));
  }

  removeFile(index: number): void {
    this.files.update((list) => list.filter((_f, i) => i !== index));
  }

  start(): void {
    this.busy.set(true);
    this.service.run(this.name() ?? '', this.values(), this.files()).subscribe({
      next: (started) => {
        this.busy.set(false);
        this.toasts.ok(`Run started: ${started.runId.slice(0, 8)}`);
        this.router.navigate(['/runs', started.runId]);
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'Could not start the run'));
      },
    });
  }
}
