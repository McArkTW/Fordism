import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router, RouterLink } from '@angular/router';
import { apiError } from '../../core/api-error';
import { ResultFile, RunDetail, RunsService, TaskView } from '../../core/api/runs.service';
import { Icon } from '../../core/icon';
import { formatDuration, formatSize, statusView } from '../../core/status';
import { Toasts } from '../../core/toast';
import { Markdown } from '../../shared/markdown';

/** One parsed transcript record — role + best-effort text + any tools it invoked. */
type TxEntry = { role: string; text: string; tools: string[] };

/** A result file plus how to render it. */
type FileView = { name: string; size: number; binary: boolean; markdown: boolean; content: string };

/**
 * Everything needed to investigate a single task without downloading anything: the summary,
 * result files previewed by type, the session transcript rendered readably — and the reply box
 * when the agent stopped to ask. Downloads are offered but never required.
 */
@Component({
  selector: 'app-task-detail',
  imports: [RouterLink, Icon, DatePipe, Markdown, MatButtonModule, MatFormFieldModule, MatInputModule],
  host: { class: 'block' },
  templateUrl: './task-detail.html',
})
export class TaskDetailPage {
  readonly id = input.required<string>();
  readonly taskId = input.required<string>();

  private service = inject(RunsService);
  private toasts = inject(Toasts);
  private router = inject(Router);

  readonly run = signal<RunDetail | null>(null);
  readonly error = signal<string>('');
  /** null while loading — distinct from a loaded-but-empty list. */
  readonly files = signal<FileView[] | null>(null);
  readonly resultError = signal<string>('');
  readonly openFiles = signal<Set<string>>(new Set());
  readonly transcript = signal<TxEntry[] | null>(null);
  readonly transcriptPending = signal<boolean>(true);
  readonly showTranscript = signal<boolean>(false);

  readonly reply = signal<string>('');
  /** Typed-but-unsent credential values, by environment-variable name. */
  readonly secrets = signal<Record<string, string>>({});
  readonly answering = signal<boolean>(false);

  readonly task = computed<TaskView | null>(
    () => this.run()?.tasks.find((t) => t.taskId === this.taskId()) ?? null,
  );

  /** Credentials the question still needs, vs. names an earlier answer already supplied. */
  readonly neededSecrets = computed(() => {
    const t = this.task();
    return (t?.secretsRequested ?? []).filter((name) => !(t?.secretsHeld ?? []).includes(name));
  });
  readonly heldSecrets = computed(() => {
    const t = this.task();
    return (t?.secretsRequested ?? []).filter((name) => (t?.secretsHeld ?? []).includes(name));
  });

  private loaded = '';

  constructor() {
    // Routed inputs bind after the constructor, so load from an effect rather than reading them here.
    effect(() => {
      const key = `${this.id()}|${this.taskId()}`;
      if (this.loaded === key) {
        return;
      }
      this.loaded = key;
      this.load(this.id(), this.taskId());
    });
  }

  private load(id: string, taskId: string): void {
    this.run.set(null);
    this.error.set('');
    this.files.set(null);
    this.resultError.set('');
    this.transcript.set(null);
    this.transcriptPending.set(true);

    this.service.get(id).subscribe({
      next: (detail) => this.run.set(detail),
      error: (e) => this.error.set(apiError(e, 'Could not load the run')),
    });
    this.service.result(taskId).subscribe({
      next: (result) => {
        this.files.set(result.files.map((f) => this.toView(f)));
        // First file open by default: the common case is one result file you came here to read.
        this.openFiles.set(new Set(result.files.length ? [result.files[0].name] : []));
      },
      error: (e) => {
        this.files.set([]);
        this.resultError.set(apiError(e, 'Could not load result files'));
      },
    });
    // 404 just means the task never wrote a transcript — absent, not an error.
    this.service.transcript(taskId).subscribe({
      next: (text) => {
        this.transcript.set(this.parseTranscript(text));
        this.transcriptPending.set(false);
      },
      error: () => this.transcriptPending.set(false),
    });
  }

  send(): void {
    const task = this.task();
    if (!task) {
      return;
    }
    const message = this.reply().trim();
    const secrets = this.filledSecrets();
    if (!message && Object.keys(secrets).length === 0) {
      return;
    }
    this.answering.set(true);
    this.service.answer(task.taskId, message, secrets).subscribe({
      next: () => {
        this.toasts.ok('Answered — the agent is continuing.');
        this.router.navigate(['/runs', this.id()]);
      },
      error: (e) => {
        this.answering.set(false);
        this.toasts.error(apiError(e, 'Could not send the answer'));
      },
    });
  }

