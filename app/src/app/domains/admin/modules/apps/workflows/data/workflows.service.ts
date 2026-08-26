import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Preflight,
  RunStarted,
  RunSummary,
  StagedFile,
  WorkflowDetail,
  WorkflowParsed,
  WorkflowSummary,
} from './model';

/** CRUD, validation, pre-flight and run against fordism-core's /api/workflows. */
@Injectable({ providedIn: 'root' })
export class WorkflowsService {
  private http = inject(HttpClient);

  list(): Observable<WorkflowSummary[]> {
    return this.http.get<WorkflowSummary[]>('/api/workflows');
  }

  get(name: string): Observable<WorkflowDetail> {
    return this.http.get<WorkflowDetail>(
      `/api/workflows/${encodeURIComponent(name)}`
    );
  }

  /**
   * Create or update — the body is the raw YAML; the name comes from inside it.
   *
   * `editing` is the name being edited. Core refuses a changed name, because a workflow is keyed by
   * it and renaming would write a second one; `saveAs` is how you say you meant it.
   */
  save(yaml: string, editing = '', saveAs = false): Observable<WorkflowParsed> {
    const query = saveAs ? '?saveAs=true' : '';
    const url = editing
      ? `/api/workflows/${encodeURIComponent(editing)}${query}`
      : `/api/workflows${query}`;
    const body = { headers: { 'Content-Type': 'text/plain' } };
    return editing
      ? this.http.put<WorkflowParsed>(url, yaml, body)
      : this.http.post<WorkflowParsed>(url, yaml, body);
  }

  /** Parse without storing — the outline and the error line come from core, not a second parser. */
  validate(yaml: string): Observable<WorkflowParsed> {
    return this.http.post<WorkflowParsed>('/api/workflows/validate', yaml, {
      headers: { 'Content-Type': 'text/plain' },
    });
  }

  preflight(name: string): Observable<Preflight> {
    return this.http.get<Preflight>(
      `/api/workflows/${encodeURIComponent(name)}/preflight`
    );
  }

  remove(name: string): Observable<void> {
    return this.http.delete<void>(`/api/workflows/${encodeURIComponent(name)}`);
  }

  /** This workflow's runs, newest first. */
  runs(name: string): Observable<RunSummary[]> {
    return this.http.get<RunSummary[]>(
      `/api/runs?workflow=${encodeURIComponent(name)}`
    );
  }

  /**
   * Start a run. Parameters go as form fields — core reads query and form only, so a JSON body
   * would be accepted and silently ignored. Uploaded files are zipped here and land in `task/`.
   */
  run(
    name: string,
    params: Record<string, string>,
    files: StagedFile[]
  ): Observable<RunStarted> {
    const url = `/api/workflows/${encodeURIComponent(name)}/run`;
    const form = new FormData();
    for (const [key, value] of Object.entries(params)) {
      form.append(key, value);
    }
    if (files.length === 0) {
      return this.http.post<RunStarted>(url, form);
    }
    return new Observable<RunStarted>((subscriber) => {
      zip(files)
        .then((blob) => {
          form.append('taskZip', blob, 'task.zip');
          this.http.post<RunStarted>(url, form).subscribe({
            next: (r) => {
              subscriber.next(r);
              subscriber.complete();
            },
            error: (e) => subscriber.error(e),
          });
        })
        .catch((e) => subscriber.error(e));
    });
  }
}

/**
 * A minimal store-only (uncompressed) zip.
 *
 * Hand-rolled because the alternative is a dependency for one call — and stored entries need no
 * compressor, only a CRC. Core unzips with java.util.zip, which reads stored entries natively.
 */
async function zip(files: StagedFile[]): Promise<Blob> {
  const encoder = new TextEncoder();
  const chunks: ArrayBuffer[] = [];
  const central: ArrayBuffer[] = [];
  let offset = 0;

  for (const staged of files) {
    const nameBytes = encoder.encode(staged.name);
    const data = new Uint8Array(await staged.file.arrayBuffer());
    const crc = crc32(data);

    const local = new Uint8Array(30 + nameBytes.length);
    const localView = new DataView(local.buffer);
    localView.setUint32(0, 0x04034b50, true);
    localView.setUint16(4, 20, true); // version needed
    localView.setUint16(8, 0, true); // stored, no compression
    localView.setUint32(14, crc, true);
    localView.setUint32(18, data.length, true);
    localView.setUint32(22, data.length, true);
    localView.setUint16(26, nameBytes.length, true);
    local.set(nameBytes, 30);
    chunks.push(toBuffer(local), toBuffer(data));

    const entry = new Uint8Array(46 + nameBytes.length);
    const entryView = new DataView(entry.buffer);
    entryView.setUint32(0, 0x02014b50, true);
    entryView.setUint16(4, 20, true);
    entryView.setUint16(6, 20, true);
    entryView.setUint16(10, 0, true);
    entryView.setUint32(16, crc, true);
    entryView.setUint32(20, data.length, true);
    entryView.setUint32(24, data.length, true);
    entryView.setUint16(28, nameBytes.length, true);
    entryView.setUint32(42, offset, true);
    entry.set(nameBytes, 46);
    central.push(toBuffer(entry));

    offset += local.length + data.length;
  }

  const centralSize = central.reduce((sum, entry) => sum + entry.byteLength, 0);
  const end = new Uint8Array(22);
  const endView = new DataView(end.buffer);
  endView.setUint32(0, 0x06054b50, true);
  endView.setUint16(8, files.length, true);
  endView.setUint16(10, files.length, true);
  endView.setUint32(12, centralSize, true);
  endView.setUint32(16, offset, true);

  return new Blob([...chunks, ...central, toBuffer(end)], {
    type: 'application/zip',
  });
}

/** Blob rejects a Uint8Array view type in strict TS; hand it the bytes as a buffer. */
function toBuffer(view: Uint8Array): ArrayBuffer {
  return view.buffer.slice(
    view.byteOffset,
    view.byteOffset + view.byteLength
  ) as ArrayBuffer;
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c >>> 0;
  }
  return table;
})();

function crc32(data: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
