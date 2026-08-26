import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { apiError } from '../../core/api-error';
import { TemplateSummary, TemplatesService } from '../../core/api/templates.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';

/** The templates there are. Opening one is a navigation, so an edit in progress cannot be lost. */
@Component({
  selector: 'app-template-list',
  imports: [RouterLink, Icon, MatButtonModule],
  template: `
    <div class="mx-auto max-w-4xl">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="page-h">Agent Templates</h1>
          <p class="mt-1 text-sm text-muted">
            Reusable presets a workflow step runs with. Each one picks a profile, skills,
            credentials and standing instructions.
          </p>
        </div>
        <a matButton="filled" routerLink="/templates/new"><app-icon name="plus" />New template</a>
      </div>

      @if (loading()) {
        <div class="card mt-6 flex items-center justify-center gap-2 p-10 text-sm text-muted">
          <app-icon name="loader" class="spin" />Loading…
        </div>
      } @else if (templates().length === 0) {
        <div class="card mt-6 flex flex-col items-center px-6 py-16 text-center">
          <app-icon name="layers" class="text-3xl text-muted" />
          <div class="mt-3 text-sm font-medium">No templates yet.</div>
          <p class="mt-1 max-w-md text-sm text-muted">
            A template bundles an agent profile, the skills the agent may use, the credentials it
            receives and standing instructions. Workflow steps run with a template.
          </p>
          <a matButton="filled" routerLink="/templates/new" class="mt-5">
            <app-icon name="plus" />Create the first template
          </a>
        </div>
      } @else {
        <div class="card mt-6 overflow-hidden">
          <table class="w-full">
            <thead>
              <tr>
                <th class="th">Name</th>
              </tr>
            </thead>
            <tbody>
              @for (t of templates(); track t.id) {
                <tr>
                  <td class="td p-0">
                    <a
                      [routerLink]="['/templates', t.id]"
                      class="flex items-center justify-between gap-3 px-3 py-2.5 hover:bg-ink/5"
                    >
                      <span class="text-sm font-medium">{{ t.name }}</span>
                      <app-icon name="chevron-right" class="text-muted" />
                    </a>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
})
export class TemplateList {
  private service = inject(TemplatesService);
  private toasts = inject(Toasts);

  readonly templates = signal<TemplateSummary[]>([]);
  readonly loading = signal(true);

  constructor() {
    this.service.list().subscribe({
      next: (list) => {
        this.templates.set(list);
        this.loading.set(false);
      },
      error: (e) => {
        this.loading.set(false);
        this.toasts.error(apiError(e, 'Could not load templates'));
      },
    });
  }
}
