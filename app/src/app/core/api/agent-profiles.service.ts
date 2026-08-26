import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** An agent profile as the browser sees it — id is identity, name is a mutable label; key never returned. */
export type AgentProfileView = {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  tool: string; // claude-code | qwen-code — the agent runtime that drives this profile
  hasKey: boolean;
  exists?: boolean;
};

export type AgentProfileSave = {
  name: string;
  baseUrl: string;
  apiKey: string; // blank on edit = keep the stored key
  model: string;
  tool: string;
};

/** Agent-profiles CRUD, id-keyed (rename in place; keys write-only). */
@Injectable({ providedIn: 'root' })
export class AgentProfilesService {
  private http = inject(HttpClient);

  list(): Observable<AgentProfileView[]> {
    return this.http.get<AgentProfileView[]>('/api/agent-profiles');
  }

  get(id: string): Observable<AgentProfileView> {
    return this.http.get<AgentProfileView>(`/api/agent-profiles/${encodeURIComponent(id)}`);
  }

  create(profile: AgentProfileSave): Observable<{ id: string }> {
    return this.http.post<{ id: string }>('/api/agent-profiles', profile);
  }

  update(id: string, profile: AgentProfileSave): Observable<{ id: string }> {
    return this.http.put<{ id: string }>(`/api/agent-profiles/${encodeURIComponent(id)}`, profile);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`/api/agent-profiles/${encodeURIComponent(id)}`);
  }
}
