import { Component, Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { BrnDialogRef, injectBrnDialogContext } from '@spartan-ng/brain/dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogService, HlmDialogImports } from '@spartan-ng/helm/dialog';

/** One way out of the dialog, other than Cancel. */
export type ConfirmChoice = { key: string; label: string; danger?: boolean };

type ConfirmData = { title: string; body: string; choices: ConfirmChoice[] };

@Component({
  selector: 'confirm-dialog',
  imports: [HlmButton, HlmDialogImports],
  template: `
    <hlm-dialog-header>
      <h3 hlmDialogTitle>{{ data.title }}</h3>
      <p hlmDialogDescription class="whitespace-pre-line">{{ data.body }}</p>
    </hlm-dialog-header>
    <hlm-dialog-footer class="mt-4 flex-wrap">
      <button hlmBtn variant="outline" (click)="close(null)">Cancel</button>
      @for (choice of data.choices; track choice.key) {
        <button
          hlmBtn
          [variant]="choice.danger ? 'destructive' : 'default'"
          (click)="close(choice.key)"
        >
          {{ choice.label }}
        </button>
      }
    </hlm-dialog-footer>
  `,
})
export class ConfirmDialog {
  protected readonly data = injectBrnDialogContext<ConfirmData>();
  private readonly ref = inject<BrnDialogRef<string | null>>(BrnDialogRef);

  protected close(result: string | null): void {
    this.ref.close(result);
  }
}

/** window.confirm, but themed and awaitable: `if (await confirm.ask(...)) { ... }`. */
@Injectable({ providedIn: 'root' })
export class Confirm {
  private dialog = inject(HlmDialogService);

  async ask(title: string, body: string, action = 'Confirm', danger = false): Promise<boolean> {
    return (await this.choose(title, body, [{ key: 'ok', label: action, danger }])) === 'ok';
  }

  /**
   * More than one way to say yes — the chosen key, or null for Cancel.
   *
   * <p>Two buttons rather than a checkbox when the difference matters: removing a plugin can keep
   * its skills or delete them, and a checkbox nobody reads decides that silently. Naming both
   * outcomes on their own button makes the choice the thing being clicked.
   */
  async choose(title: string, body: string, choices: ConfirmChoice[]): Promise<string | null> {
    const ref = this.dialog.open(ConfirmDialog, {
      context: { title, body, choices } satisfies ConfirmData,
      contentClass: 'w-[30rem] max-w-[calc(100vw-2rem)]',
    });
    const closed = await firstValueFrom(ref.closed$);
    return typeof closed === 'string' ? closed : null;
  }
}
