import { Routes } from '@angular/router';

/**
 * List, edit and run are separate pages. `withComponentInputBinding` is on, so `:name` arrives as
 * the components' `name` input.
 */
const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/workflow-list'),
  },
  {
    path: 'new',
    loadComponent: () => import('./features/workflow-edit'),
  },
  {
    path: ':name/edit',
    loadComponent: () => import('./features/workflow-edit'),
  },
  {
    path: ':name/run',
    loadComponent: () => import('./features/workflow-run'),
  },
];

export default routes;
