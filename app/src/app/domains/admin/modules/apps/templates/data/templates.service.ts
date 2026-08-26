import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A template summary in the list — id is identity, name is a mutable label. */
export type TemplateSummary = { id: string; name: string };

export type TemplateDetail = {
  id: string;
  name: string;
  agentProfile: string;
  model: string;
  skills: string[];
  credentials: string[];
  instructions: string;
  exists: boolean;
};

export type TemplateSave = {
  name: string;
  agentProfile: string;
  model: string;
  skills: string[];
  credentials: string[];
  instructions: string;
};

/** An agent profile (name + its single model) — for the profile dropdown. */
export type AgentProfileOption = {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  hasKey: boolean;
};

export type SkillSummary = {
  name: string;
  description: string;
  enabled: boolean;
};

/** A stored credential as the picker sees it — key and note only, never a value. */
export type CredentialOption = { key: string; note: string; hasValue: boolean };

/** Agent-template CRUD (id-keyed) + the profile + skill option lists the form draws on. */
@Injectable({ providedIn: 'root' })
export class TemplatesService {
  private http = inject(HttpClient);

  list(): Observable<TemplateSummary[]> {
    return this.http.get<TemplateSummary[]>('/api/templates');
  }

  get(id: string): Observable<TemplateDetail> {
    return this.http.get<TemplateDetail>(
      `/api/templates/${encodeURIComponent(id)}`
    );
  }

  create(template: TemplateSave): Observable<{ id: string }> {
    return this.http.post<{ id: string }>('/api/templates', template);
  }

  update(id: string, template: TemplateSave): Observable<{ id: string }> {
    return this.http.put<{ id: string }>(
      `/api/templates/${encodeURIComponent(id)}`,
      template
    );
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`/api/templates/${encodeURIComponent(id)}`);
  }

  sources(): Observable<AgentProfileOption[]> {
    return this.http.get<AgentProfileOption[]>('/api/agent-profiles');
  }

  skills(): Observable<SkillSummary[]> {
    return this.http.get<SkillSummary[]>('/api/skills');
  }

  credentials(): Observable<CredentialOption[]> {
    return this.http.get<CredentialOption[]>('/api/credentials');
  }
}
