import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'live' },
  { path: 'live', loadComponent: () => import('./features/runs/live').then((m) => m.Live) },
  { path: 'runs', loadComponent: () => import('./features/runs/history').then((m) => m.History) },
  {
    path: 'runs/:id',
    loadComponent: () => import('./features/runs/run-detail').then((m) => m.RunDetailPage),
  },
  {
    path: 'runs/:id/tasks/:taskId',
    loadComponent: () => import('./features/runs/task-detail').then((m) => m.TaskDetailPage),
  },
  {
    path: 'workflows',
    loadComponent: () => import('./features/workflows/workflow-list').then((m) => m.WorkflowList),
  },
  {
    path: 'workflows/new',
    loadComponent: () => import('./features/workflows/workflow-edit').then((m) => m.WorkflowEdit),
  },
  {
    path: 'workflows/:name/edit',
    loadComponent: () => import('./features/workflows/workflow-edit').then((m) => m.WorkflowEdit),
  },
  {
    path: 'workflows/:name/run',
    loadComponent: () => import('./features/workflows/workflow-run').then((m) => m.WorkflowRun),
  },
  {
    path: 'templates',
    loadComponent: () => import('./features/templates/template-list').then((m) => m.TemplateList),
  },
  {
    path: 'templates/new',
    loadComponent: () => import('./features/templates/template-edit').then((m) => m.TemplateEdit),
  },
  {
    path: 'templates/:id',
    loadComponent: () => import('./features/templates/template-edit').then((m) => m.TemplateEdit),
  },
  { path: 'skills', loadComponent: () => import('./features/skills/skills').then((m) => m.Skills) },
  {
    path: 'agent-profiles',
    loadComponent: () => import('./features/agent-profiles/agent-profiles').then((m) => m.AgentProfiles),
  },
  {
    path: 'credentials',
    loadComponent: () => import('./features/credentials/credentials').then((m) => m.Credentials),
  },
  // The old inbox: a question is a run that needs you, and Live already shows those.
  { path: 'questions', redirectTo: 'live' },
  { path: '**', redirectTo: 'live' },
];
