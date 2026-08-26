import { Routes } from '@angular/router';

const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('../runs/features/live'),
  },
];

export default routes;
