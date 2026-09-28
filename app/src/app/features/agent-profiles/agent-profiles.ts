import { Component, computed, inject, signal } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { apiError } from '../../core/api-error';
import { AgentProfileView, AgentProfilesService } from '../../core/api/agent-profiles.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';

/** The agent runtimes a profile can be driven by, with the dialect each one speaks. */
const TOOL_LABELS: Record<string, string> = {
  'claude-code': 'Claude Code — Anthropic API',
  'qwen-code': 'Qwen Code — OpenAI-compatible',
  'gemini-cli': 'Gemini CLI — Google API',
  codex: 'Codex — OpenAI Responses',
  opencode: 'opencode',
  aider: 'aider',
  goose: 'goose',
  copilot: 'GitHub Copilot CLI',
  pi: 'pi',
  crush: 'crush',
  cline: 'cline',
  continue: 'Continue',
  openhands: 'OpenHands',
  dsh: 'dsh',
  openclaw: 'openclaw',
  hermes: 'hermes',
  deepagents: 'deepagents',
  kimi: 'kimi',
  codewhale: 'codewhale',
  reasonix: 'reasonix',
  jcode: 'jcode',
  grok: 'grok',
};

/** The five tools that keep a session a later container can resume; the rest are one-shot. */
const SESSION_TOOLS = new Set(['claude-code', 'qwen-code', 'gemini-cli', 'codex', 'opencode']);

/**
 * The wire formats each tool can speak, primary first — mirrors AgentTool's declaration in core,
 * which is what actually enforces it. Kept here only so the form can offer the choice; a profile
 * naming a format its tool cannot speak is refused by the API either way.
 */
const TOOL_FORMATS: Record<string, string[]> = {
  'claude-code': ['anthropic'],
  'qwen-code': ['openai-chat'],
  'gemini-cli': ['gemini'],
  codex: ['openai-responses', 'openai-chat'],
  opencode: ['openai-chat', 'anthropic'],
  aider: ['openai-chat', 'anthropic'],
  goose: ['openai-chat', 'anthropic'],
  copilot: ['openai-chat'],
  pi: ['openai-chat', 'anthropic'],
  crush: ['openai-chat', 'anthropic'],
  cline: ['openai-chat', 'anthropic'],
  continue: ['openai-chat', 'anthropic'],
  openhands: ['openai-chat', 'anthropic'],
  dsh: ['openai-chat', 'anthropic'],
  openclaw: ['openai-chat', 'anthropic'],
  hermes: ['openai-chat', 'anthropic'],
  deepagents: ['openai-chat', 'anthropic'],
  kimi: ['openai-chat', 'anthropic'],
  codewhale: ['openai-chat', 'anthropic'],
  reasonix: ['openai-chat', 'anthropic'],
  jcode: ['openai-chat', 'anthropic'],
  grok: ['openai-chat', 'anthropic'],
};

const FORMAT_LABELS: Record<string, string> = {
  anthropic: 'Anthropic Messages',
  'openai-chat': 'OpenAI Chat Completions',
  'openai-responses': 'OpenAI Responses',
  gemini: 'Gemini',
};

/**
 * Agent Profiles — a named model backend + the agent tool that drives it (claude-code =
 * Anthropic dialect, qwen-code = OpenAI-chat dialect). A template picks a profile by name;
 * with exactly one profile it is the default for every template. Keys are write-only.
 */
