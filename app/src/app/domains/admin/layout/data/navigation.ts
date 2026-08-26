import { IsActiveMatchOptions } from '@angular/router';

export type NavigationItem = {
  id: string;
  label: string;
  description?: string;
  route?: string;
  icon?: string;
  badge?: string;
  children?: NavigationItem[];
  disabled?: boolean;
  expanded?: boolean;
  activeOptions?: { exact: boolean } | IsActiveMatchOptions;
};

// New design — pages are being rebuilt one at a time, starting with Jobs.
// The previous nav (Planning, Tracker, Gates, Release, Metrics, Telemetry,
// Work Items + the Handbook group) is archived alongside the pages under
// modules/apps/_archive/ and will return as each page is redesigned.
export const NAVIGATION: NavigationItem[] = [
  {
    id: 'foundry',
    label: 'Foundry',
    description: 'run agents',
    children: [
      // Operate — what you do day to day
      {
        id: 'foundry/live',
        label: 'Live',
        icon: 'radio',
        route: '/live',
      },
      {
        id: 'foundry/runs',
        label: 'History',
        icon: 'history',
        route: '/runs',
      },
      {
        id: 'foundry/workflows',
        label: 'Workflows',
        icon: 'list-todo',
        route: '/workflows',
      },
      // Configure — set up once
      {
        id: 'foundry/templates',
        label: 'Agent templates',
        icon: 'package',
        route: '/templates',
      },
      {
        id: 'foundry/skills',
        label: 'Skills',
        icon: 'sparkles',
        route: '/skills',
      },
      {
        id: 'foundry/agent-profiles',
        label: 'Agent Profiles',
        icon: 'server',
        route: '/agent-profiles',
      },
      {
        id: 'foundry/credentials',
        label: 'Credentials',
        icon: 'key-round',
        route: '/credentials',
      },
    ],
  },
];
