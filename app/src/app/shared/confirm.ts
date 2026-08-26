import { Component, Injectable, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';

type ConfirmData = { title: string; body: string; action: string; danger: boolean };

@Component({
  selector: 'confirm-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content class="whitespace-pre-line">{{ data.body }}</mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton mat-dialog-close>Cancel</button>
      <button matButton="filled" [class.danger]="data.danger" [mat-dialog-close]="true" cdkFocusInitial>
        {{ data.action }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .danger {
      --mat-button-filled-container-color: var(--color-red-600, #dc2626);
      --mat-button-filled-label-text-color: #fff;
    }
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
}

/** window.confirm, but themed and awaitable: `if (await confirm.ask(...)) { ... }`. */
@Injectable({ providedIn: 'root' })
export class Confirm {
  private dialog = inject(MatDialog);

  async ask(title: string, body: string, action = 'Confirm', danger = false): Promise<boolean> {
    const ref = this.dialog.open(ConfirmDialog, {
      data: { title, body, action, danger } satisfies ConfirmData,
      width: '26rem',
    });
    return (await firstValueFrom(ref.afterClosed())) === true;
  }
}
