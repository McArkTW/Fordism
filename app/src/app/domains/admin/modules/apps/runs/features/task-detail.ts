import {
  Component,
  ViewEncapsulation,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { MatAnchor, MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { marked } from 'marked';
import { errorMessage } from '@/app/core/api-error';
import { formatDuration, formatSize, statusView } from '../data/run-status';
import {
  ResultFile,
  RunsService,
  TaskResultView,
  TaskView,
} from '../data/runs.service';

/** One parsed transcript record — role + best-effort text + any tools it invoked. */
type TxEntry = { role: string; text: string; tools: string[] };

/** A result file prepared for display: how to render it + rendered payload. */
type FileView = {
  name: string;
  size: number;
  kind: 'md' | 'text' | 'binary';
  html: string;
  content: string;
};

/**
 * The expanded detail for a single task — everything needed to investigate a run
 * without downloading anything: the result summary, artifact files previewed by
 * type (markdown rendered, text/code shown inline), logs, and the session
 * transcript rendered readably. Downloads are offered but never required.
 */
@Component({
  selector: 'task-detail',
  imports: [MatButton, MatAnchor, MatIcon],
  host: { class: 'block' },
  encapsulation: ViewEncapsulation.None,
  styleUrls: ['./task-detail.css'],
  templateUrl: './task-detail.html',
})
export class TaskDetail {
  private service = inject(RunsService);

  task = input.required<TaskView>();

  private result = signal<TaskResultView | null>(null);
  loading = signal<boolean>(false);
  resultError = signal<string>('');

  showLogs = signal<boolean>(false);
  showTranscript = signal<boolean>(false);
  transcript = signal<TxEntry[] | null>(null);
  transcriptError = signal<string>('');

  reply = signal<string>('');
  /** Typed-but-unsent credential values, by environment-variable name. Cleared once sent. */
  secrets = signal<Record<string, string>>({});
  answering = signal<boolean>(false);
  answerMsg = signal<string>('');
  answerErr = signal<string>('');

  status = computed(() => statusView(this.task().state));
  duration = computed(() => formatDuration(this.task().durationMs));
  tokens = computed(() => this.task().usage?.total ?? '—');
  summaryHtml = computed(() => this.renderMd(this.task().summary ?? ''));

  private files = computed<ResultFile[]>(() => this.result()?.files ?? []);
  artifacts = computed<FileView[]>(() =>
    this.files()
      .filter(
        (f) =>
          f.name !== 'result.json' &&
          f.name !== 'usage.json' &&
          !f.name.startsWith('logs/')
      )
      .map((f) => this.toView(f))
  );
  logs = computed<FileView[]>(() =>
    this.files()
      .filter((f) => f.name.startsWith('logs/'))
      .map((f) => this.toView(f))
  );

  private loadedId = '';

  constructor() {
    // Load the result bundle only when the task IDENTITY changes — the parent Runs list polls
    // every few seconds and hands us a new object with the SAME taskId; without this guard that
    // refresh would reset the open transcript/view state on every tick.
    effect(() => {
      const id = this.task().taskId;
      if (id === this.loadedId) {
        return;
      }
      this.loadedId = id;
      this.result.set(null);
      this.resultError.set('');
      this.loading.set(true);
      this.showTranscript.set(false);
      this.transcript.set(null);
      this.transcriptError.set('');
      this.service.result(id).subscribe({
        next: (r) => {
          if (this.task().taskId !== id) {
            return;
          }
          this.result.set(r);
          this.loading.set(false);
        },
        error: (e) => {
          if (this.task().taskId !== id) {
            return;
          }
          this.resultError.set(errorMessage(e));
          this.loading.set(false);
        },
      });
    });
  }

  resultZip(): string {
    return this.service.resultZipUrl(this.task().taskId);
  }

  workspaceZip(): string {
    return this.service.workspaceZipUrl(this.task().taskId);
  }

  sizeOf(bytes: number): string {
    return formatSize(bytes);
  }

  toggleTranscript(): void {
    const next = !this.showTranscript();
    this.showTranscript.set(next);
    if (next && this.transcript() === null && !this.transcriptError()) {
      const id = this.task().taskId;
      this.service.transcript(id).subscribe({
        next: (text) => {
          if (this.task().taskId === id) {
            this.transcript.set(this.parseTranscript(text));
          }
        },
        error: (e) => {
          const err = e as { status?: number };
          this.transcriptError.set(
            err?.status === 404
              ? 'No transcript for this task.'
              : errorMessage(e)
          );
        },
      });
    }
  }

  /** Environment variables this question asks for; [] for a plain question. */
  requestedSecrets(): string[] {
    return this.task().secretsRequested ?? [];
  }

  heldSecret(name: string): boolean {
    return (this.task().secretsHeld ?? []).includes(name);
  }

  secret(name: string): string {
    return this.secrets()[name] ?? '';
  }

  setSecret(name: string, value: string): void {
    this.secrets.update((m) => ({ ...m, [name]: value }));
  }

  /** Non-empty typed credentials — what actually gets sent. */
  private filledSecrets(): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [name, value] of Object.entries(this.secrets())) {
      if (value.trim()) {
        out[name] = value;
      }
    }
    return out;
  }

  /** A reply alone answers it, and so does a credential alone; neither does not. */
  canAnswer(): boolean {
    return (
      !!this.reply().trim() || Object.keys(this.filledSecrets()).length > 0
    );
  }

  doAnswer(): void {
    const message = this.reply().trim();
    const secrets = this.filledSecrets();
    if (!message && Object.keys(secrets).length === 0) {
      return;
    }
    this.answering.set(true);
    this.answerErr.set('');
    this.answerMsg.set('');
    this.service.answer(this.task().taskId, message, secrets).subscribe({
      next: () => {
        this.answering.set(false);
        this.answerMsg.set('Answered — the agent is continuing.');
        this.reply.set('');
        this.secrets.set({});
      },
      error: (e) => {
        this.answering.set(false);
        this.answerErr.set(errorMessage(e));
      },
    });
  }

  clip(text: string): string {
    return text.length > 2000 ? text.slice(0, 2000) + '…' : text;
  }

  roleBadge(role: string): string {
    switch (role) {
      case 'user':
        return 'bg-blue-500/15 text-blue-400';
      case 'assistant':
        return 'bg-emerald-500/15 text-emerald-400';
      case 'system':
        return 'bg-white/10 text-secondary';
      case 'result':
        return 'bg-amber-500/15 text-amber-400';
      default:
        return 'bg-white/10 text-secondary';
    }
  }

  /** Render markdown to HTML (Angular sanitises the bound result). */
  private renderMd(content: string): string {
    if (!content.trim()) {
      return '';
    }
    return marked.parse(content, { async: false }) as string;
  }

  /** Classify one result file and pre-render markdown so the template stays declarative. */
  private toView(f: ResultFile): FileView {
    if (f.binary) {
      return {
        name: f.name,
        size: f.size,
        kind: 'binary',
        html: '',
        content: '',
      };
    }
    if (f.name.toLowerCase().endsWith('.md')) {
      return {
        name: f.name,
        size: f.size,
        kind: 'md',
        html: this.renderMd(f.content),
        content: f.content,
      };
    }
    return {
      name: f.name,
      size: f.size,
      kind: 'text',
      html: '',
      content: f.content,
    };
  }

  /** Best-effort readable parse of a Claude-Code / Qwen-Code NDJSON transcript. */
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
        out.push({ role: 'raw', text: trimmed, tools: [] });
        continue;
      }
      const msg = (obj['message'] ?? {}) as Record<string, unknown>;
      const role = String(obj['role'] ?? msg['role'] ?? obj['type'] ?? 'event');
      const tools: string[] = [];
      let body = '';
      // Two transcript dialects. claude-code writes message.content[] with a `type` discriminator;
      // qwen-code writes message.parts[] with no type at all — a part is text, a functionCall or a
      // functionResponse depending on which key it has. Reading only `content` left every qwen row
      // rendered with its role and an empty body, which looked like a transcript that had not been
      // captured rather than one that had not been parsed.
      const content =
        msg['content'] ?? obj['content'] ?? msg['parts'] ?? obj['parts'];
      if (typeof content === 'string') {
        body = content;
      } else if (Array.isArray(content)) {
        for (const raw of content) {
          const b = raw as Record<string, unknown>;
          if (typeof raw === 'string') {
            body += raw;
          } else if (b['type'] === 'text') {
            body += String(b['text'] ?? '');
          } else if (b['type'] === 'thinking') {
            body += String(b['thinking'] ?? '');
          } else if (b['type'] === 'tool_use') {
            tools.push(String(b['name'] ?? 'tool'));
          } else if (b['type'] === 'tool_result') {
            const r = b['content'];
            if (typeof r === 'string') {
              body += r;
            } else if (Array.isArray(r)) {
              body += r
                .map((x) =>
                  String((x as Record<string, unknown>)?.['text'] ?? '')
                )
                .join('');
            }
          } else if (b['functionCall']) {
            const call = b['functionCall'] as Record<string, unknown>;
            tools.push(String(call?.['name'] ?? 'tool'));
          } else if (b['functionResponse']) {
            const response = b['functionResponse'] as Record<string, unknown>;
            const output = (response?.['response'] ?? {}) as Record<
              string,
              unknown
            >;
            body += String(output['output'] ?? output['error'] ?? '');
          } else if (typeof b['text'] === 'string') {
            // qwen text part; `thought` marks reasoning rather than an answer.
            body += b['thought'] ? '' : String(b['text']);
          }
        }
      }
      if (!body && typeof obj['text'] === 'string') {
        body = obj['text'] as string;
      }
      if (!body && typeof obj['summary'] === 'string') {
        body = obj['summary'] as string;
      }
      out.push({ role, text: body.trim(), tools });
    }
    return out;
  }
}
