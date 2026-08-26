/**
 * Display mapping for run/task states. The raw enum values (e.g. ASKED) are
 * never shown to a user — every state resolves to a friendly label, a badge colour
 * class and an icon. Keep this the single source of truth for state styling.
 */
export type StatusView = { label: string; badge: string; icon: string };

/**
 * Map a raw run/task state to its friendly label + badge classes + icon.
 *
 * `runState` matters for one case: a task culled because its run was abandoned is REAPED, which
 * otherwise reads as a red "Failed". A run you deliberately stopped did not fail, and saying so
 * is the whole reason ABANDONED is a separate state.
 */
export function statusView(state: string | null | undefined, runState?: string | null): StatusView {
  if (runState === 'ABANDONED' && (state === 'REAPED' || state === 'ABANDONED')) {
    return { label: 'Stopped', badge: 'badge-muted', icon: 'circle-slash' };
  }
  switch (state) {
    case 'ABANDONED':
      return { label: 'Stopped', badge: 'badge-muted', icon: 'circle-slash' };
    case 'DONE':
    case 'COLLECTED':
      return { label: 'Done', badge: 'badge-ok', icon: 'circle-check' };
    case 'RUNNING':
    case 'ACTIVE':
      return { label: 'Running', badge: 'badge-info', icon: 'loader' };
    case 'PENDING':
      return { label: 'Pending', badge: 'badge-muted', icon: 'clock' };
    case 'FAILED':
    case 'REAPED':
      return { label: 'Failed', badge: 'badge-error', icon: 'circle-x' };
    case 'ASKED':
      return { label: 'Asked', badge: 'badge-warn', icon: 'message-circle-question-mark' };
    default:
      return { label: state ?? '—', badge: 'badge-muted', icon: 'circle-dot' };
  }
}

/** Human-friendly duration from milliseconds: "820ms", "12s", "1m 4s", "1h 2m". */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || ms < 0) {
    return '—';
  }
  if (ms < 1000) {
    return `${Math.round(ms)}ms`;
  }
  const totalSec = Math.round(ms / 1000);
  if (totalSec < 60) {
    return `${totalSec}s`;
  }
  const totalMin = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  if (totalMin < 60) {
    return sec ? `${totalMin}m ${sec}s` : `${totalMin}m`;
  }
  const hr = Math.floor(totalMin / 60);
  const min = totalMin % 60;
  return min ? `${hr}h ${min}m` : `${hr}h`;
}

/** Compact byte size: "820 B", "4.1 KB", "2.3 MB". */
export function formatSize(bytes: number | null | undefined): string {
  if (bytes == null || bytes < 0) {
    return '';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
