import { Injectable } from '@angular/core';
import { toast } from '@spartan-ng/brain/sonner';

/** Transient notifications via sonner (the shell renders <hlm-toaster>). */
@Injectable({ providedIn: 'root' })
export class Toasts {
  ok(text: string): void {
    toast.success(text);
  }

  error(text: string): void {
    toast.error(text, { duration: 8000 });
  }
}
