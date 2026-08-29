# Changelog

## 1.0.0 — first public release (2026)

Fordism — a workflow engine that runs AI agents as disposable containers.

- **Engine** (`core/`, Java 25 / Javalin): six orchestration strategies (linear,
  graph, conditional, map-reduce, rework, reconciler) over a level-triggered
  reconcile loop; disposable one-container-per-task launching over a host-mounted
  workspace; result validation (no valid `result.json` ⇒ failed, never
  rubber-stamped); human-in-the-loop rescue with same-session resume and write-only
  secret injection; in-memory state with a disk snapshot — no database.
- **Auth & RBAC**: always-on authentication — local accounts, Google, and GitHub
  sign-in, each enabled by config; one-time admin bootstrap via
  `FORDISM_ADMIN_SECRET`; group-based permissions with wildcard grants
  (`workflow.*`, bare `*`) over dot-named route permissions; four seeded, editable
  groups (admins, maintainers, operators, viewers); cookie sessions with CSRF
  protection. OAuth sign-ins map only to existing users or an email/domain
  allowlist.
- **Agent** (`agent/`): one image baking Claude Code and Qwen Code (both pinned);
  the Agent Profile's `tool` picks the runtime; headless self-heal loop resumes a
  claude-code session that ended its turn waiting on background work (qwen-code
  runs one-shot for now).
- **Operator UI** (`app/`): Angular 21 + Tailwind 4 + spartan/ui (vendored, MIT) —
  Live/History run views, run→task drill-down with transcripts and token usage,
  YAML workflow editor with live server-side validation, templates/skills/profiles/
  credentials management, and user/group administration.
- Out of the box: bootstrap the admin, create one Agent Profile, and the bundled
  example workflows run.
