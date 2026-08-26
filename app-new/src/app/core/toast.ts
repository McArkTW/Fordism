import { Injectable, signal } from '@angular/core';

export type Toast = { id: number; kind: 'ok' | 'error'; text: string };

/** Transient notifications, rendered by the shell. Errors stay longer than confirmations. */
@Injectable({ providedIn: 'root' })
export class Toasts {
  readonly items = signal<Toast[]>([]);
  private nextId = 1;

  ok(text: string): void {
    this.push('ok', text, 3500);
  }

  error(text: string): void {
    this.push('error', text, 8000);
  }

  dismiss(id: number): void {
    this.items.update((list) => list.filter((toast) => toast.id !== id));
  }

  private push(kind: 'ok' | 'error', text: string, ttlMs: number): void {
    const toast = { id: this.nextId++, kind, text };
    this.items.update((list) => [...list, toast]);
    setTimeout(() => this.dismiss(toast.id), ttlMs);
  }
}
