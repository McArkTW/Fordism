/**
 * The message to show for a failed API call.
 *
 * Core answers an error as `{"error": "..."}`, so that is the first thing to look for; the
 * transport's own message is the fallback, and a generic line is the last resort. Every board had
 * its own private copy of this, which is how they drifted into reporting the same failure
 * differently.
 */
export function errorMessage(error: unknown): string {
  const response = error as { error?: { error?: string }; message?: string };
  return response?.error?.error ?? response?.message ?? 'request failed';
}
