import { Route } from '@angular/router';

/**
 * Every route is a Fordism board, at the root — /workflows, /runs, /questions, …
 *
 * There is no sign-in, marketing or coming-soon route: access is network-gated, so the whole
 * app is the admin shell.
 */
export const routes: Route[] = [
  {
    path: '',
    loadChildren: () => import('./domains/admin/routes'),
  },
];
