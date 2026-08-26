/**
 * Copy text to the clipboard, wherever the app happens to be served from.
 *
 * `navigator.clipboard` only exists in a secure context, and UAT and PRD are plain HTTP on a
 * `.local` host — so the modern call is simply absent there and the copy button did nothing. The
 * hidden-textarea path is the fallback for exactly that case.
 */
export function copyText(text: string): void {
  const copied = navigator.clipboard?.writeText?.(text);
  if (copied) {
    copied.catch(() => copyViaTextarea(text));
    return;
  }
  copyViaTextarea(text);
}

function copyViaTextarea(text: string): void {
  const area = document.createElement('textarea');
  area.value = text;
  area.style.position = 'fixed';
  area.style.top = '0';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.focus();
  area.select();
  try {
    document.execCommand('copy');
  } catch {
    /* nothing more we can do */
  }
  document.body.removeChild(area);
}
