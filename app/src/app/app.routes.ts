import { Routes } from '@angular/router';
import { authGuard, permissionGuard } from './auth/auth.guard';

/**
 * Two tiers. /login and /bootstrap are the only pages reachable without a session — everything
 * else hangs off an empty-path parent whose canActivateChild waits for /api/auth/me before it
 * decides. Pages that would be nothing but errors without a permission also carry a
 * permissionGuard; those bounce to '/' — never permission-guarded itself, so a refusal can
 * never bounce in a circle.
 */
export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./auth/login').then((m) => m.Login) },
  { path: 'bootstrap', loadComponent: () => import('./auth/bootstrap').then((m) => m.Bootstrap) },
  {
    path: '',
    canActivateChild: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'live' },
      { path: 'live', loadComponent: () => import('./features/runs/live').then((m) => m.Live) },
      {
        path: 'runs',
        loadComponent: () => import('./features/runs/history').then((m) => m.History),
      },
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
        canActivate: [permissionGuard('workflow.read')],
        loadComponent: () =>
          import('./features/workflows/workflow-list').then((m) => m.WorkflowList),
      },
      {
        path: 'workflows/new',
        canActivate: [permissionGuard('workflow.read')],
        loadComponent: () =>
          import('./features/workflows/workflow-edit').then((m) => m.WorkflowEdit),
      },
      {
        path: 'workflows/:name/edit',
        canActivate: [permissionGuard('workflow.read')],
        loadComponent: () =>
          import('./features/workflows/workflow-edit').then((m) => m.WorkflowEdit),
      },
      {
        path: 'workflows/:name/run',
        canActivate: [permissionGuard('workflow.read')],
        loadComponent: () => import('./features/workflows/workflow-run').then((m) => m.WorkflowRun),
      },
      {
        path: 'templates',
        canActivate: [permissionGuard('template.read')],
        loadComponent: () =>
          import('./features/templates/template-list').then((m) => m.TemplateList),
      },
      {
        path: 'templates/new',
        canActivate: [permissionGuard('template.read')],
        loadComponent: () =>
          import('./features/templates/template-edit').then((m) => m.TemplateEdit),
      },
      {
        path: 'templates/:id',
        canActivate: [permissionGuard('template.read')],
        loadComponent: () =>
          import('./features/templates/template-edit').then((m) => m.TemplateEdit),
      },
      // The fixed segments come first: 'skills/plugins' would otherwise be indistinguishable from
      // a skill named "plugins" if the detail route took a path parameter — which is the second
      // reason it takes ?name= instead, the first being that a skill name holds slashes.
      {
        path: 'skills',
        pathMatch: 'full',
        canActivate: [permissionGuard('skill.read')],
        loadComponent: () => import('./features/skills/skill-list').then((m) => m.SkillList),
      },
      {
        path: 'skills/plugins',
        canActivate: [permissionGuard('skill.read')],
        loadComponent: () => import('./features/skills/skill-plugins').then((m) => m.SkillPlugins),
      },
      {
        path: 'skills/new',
        canActivate: [permissionGuard('skill.read')],
        loadComponent: () => import('./features/skills/skill-edit').then((m) => m.SkillEdit),
      },
      {
        path: 'skills/edit',
        canActivate: [permissionGuard('skill.read')],
        loadComponent: () => import('./features/skills/skill-edit').then((m) => m.SkillEdit),
      },
      {
        path: 'skills/view',
        canActivate: [permissionGuard('skill.read')],
        loadComponent: () =>
          import('./features/skills/skill-detail').then((m) => m.SkillDetailPage),
      },
      {
        path: 'agent-profiles',
        canActivate: [permissionGuard('profile.read')],
        loadComponent: () =>
          import('./features/agent-profiles/agent-profiles').then((m) => m.AgentProfiles),
      },
      {
        path: 'credentials',
        canActivate: [permissionGuard('credential.read')],
        loadComponent: () =>
          import('./features/credentials/credentials').then((m) => m.Credentials),
      },
      {
        path: 'users',
        canActivate: [permissionGuard('user.read')],
        loadComponent: () => import('./features/users/users').then((m) => m.Users),
      },
      {
        path: 'groups',
        canActivate: [permissionGuard('group.read')],
        loadComponent: () => import('./features/groups/groups').then((m) => m.Groups),
      },
      {
        path: 'api-tokens',
        canActivate: [permissionGuard('token.read')],
        loadComponent: () => import('./features/api-tokens/api-tokens').then((m) => m.ApiTokens),
      },
      {
        // Self-service: no permission guard — every signed-in account manages its own second factor.
        path: 'security',
        loadComponent: () => import('./features/security/security').then((m) => m.Security),
      },
      {
        path: 'audit',
        canActivate: [permissionGuard('audit.read')],
        loadComponent: () => import('./features/audit/audit').then((m) => m.Audit),
      },
      // The old inbox: a question is a run that needs you, and Live already shows those.
      { path: 'questions', redirectTo: 'live' },
      { path: '**', redirectTo: 'live' },
    ],
  },
];
