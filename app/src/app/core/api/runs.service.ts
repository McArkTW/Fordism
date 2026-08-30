import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export type RunSummary = {
  id: string;
  workflow: string;
  state: string;
  createdAt: number;
  durationMs: number;
  /** Set only on a run a reconciler spawned; core omits the field entirely for every other run. */
  parentRunId?: string;
};

/** What History asks for. Every field is optional; omitting all of them is "everything, newest first". */
export type RunFilter = {
  workflow?: string;
  /** Repeatable — Live asks for ACTIVE and ASKED together. */
  state?: string[];
  /** Epoch millis; runs created at or after it. */
  since?: number;
  /** Free text over the run id and workflow name. */
  q?: string;
  sort?: 'newest' | 'oldest';
  /** Opaque keyset cursor from the previous page. */
  before?: string;
  limit?: number;
};

/**
 * A page of history. `nextCursor` is null on the last page.
 *
 * The body core returns is a plain array and the cursor rides in a header — paging metadata is not
 * data, and keeping the array means the response shape the app was written against never changed.
 */
export type RunPage = { runs: RunSummary[]; nextCursor: string | null };

/** Token accounting a task reports, or null when the agent produced none. */
export type TaskUsage = {
  input_tokens: number;
  output_tokens: number;
  total: number;
  turns: number;
};

export type TaskView = {
  taskId: string;
  step: number;
  state: string;
  template: string;
  session: string;
  workspace: string | null;
  summary: string | null;
  /** The agent's one-word answer, when it wrote one. */
  verdict: string | null;
  createdAt: number;
  attempt: number;
  error: string | null;
  durationMs: number | null;
  usage: TaskUsage | null;
  question: string | null; // what the agent asked when it stopped (state ASKED)
  secretsRequested: string[]; // environment variables this question wants supplied — names only
  secretsHeld: string[]; // names this run already holds from an earlier answer
};

export type RunDetail = {
  id: string;
  workflow: string;
  strategy: string;
  state: string;
  createdAt: number;
  workflowSnapshot: string | null;
  tasks: TaskView[];
  /** The run that spawned this one, if any. */
  parentRunId?: string;
  /**
   * The runs this one spawned, oldest first. A reconciler that delegates its work has tasks that
   * only ever say "I delegated this" — without these the page cannot show what actually happened.
   */
  children: RunSummary[];
};

/** One file in a task's result bundle. `content` is "" when `binary` is true. */
export type ResultFile = {
  name: string;
  size: number;
  binary: boolean;
  content: string;
};
export type TaskResultView = {
  taskId: string;
  workspace: string | null;
  files: ResultFile[];
};

/** Read-only view of runs + their tasks: /api/runs, /api/tasks/{id}/result[.zip], transcript. */
@Injectable({ providedIn: 'root' })
export class RunsService {
  private http = inject(HttpClient);

  /** One page of the history. */
  page(filter: RunFilter = {}): Observable<RunPage> {
    let params = new HttpParams();
    for (const state of filter.state ?? []) {
      params = params.append('state', state);
    }
    const scalars: Record<string, string | number | undefined> = {
      workflow: filter.workflow,
      since: filter.since,
      q: filter.q,
      sort: filter.sort,
      before: filter.before,
      limit: filter.limit,
    };
    for (const [key, value] of Object.entries(scalars)) {
      if (value !== undefined && value !== null && `${value}` !== '') {
        params = params.set(key, `${value}`);
      }
    }
    return this.http.get<RunSummary[]>('/api/runs', { params, observe: 'response' }).pipe(
      map((response) => ({
        runs: response.body ?? [],
        nextCursor: response.headers.get('X-Next-Cursor'),
      })),
    );
  }

  /** The runs that want attention — in flight, or parked waiting on a human. */
  live(): Observable<RunSummary[]> {
    return this.page({ state: ['ACTIVE', 'WAITING_ON_CHILD', 'ASKED'] }).pipe(map((p) => p.runs));
  }

  /** Every distinct workflow that has ever run, for the History filter. */
  workflowsSeen(): Observable<string[]> {
    return this.page({ limit: 200 }).pipe(
      map((page) => [...new Set(page.runs.map((run) => run.workflow))].sort()),
    );
  }

  get(id: string): Observable<RunDetail> {
    return this.http.get<RunDetail>(`/api/runs/${encodeURIComponent(id)}`);
  }

  result(taskId: string): Observable<TaskResultView> {
    return this.http.get<TaskResultView>(`/api/tasks/${encodeURIComponent(taskId)}/result`);
  }

  /** The session transcript as raw NDJSON text (404 when the task has none). */
  transcript(taskId: string): Observable<string> {
    return this.http.get(`/api/tasks/${encodeURIComponent(taskId)}/transcript`, {
      responseType: 'text',
    });
  }

  resultZipUrl(taskId: string): string {
    return `/api/tasks/${encodeURIComponent(taskId)}/result.zip`;
  }

  workspaceZipUrl(taskId: string): string {
    return `/api/tasks/${encodeURIComponent(taskId)}/workspace.zip`;
  }

  /**
   * Stop a run that is still going. The run ends immediately; core's next reconcile tick culls
   * whatever it still had running. Nothing is deleted — the workspace stays readable.
   */
  abandon(runId: string): Observable<RunSummary> {
    return this.http.post<RunSummary>(`/api/runs/${encodeURIComponent(runId)}/abandon`, {});
  }

  /**
   * Answer a task that stopped to ask: send the human's reply; the session resumes with it.
   *
   * Secrets go in their own field, never folded into the message — the message becomes the agent's
   * resume prompt and is written to the session transcript on disk, whereas these are held in core's
   * memory and injected as environment variables into this run's containers.
   */
  answer(
    taskId: string,
    message: string,
    secrets?: Record<string, string>,
  ): Observable<{ taskId: string; state: string }> {
    const body: { message: string; secrets?: Record<string, string> } = { message };
    if (secrets && Object.keys(secrets).length > 0) {
      body.secrets = secrets;
    }
    return this.http.post<{ taskId: string; state: string }>(
      `/api/tasks/${encodeURIComponent(taskId)}/answer`,
      body,
    );
  }
}
