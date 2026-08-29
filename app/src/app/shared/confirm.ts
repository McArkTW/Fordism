import { Component, Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { BrnDialogRef, injectBrnDialogContext } from '@spartan-ng/brain/dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogService, HlmDialogImports } from '@spartan-ng/helm/dialog';

type ConfirmData = { title: string; body: string; action: string; danger: boolean };

@Component({
  selector: 'confirm-dialog',
  imports: [HlmButton, HlmDialogImports],
  template: `
    <hlm-dialog-header>
      <h3 hlmDialogTitle>{{ data.title }}</h3>
      <p hlmDialogDescription class="whitespace-pre-line">{{ data.body }}</p>
    </hlm-dialog-header>
    <hlm-dialog-footer class="mt-4">
      <button hlmBtn variant="outline" (click)="close(false)">Cancel</button>
      <button hlmBtn [variant]="data.danger ? 'destructive' : 'default'" (click)="close(true)">
        {{ data.action }}
      </button>
    </hlm-dialog-footer>
  `,
})
export class ConfirmDialog {
  protected readonly data = injectBrnDialogContext<ConfirmData>();
  private readonly ref = inject<BrnDialogRef<boolean>>(BrnDialogRef);

  protected close(result: boolean): void {
    this.ref.close(result);
  }
}

/** window.confirm, but themed and awaitable: `if (await confirm.ask(...)) { ... }`. */
@Injectable({ providedIn: 'root' })
export class Confirm {
  private dialog = inject(HlmDialogService);

  async ask(title: string, body: string, action = 'Confirm', danger = false): Promise<boolean> {
    const ref = this.dialog.open(ConfirmDialog, {
      context: { title, body, action, danger } satisfies ConfirmData,
      contentClass: 'w-[26rem] max-w-[calc(100vw-2rem)]',
    });
    return (await firstValueFrom(ref.closed$)) === true;
  }
}