@Component({
  selector: 'app-agent-profiles',
  imports: [Icon, HlmButton, HlmInput, HlmLabel, HlmSelectImports, HlmSpinner],
  templateUrl: './agent-profiles.html',
})
export class AgentProfiles {
  private service = inject(AgentProfilesService);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);

  readonly profiles = signal<AgentProfileView[]>([]);
  readonly selected = signal<string | null>(null);
  readonly name = signal('');
  readonly baseUrl = signal('');
  readonly apiKey = signal('');
  readonly model = signal('');
  readonly tool = signal('claude-code');
  readonly format = signal('anthropic');
  readonly hasKey = signal(false);
  readonly busy = signal(false);
  /** The first list request is still out — an empty list means nothing yet. */
  readonly loading = signal(true);
  /** Why the list could not be read. Rendered instead of the "none yet" empty state. */
  readonly loadError = signal('');

  /** The select stores the raw tool id; the trigger shows the human label via itemToString. */
  readonly toolLabel = (tool: string): string => TOOL_LABELS[tool] ?? tool;
  readonly formatLabel = (format: string): string => FORMAT_LABELS[format] ?? format;

  /**
   * The formats the chosen tool can speak. The form only offers the choice when there is one to
   * make — for four of the five tools the endpoint shape follows from the tool, and a select with
   * a single option is a question with one answer.
   */
  readonly formatChoices = computed(() => TOOL_FORMATS[this.tool()] ?? []);
  /** Tool ids in the order the select offers them. */
  readonly toolChoices = Object.keys(TOOL_LABELS);
  /** Whether the chosen tool can be resumed — what human-in-the-loop and rework depend on. */
  readonly toolResumes = computed(() => SESSION_TOOLS.has(this.tool()));
  readonly formatIsAChoice = computed(() => this.formatChoices().length > 1);

  constructor() {
    this.reload();
  }

  /**
   * A failed list used to leave the column empty, which reads as "no agent profiles yet" — and
   * that reading is what makes someone create a second profile that already exists. Show the
   * failure in its place.
   */
  reload(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: (list) => {
        this.profiles.set(list);
        this.loadError.set('');
        this.loading.set(false);
      },
      error: (e) => {
        this.loadError.set(apiError(e, 'Could not load agent profiles'));
        this.loading.set(false);
      },
    });
  }

  select(id: string): void {
    this.service.get(id).subscribe({
      next: (p) => {
        this.selected.set(p.id);
        this.name.set(p.name);
        this.baseUrl.set(p.baseUrl ?? '');
        // The stored key is never returned; blank means "keep it" on save.
        this.apiKey.set('');
        this.hasKey.set(!!p.hasKey);
        this.model.set(p.model ?? '');
        this.tool.set(p.tool || 'claude-code');
        this.format.set(p.format || TOOL_FORMATS[p.tool || 'claude-code']?.[0] || 'anthropic');
      },
      error: (e) => this.toasts.error(apiError(e, 'Could not load the profile')),
    });
  }

  startNew(): void {
    this.selected.set(null);
    this.name.set('');
    this.baseUrl.set('');
    this.apiKey.set('');
    this.hasKey.set(false);
    this.model.set('');
    this.tool.set('claude-code');
    this.format.set('anthropic');
  }

  setTool(value: unknown): void {
    // brn-select can emit null on deselect; the tool is never optional, so fall back.
    const tool = typeof value === 'string' && value ? value : 'claude-code';
    this.tool.set(tool);
    // The format belonged to the old tool and the new one may not speak it, which the API would
    // refuse. Reset to the new tool's primary rather than sending a pairing that cannot save.
    this.format.set(TOOL_FORMATS[tool]?.[0] ?? 'anthropic');
  }

  setFormat(value: unknown): void {
    this.format.set(
      typeof value === 'string' && value ? value : (TOOL_FORMATS[this.tool()]?.[0] ?? 'anthropic'),
    );
  }

  save(): void {
    if (!this.name().trim()) {
      this.toasts.error('Name the profile first.');
      return;
    }
    this.busy.set(true);
    const payload = {
      name: this.name(),
      baseUrl: this.baseUrl(),
      apiKey: this.apiKey(),
      model: this.model(),
      tool: this.tool(),
      format: this.format(),
    };
    const id = this.selected();
    const request = id ? this.service.update(id, payload) : this.service.create(payload);
    request.subscribe({
      next: (r) => {
        this.busy.set(false);
        this.selected.set(r.id);
        // The key just sent (if any) is now stored and will never be shown again.
        this.apiKey.set('');
        this.hasKey.set(this.hasKey() || !!payload.apiKey);
        this.toasts.ok(`Saved “${this.name()}”`);
        this.reload();
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'Could not save the profile'));
      },
    });
  }

  async remove(): Promise<void> {
    const id = this.selected();
    if (!id) {
      return;
    }
    const ok = await this.confirm.ask(
      'Delete profile',
      `Delete “${this.name()}”? Templates referencing it lose their backend.`,
      'Delete',
      true,
    );
    if (!ok) {
      return;
    }
    const label = this.name();
    this.busy.set(true);
    this.service.remove(id).subscribe({
      next: () => {
        this.busy.set(false);
        this.toasts.ok(`Deleted “${label}”`);
        this.startNew();
        this.reload();
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(apiError(e, 'Could not delete the profile'));
      },
    });
  }
}
