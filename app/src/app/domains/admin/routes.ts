import { Routes } from '@angular/router';
import { AdminLayout } from './layout/layout';

const routes: Routes = [
  {
    path: '',
    component: AdminLayout,
    children: [
      // Live is the landing page: the first thing you want to know is whether anything needs you.
      { path: '', pathMatch: 'full', redirectTo: 'live' },

      {
        path: 'live',
        loadChildren: () => import('./modules/apps/live/routes'),
      },

      {
        path: 'workflows',
        loadChildren: () => import('./modules/apps/workflows/routes'),
      },
      {
        path: 'templates',
        loadChildren: () => import('./modules/apps/templates/routes'),
      },
      {
        path: 'skills',
        loadChildren: () => import('./modules/apps/skills/routes'),
      },
      {
        path: 'runs',
        loadChildren: () => import('./modules/apps/runs/routes'),
      },
      // Questions was its own inbox; a question is a run that needs you, and the run detail is
      // where you answer it. Kept as a redirect so old links and bookmarks still land somewhere.
      { path: 'questions', redirectTo: 'live', pathMatch: 'full' },
      {
        path: 'agent-profiles',
        loadChildren: () => import('./modules/apps/agent-profiles/routes'),
      },
      {
        path: 'credentials',
        loadChildren: () => import('./modules/apps/credentials/routes'),
      },

      // -----------------------------------------------------------------------
      // Extras
      // -----------------------------------------------------------------------
      {
        path: 'error',
        loadChildren: () => import('./modules/extras/error/routes'),
      },

      // -----------------------------------------------------------------------
      // Documentation
      // -----------------------------------------------------------------------

      // 404
      {
        path: '404',
        pathMatch: 'full',
        loadComponent: () =>
          import('./modules/extras/error/features/error-404'),
      },

      // Catch all
      { path: '**', redirectTo: '404' },
    ],
  },
];

export default routes;
