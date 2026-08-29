import { Component, inject, signal } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { apiError } from '../../core/api-error';
import { AgentProfileView, AgentProfilesService } from '../../core/api/agent-profiles.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';
import { Confirm } from '../../shared/confirm';

/** The two agent runtimes a profile can be driven by, with the dialect each one speaks. */
const TOOL_LABELS: Record<string, string> = {
  'claude-code': 'Claude Code — Anthropic API',
  'qwen-code': 'Qwen Code — OpenAI-compatible API',
};

/**
 * Agent Profiles — a named model backend + the agent tool that drives it (claude-code =
 * Anthropic dialect, qwen-code = OpenAI-chat dialect). A template picks a profile by name;
 * with exactly one profile it is the default for every template. Keys are write-only.
 */
@Component({
  selector: 'app-agent-profiles',
  imports: [Icon, HlmButton, HlmInput, HlmLabel, HlmSelectImports],
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
  readonly hasKey = signal(false);
  readonly busy = signal(false);

  /** The select stores the raw tool id; the trigger shows the human label via itemToString. */
  readonly toolLabel = (tool: string): string => TOOL_LABELS[tool] ?? tool;

  constructor() {
    this.reload();
  }

  reload(): void {
    this.service.list().subscribe({
      next: (list) => this.profiles.set(list),
      error: (e) => this.toasts.error(apiError(e, 'Could not load agent profiles')),
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
  }

  setTool(value: unknown): void {
    // brn-select can emit null on deselect; the tool is never optional, so fall back.
    this.tool.set(typeof value === 'string' && value ? value : 'claude-code');
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