  /** A reply alone answers it, and so does a credential alone; neither does not. */
  canSend(): boolean {
    return !!this.reply().trim() || Object.keys(this.filledSecrets()).length > 0;
  }

  secret(name: string): string {
    return this.secrets()[name] ?? '';
  }

  setSecret(name: string, value: string): void {
    this.secrets.update((m) => ({ ...m, [name]: value }));
  }

  private filledSecrets(): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [name, value] of Object.entries(this.secrets())) {
      if (value.trim()) {
        out[name] = value;
      }
    }
    return out;
  }

  toggleFile(name: string): void {
    this.openFiles.update((open) => {
      const next = new Set(open);
      if (next.has(name)) {
        next.delete(name);
      } else {
        next.add(name);
      }
      return next;
    });
  }

  copy(text: string): void {
    navigator.clipboard.writeText(text).then(
      () => this.toasts.ok('Copied to clipboard'),
      () => this.toasts.error('Could not copy'),
    );
  }

  resultZip(): string {
    return this.service.resultZipUrl(this.taskId());
  }

  workspaceZip(): string {
    return this.service.workspaceZipUrl(this.taskId());
  }

  view(state: string) {
    return statusView(state, this.run()?.state);
  }

  dur(ms: number | null): string {
    return formatDuration(ms);
  }

  sizeOf(bytes: number): string {
    return formatSize(bytes);
  }

  clip(text: string): string {
    return text.length > 2000 ? text.slice(0, 2000) + '…' : text;
  }

  roleBadge(role: string): string {
    switch (role) {
      case 'user':
        return 'badge-info';
      case 'assistant':
        return 'badge-ok';
      case 'result':
        return 'badge-warn';
      default:
        return 'badge-muted';
    }
  }

  private toView(f: ResultFile): FileView {
    return {
      name: f.name,
      size: f.size,
      binary: f.binary,
      markdown: !f.binary && f.name.toLowerCase().endsWith('.md'),
      content: f.content,
    };
  }

  /** Best-effort readable parse of an NDJSON transcript; unparseable lines are skipped. */
  private parseTranscript(text: string): TxEntry[] {
    const out: TxEntry[] = [];
    for (const line of text.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed) {
        continue;
      }
      let obj: Record<string, unknown>;
      try {
        obj = JSON.parse(trimmed) as Record<string, unknown>;
      } catch {
        continue;
      }
      const msg = (obj['message'] ?? {}) as Record<string, unknown>;
      const role = String(obj['role'] ?? msg['role'] ?? obj['type'] ?? 'event');
      const tools: string[] = [];
      let body = '';
      // Two transcript dialects. claude-code writes message.content[] with a `type` discriminator;
      // qwen-code writes message.parts[] with no type at all — a part is text, a functionCall or a
      // functionResponse depending on which key it has. Reading only `content` leaves every qwen
      // row rendered with its role and an empty body, which looks like a transcript that was not
      // captured rather than one that was not parsed.
      const content = msg['content'] ?? obj['content'] ?? msg['parts'] ?? obj['parts'];
      if (typeof content === 'string') {
        body = content;
      } else if (Array.isArray(content)) {
        for (const raw of content) {
          const part = raw as Record<string, unknown>;
          if (typeof raw === 'string') {
            body += raw;
          } else if (part['type'] === 'text') {
            body += String(part['text'] ?? '');
          } else if (part['type'] === 'thinking') {
            body += String(part['thinking'] ?? '');
          } else if (part['type'] === 'tool_use') {
            tools.push(String(part['name'] ?? 'tool'));
          } else if (part['type'] === 'tool_result') {
            const result = part['content'];
            if (typeof result === 'string') {
              body += result;
            } else if (Array.isArray(result)) {
              body += result
                .map((x) => String((x as Record<string, unknown>)?.['text'] ?? ''))
                .join('');
            }
          } else if (part['functionCall']) {
            const call = part['functionCall'] as Record<string, unknown>;
            tools.push(String(call?.['name'] ?? 'tool'));
          } else if (part['functionResponse']) {
            const response = part['functionResponse'] as Record<string, unknown>;
            const output = (response?.['response'] ?? {}) as Record<string, unknown>;
            body += String(output['output'] ?? output['error'] ?? '');
          } else if (typeof part['text'] === 'string') {
            // qwen text part; `thought` marks reasoning rather than an answer.
            body += part['thought'] ? '' : String(part['text']);
          }
        }
      }
      if (!body && typeof obj['text'] === 'string') {
        body = obj['text'];
      }
      if (!body && typeof obj['summary'] === 'string') {
        body = obj['summary'];
      }
      out.push({ role, text: body.trim(), tools });
    }
    return out;
  }
}
