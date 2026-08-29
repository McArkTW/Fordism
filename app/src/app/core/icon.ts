import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * One lucide icon from the sprite the shell injects once. Sized by the host's font-size
 * (1em square), colored by `currentColor` — style it from the outside like text.
 */
@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg class="lucide" aria-hidden="true"><use [attr.href]="'#' + name()" /></svg>`,
  styles: `
    /* Sit on the text's optical center, not the baseline — otherwise the icon
       rides high beside button labels. */
    :host { display: inline-flex; line-height: 1; vertical-align: -0.125em; }
    .lucide {
      width: 1em; height: 1em;
      fill: none; stroke: currentColor;
      stroke-width: 2; stroke-linecap: round; stroke-linejoin: round;
    }
  `,
})
export class Icon {
  readonly name = input.required<string>();
}
