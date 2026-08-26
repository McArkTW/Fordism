import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type SkillSummary = {
  name: string;
  description: string;
  enabled: boolean;
};
export type SkillDetail = {
  name: string;
  description: string;
  content: string;
  exists: boolean;
  enabled: boolean;
  files: string[];
};

/** The mirror's source: repo + pinned tag. */
export type SkillSource = { repo?: string; tag?: string; syncedAt?: string };

/** Skills library (read-only mirror of an external skills repo): list / preview / enable-disable. */
@Injectable({ providedIn: 'root' })
export class SkillsService {
  private http = inject(HttpClient);

  list(): Observable<SkillSummary[]> {
    return this.http.get<SkillSummary[]>('/api/skills');
  }

  get(name: string): Observable<SkillDetail> {
    return this.http.get<SkillDetail>(`/api/skills/${name}`);
  }

  source(): Observable<SkillSource> {
    return this.http.get<SkillSource>('/api/skills-source');
  }

  setEnabled(name: string, enabled: boolean): Observable<{ name: string }> {
    return this.http.post<{ name: string }>('/api/skills-state', { name, enabled });
  }
}
