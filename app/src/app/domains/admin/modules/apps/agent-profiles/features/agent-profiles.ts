import { Component, inject, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatOption } from '@angular/material/core';
import { MatFormField } from '@angular/material/form-field';
import { MatIcon } from '@angular/material/icon';
import { MatInput } from '@angular/material/input';
import { MatSelect } from '@angular/material/select';
import { errorMessage } from '@/app/core/api-error';
import {
  AgentProfileView,
  AgentProfilesService,
} from '../data/agent-profiles.service';

/**
 * Agent Profiles — a named backend + the agent tool that drives it. Each profile is base URL +
 * model + API key + tool (claude-code = Anthropic dialect, qwen-code = OpenAI-chat dialect); a
 * template picks one profile. Keys are write-only (never shown back).
 */
@Component({
  selector: 'agent-profiles',
  imports: [MatButton, MatIcon, MatFormField, MatInput, MatSelect, MatOption],
  host: { class: 'block' },
  templateUrl: './agent-profiles.html',
})
export default class AgentProfiles {
  private service = inject(AgentProfilesService);

  sources = signal<AgentProfileView[]>([]);
  selected = signal<string | null>(null);
  name = signal<string>('');
  baseUrl = signal<string>('');
  apiKey = signal<string>('');
  model = signal<string>('');
  tool = signal<string>('claude-code');
  hasKey = signal<boolean>(false);
  error = signal<string>('');
  message = signal<string>('');
  busy = signal<boolean>(false);

  constructor() {
    this.reload();
  }

  reload(): void {
    this.service.list().subscribe({
      next: (list) => this.sources.set(list),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  select(id: string): void {
    this.clear();
    this.service.get(id).subscribe({
      next: (s) => {
        this.selected.set(s.id);
        this.name.set(s.name);
        this.baseUrl.set(s.baseUrl ?? '');
        this.apiKey.set('');
        this.hasKey.set(!!s.hasKey);
        this.model.set(s.model ?? '');
        this.tool.set(s.tool || 'claude-code');
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  newSource(): void {
    this.clear();
    this.selected.set(null);
    this.name.set('');
    this.baseUrl.set('');
    this.apiKey.set('');
    this.hasKey.set(false);
    this.model.set('');
    this.tool.set('claude-code');
  }

  save(): void {
    this.clear();
    this.busy.set(true);
    const payload = {
      name: this.name(),
      baseUrl: this.baseUrl(),
      apiKey: this.apiKey(),
      model: this.model(),
      tool: this.tool(),
    };
    const id = this.selected();
    const request = id
      ? this.service.update(id, payload)
      : this.service.create(payload);
    request.subscribe({
      next: (r) => {
        this.busy.set(false);
        this.message.set('Saved “' + this.name() + '”');
        this.selected.set(r.id);
        this.reload();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  remove(): void {
    const target = this.selected();
    if (!target) {
      return;
    }
    const label = this.name();
    this.clear();
    this.busy.set(true);
    this.service.remove(target).subscribe({
      next: () => {
        this.busy.set(false);
        this.message.set('Deleted “' + label + '”');
        this.newSource();
        this.reload();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  private clear(): void {
    this.error.set('');
    this.message.set('');
  }
}
