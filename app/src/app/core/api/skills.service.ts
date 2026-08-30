import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type SkillSummary = {
  name: string;
  description: string;
  enabled: boolean;
  /** The plugin whose folder this skill sits in. Absent when the user wrote it themselves. */
  owner?: string;
  fileCount: number;
  /** Newest mtime in the folder, ISO-8601. Blank when the folder could not be read. */
  updatedAt: string;
};
export type SkillDetail = {
  name: string;
  description: string;
  content: string;
  exists: boolean;
  enabled: boolean;
  files: string[];
  owner?: string;
  fileCount: number;
  updatedAt: string;
};

/**
 * One file inside a skill. `binary` and `truncated` are normal answers, not errors: a skill folder
 * may hold a PNG or a large fixture, and the page asked what is in it.
 */
export type SkillFile = {
  path: string;
  size: number;
  binary: boolean;
  truncated: boolean;
  content: string;
};

/** What a bulk delete actually did — 200 even when some names failed. */
export type BulkDelete = {
  deleted: string[];
  failed: { name: string; error: string }[];
};

/** The mirror's source: repo + pinned tag. */
export type SkillSource = { repo?: string; tag?: string; syncedAt?: string };

/** A skills repo the library mirrors. `lastError` is blank when the last sync worked. */
export type SkillPlugin = {
  id: string;
  name: string;
  url: string;
  ref: string;
  lastSyncedAt: string;
  lastError: string;
};

/** The skills library: browse, edit, and the plugins it mirrors. */
@Injectable({ providedIn: 'root' })
export class SkillsService {
  private http = inject(HttpClient);

  list(): Observable<SkillSummary[]> {
    return this.http.get<SkillSummary[]>('/api/skills');
  }

  get(name: string): Observable<SkillDetail> {
    return this.http.get<SkillDetail>(`/api/skills/${encodeURIComponent(name)}`);
  }

  source(): Observable<SkillSource> {
    return this.http.get<SkillSource>('/api/skills-source');
  }

  setEnabled(name: string, enabled: boolean): Observable<{ name: string }> {
    return this.http.post<{ name: string }>('/api/skills-state', { name, enabled });
  }

  save(name: string, content: string): Observable<{ name: string }> {
    return this.http.post<{ name: string }>('/api/skills', { name, content });
  }

  remove(name: string): Observable<void> {
    return this.http.delete<void>(`/api/skills/${encodeURIComponent(name)}`);
  }

  /**
   * Delete a selection in one request. One DELETE per name would answer twenty times with no way
   * to say which half failed; this answers once with the breakdown.
   */
  removeMany(names: string[]): Observable<BulkDelete> {
    return this.http.post<BulkDelete>('/api/skills-delete', { names });
  }

  /** One file's content. Its own endpoint because `/api/skills/<name>` matches slashes. */
  file(name: string, path: string): Observable<SkillFile> {
    const query = `name=${encodeURIComponent(name)}&path=${encodeURIComponent(path)}`;
    return this.http.get<SkillFile>(`/api/skills-file?${query}`);
  }

  /**
   * Upload a picked folder. A multipart part carries only a file's base name, so each file's
   * path relative to the folder rides alongside in the same order — that is what preserves
   * `scripts/run.sh` instead of flattening it to `run.sh`.
   */
  upload(name: string, files: File[]): Observable<{ name: string }> {
    const form = new FormData();
    const paths = files.map((f) => relativePath(f));
    form.append('paths', JSON.stringify(paths));
    for (const file of files) {
      form.append('files', file, file.name);
    }
    return this.http.post<{ name: string }>(
      `/api/skills/upload?name=${encodeURIComponent(name)}`,
      form,
    );
  }

  plugins(): Observable<SkillPlugin[]> {
    return this.http.get<SkillPlugin[]>('/api/skill-plugins');
  }

  addPlugin(url: string, ref: string): Observable<SkillPlugin> {
    return this.http.post<SkillPlugin>('/api/skill-plugins', { url, ref });
  }

  syncPlugin(id: string): Observable<SkillPlugin> {
    return this.http.post<SkillPlugin>(`/api/skill-plugins/${encodeURIComponent(id)}/sync`, {});
  }

  /**
   * Remove a plugin. `keepSkills` leaves its folder in place, so its skills become the user's own
   * — and the same plugin can then no longer be re-added over them.
   */
  removePlugin(id: string, keepSkills = false): Observable<void> {
    const query = keepSkills ? '?keepSkills=true' : '';
    return this.http.delete<void>(`/api/skill-plugins/${encodeURIComponent(id)}${query}`);
  }
}

/**
 * The file's path inside the picked folder, with the folder's own name dropped — the user names
 * the skill, so `my-skill/SKILL.md` must arrive as `SKILL.md`.
 */
function relativePath(file: File): string {
  const full = (file as File & { webkitRelativePath?: string }).webkitRelativePath ?? '';
  if (!full) {
    return file.name;
  }
  const cut = full.indexOf('/');
  return cut < 0 ? full : full.slice(cut + 1);
}
