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
import { formatDistanceToNow } from 'date-fns';
import { errorMessage } from '@/app/core/api-error';
import {
  Preflight,
  RunSummary,
  StagedFile,
  WorkflowDetail,
} from '../data/model';
import { WorkflowsService } from '../data/workflows.service';

/**
 * Start one workflow.
 *
 * Files are inputs. The workflow's own `task:` is the task — an upload named task.md is refused
 * rather than allowed to replace it, because two sources for the task is how you end up running
 * something other than what the workflow says.
 */
@Component({
  selector: 'workflow-run',
  imports: [MatButton, MatIcon, RouterLink],
  host: { class: 'block' },
  templateUrl: './workflow-run.html',
})
export default class WorkflowRun {
  name = input<string>('');

  private service = inject(WorkflowsService);
  private router = inject(Router);

  detail = signal<WorkflowDetail | null>(null);
  preflight = signal<Preflight | null>(null);
  runs = signal<RunSummary[]>([]);
  files = signal<StagedFile[]>([]);
  values = signal<Record<string, string>>({});
  dragging = signal(false);
  busy = signal(false);
  error = signal('');

  /** Required parameters still blank — shown beside the button rather than failing on press. */
  missing = computed(() => {
    const w = this.detail();
    if (!w) {
      return [];
    }
    return w.parameters
      .filter((p) => p.required && !this.value(p.name).trim())
      .map((p) => p.name);
  });

  canRun = computed(
    () =>
      this.missing().length === 0 &&
      !this.files().some((f) => this.isTaskMd(f.name)) &&
      (this.preflight()?.ready ?? true)
  );

  /** The name this page has already loaded, so a re-render does not refetch. */
  private loaded = signal<string>('');

  constructor() {
    // Routed inputs are bound after the constructor, so load from an effect rather than reading
    // name() here, which would be '' and fetch nothing.
    effect(() => {
      const name = this.name();
      if (!name || this.loaded() === name) {
        return;
      }
      this.loaded.set(name);
      this.service.get(name).subscribe({
        next: (w) => {
          this.detail.set(w);
          const seeded: Record<string, string> = {};
          for (const p of w.parameters) {
            seeded[p.name] = p.defaultValue ?? '';
          }
          this.values.set(seeded);
        },
        error: (e) => this.error.set(errorMessage(e)),
      });
      this.service
        .preflight(name)
        .subscribe({ next: (p) => this.preflight.set(p) });
      this.service
        .runs(name)
        .subscribe({ next: (list) => this.runs.set(list.slice(0, 10)) });
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
    this.add((event.target as HTMLInputElement).files);
  }

  private add(list: FileList | null | undefined): void {
    if (!list) {
      return;
    }
    const staged = [...list].map((file) => ({ name: file.name, file }));
    this.files.update((current) => [...current, ...staged]);
  }

  rename(index: number, name: string): void {
    this.files.update((list) =>
      list.map((f, i) => (i === index ? { ...f, name } : f))
    );
  }

  removeFile(index: number): void {
    this.files.update((list) => list.filter((_f, i) => i !== index));
  }

  start(): void {
    this.error.set('');
    this.busy.set(true);
    this.service.run(this.name(), this.values(), this.files()).subscribe({
      next: (started) => {
        this.busy.set(false);
        this.router.navigate(['/runs'], {
          queryParams: { run: started.runId },
        });
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  size(bytes: number): string {
    return bytes < 1024
      ? `${bytes} B`
      : bytes < 1024 * 1024
        ? `${Math.round(bytes / 1024)} KB`
        : `${(bytes / 1048576).toFixed(1)} MB`;
  }

  duration(ms: number): string {
    const s = Math.round(ms / 1000);
    return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
  }

  when(ms: number): string {
    return ms ? formatDistanceToNow(ms, { addSuffix: true }) : '';
  }

  stateStyle(state: string): string {
    switch (state) {
      case 'DONE':
        return 'bg-emerald-500/15 text-emerald-300';
      case 'FAILED':
        return 'bg-red-500/15 text-red-300';
      case 'ASKED':
        return 'bg-amber-500/15 text-amber-300';
      default:
        return 'bg-sky-500/15 text-sky-300';
    }
  }
}
