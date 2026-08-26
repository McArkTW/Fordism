import { Routes } from '@angular/router';
import { unsavedChangesGuard } from '@/app/core/unsaved-changes';

const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/template-list'),
  },
  {
    path: 'new',
    loadComponent: () => import('./features/template-edit'),
    canDeactivate: [unsavedChangesGuard],
  },
  {
    path: ':id',
    loadComponent: () => import('./features/template-edit'),
    canDeactivate: [unsavedChangesGuard],
  },
];

export default routes;
