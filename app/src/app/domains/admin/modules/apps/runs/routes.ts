import { Routes } from '@angular/router';

/**
 * History at the root, one run underneath it, and a step inside that.
 *
 * Live is a sibling route rather than a child: it is a different job, not a filtered History, and
 * making it a child would imply it inherits the query string it deliberately ignores.
 */
const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/history'),
  },
  {
    path: ':id',
    loadComponent: () => import('./features/run-detail'),
  },
  {
    path: ':id/tasks/:taskId',
    loadComponent: () => import('./features/run-detail'),
  },
];

export default routes;
