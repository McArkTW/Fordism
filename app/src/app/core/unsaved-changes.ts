import { CanDeactivateFn } from '@angular/router';

/** A page that knows whether it holds work the user has not saved. */
export type HasUnsavedChanges = {
  dirty(): boolean;
}

/**
 * Ask before leaving a form with edits in it.
 *
 * This exists because the templates page used to swap its contents when you picked another
 * template from a list beside it — typed instructions vanished with no warning. Opening a template
 * is a navigation now, which is a thing that can be interrupted.
 */
export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (
  component
) =>
  !component.dirty() ||
  confirm('You have unsaved changes. Leave without saving?');
