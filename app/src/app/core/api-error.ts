import { HttpErrorResponse } from '@angular/common/http';

/** The human-readable reason out of a core error response ({"error": "..."}), or a fallback. */
export function apiError(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    const body = error.error;
    if (body && typeof body === 'object' && typeof body.error === 'string') {
      return body.error;
    }
    if (typeof body === 'string' && body.length > 0 && body.length < 300) {
      return body;
    }
    return `${fallback} (HTTP ${error.status})`;
  }
  return fallback;
}
