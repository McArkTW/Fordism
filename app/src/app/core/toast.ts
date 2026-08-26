import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/** Transient notifications via Material's snackbar. Errors stay longer and must be dismissable. */
@Injectable({ providedIn: 'root' })
export class Toasts {
  private snackBar = inject(MatSnackBar);

  ok(text: string): void {
    this.snackBar.open(text, undefined, { duration: 3500 });
  }

  error(text: string): void {
    this.snackBar.open(text, 'Dismiss', { duration: 8000 });
  }
}
