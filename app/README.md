# fordism-app

The Fordism operator UI — Angular 21 (standalone, signals, zoneless), Tailwind 4 for
layout with Angular Material widgets on top (M3 theme in `src/material-theme.scss`),
CodeMirror 6 for the workflow YAML editor. Ships as a static SPA on nginx, which is
also the edge (proxies `/api` → `fordism-core`).

## Develop

```bash
npm ci
npm start        # dev server on :4200, /api proxied to localhost:8080 (proxy.conf.json)
npm run test     # vitest + jsdom — also a hard gate in the Docker build
npm run build    # production bundle to dist/fordism-app/browser
```

## Structure

- `src/app/core/api/` — thin typed services mirroring core's `/api` (the wire shapes
  are pinned against `tw.mcark.tony.fordism.web.Views`).
- `src/app/core/` — theming (light/dark via a `dark` class), snackbar toasts, the
  asked-runs attention poll, the generated lucide icon sprite (`scripts/build-icons.mjs`).
- `src/app/shared/` — the YAML editor, markdown renderer, confirm dialog.
- `src/app/features/` — one directory per screen: runs (Live · History · run/task
  detail), workflows, templates, agent-profiles, credentials, skills.
