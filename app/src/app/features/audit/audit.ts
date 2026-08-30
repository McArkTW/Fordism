import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTableImports } from '@spartan-ng/helm/table';
import { apiError } from '../../core/api-error';
import { AuditEntry, AuditService } from '../../core/api/audit.service';
import { Icon } from '../../core/icon';

/**
 * The audit trail — who did what, newest first. Read-only, admins only. It is the one place a
 * break-in leaves a mark, so a failed read shows the error rather than an empty "nothing happened".
 */
@Component({
  selector: 'app-audit',
  imports: [DatePipe, Icon, HlmSpinner, HlmTableImports],
  templateUrl: './audit.html',
})
export class Audit {
  private service = inject(AuditService);

  readonly entries = signal<AuditEntry[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal('');

  constructor() {
    this.service.recent().subscribe({
      next: (list) => {
        this.entries.set(list);
        this.loading.set(false);
      },
      error: (e) => {
        this.loadError.set(apiError(e, 'Could not load the audit log'));
        this.loading.set(false);
      },
    });
  }
}
