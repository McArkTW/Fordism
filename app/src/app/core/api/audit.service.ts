import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** One audited action, as core recorded it. `allowed` is whether the gate let the write through. */
export type AuditEntry = {
  at: number;
  actor: string;
  actorEmail: string;
  ip: string;
  action: string;
  allowed: boolean;
};

/** /api/audit — the trail of who did what. Admins only (audit.read). */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private http = inject(HttpClient);

  recent(): Observable<AuditEntry[]> {
    return this.http.get<AuditEntry[]>('/api/audit');
  }
}
