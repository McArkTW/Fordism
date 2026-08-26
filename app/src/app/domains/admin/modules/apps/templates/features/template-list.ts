import { Component, inject, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { errorMessage } from '@/app/core/api-error';
import { TemplateSummary, TemplatesService } from '../data/templates.service';

/** The templates there are. Opening one is a navigation, so an edit in progress cannot be lost. */
@Component({
  selector: 'template-list',
  imports: [MatButton, MatIcon, RouterLink],
  host: { class: 'block' },
  templateUrl: './template-list.html',
})
export default class TemplateList {
  private service = inject(TemplatesService);

  templates = signal<TemplateSummary[]>([]);
  error = signal<string>('');

  constructor() {
    this.service.list().subscribe({
      next: (list) => this.templates.set(list),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }
}
