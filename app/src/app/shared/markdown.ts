import { Component, computed, inject, input } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';

/** Rendered markdown (SKILL.md, agent result files). Content comes from our own backend. */
@Component({
  selector: 'app-markdown',
  host: { class: 'block' },
  template: `<div class="prose-md" [innerHTML]="html()"></div>`,
})
export class Markdown {
  readonly source = input<string>('');
  private sanitizer = inject(DomSanitizer);

  readonly html = computed<SafeHtml>(() => {
    const raw = marked.parse(this.source(), { async: false });
    // DomSanitizer strips scripts/handlers on bind; trust is NOT bypassed here on purpose.
    return this.sanitizer.sanitize(1 /* SecurityContext.HTML */, raw) ?? '';
  });
}
