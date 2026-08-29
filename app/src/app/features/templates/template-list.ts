import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { apiError } from '../../core/api-error';
import { TemplateSummary, TemplatesService } from '../../core/api/templates.service';
import { Icon } from '../../core/icon';
import { Toasts } from '../../core/toast';

/** The templates there are. Opening one is a navigation, so an edit in progress cannot be lost. */
@Component({
  selector: 'app-template-list',
  imports: [RouterLink, Icon, HlmButton],
  template: `
    <div class="mx-auto flex max-w-4xl flex-col gap-6">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="page-h">Agent Templates</h1>
          <p class="text-muted-foreground text-sm">
            Reusable presets a workflow step runs with. Each one picks a profile, skills,
            credentials and standing instructions.
          </p>
        </div>
        <!-- The one filled button on this view: creating a template is the primary action. -->
        <a hlmBtn routerLink="/templates/new"><app-icon name="plus" />New template</a>
      </div>

      @if (loading()) {
        <div
          class="border-border bg-card text-muted-foreground flex items-center justify-center gap-2 rounded-xl border p-10 text-sm"
        >
          <app-icon name="loader" class="spin" />Loading…
        </div>
      } @else if (templates().length === 0) {
        <div
          class="border-border bg-card flex flex-col items-center rounded-xl border px-6 py-16 text-center"
        >
          <app-icon name="layers" class="text-muted-foreground text-3xl" />
          <div class="mt-3 text-sm font-medium">No templates yet.</div>
          <p class="text-muted-foreground mt-1 max-w-md text-sm">
            A template bundles an agent profile, the skills the agent may use, the credentials it
            receives and standing instructions. Workflow steps run with a template.
          </p>
          <a hlmBtn variant="outline" routerLink="/templates/new" class="mt-5">
            <app-icon name="plus" />Create the first template
          </a>
        </div>
      } @else {
        <div class="border-border bg-card overflow-x-auto rounded-xl border">
          <table class="w-full">
            <thead>
              <tr>
                <th class="text-muted-foreground px-3 py-2 text-left text-xs font-semibold tracking-wide uppercase">
                  Name
                </th>
              </tr>
            </thead>
            <tbody>
              @for (t of templates(); track t.id) {
                <tr class="hover:bg-foreground/5">
                  <td class="border-border border-t p-0 text-sm">
                    <a
                      [routerLink]="['/templates', t.id]"
                      class="flex items-center justify-between gap-3 px-3 py-2.5"
                    >
                      <span class="text-sm font-medium">{{ t.name }}</span>
                      <app-icon name="chevron-right" class="text-muted-foreground" />
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
