# Changelog

## 0.8.0 — first public release (2026)

Fordism — a workflow engine that runs AI agents as disposable containers.

- **Engine** (`core/`, Java 25 / Javalin): six orchestration strategies (linear,
  graph, conditional, map-reduce, rework, reconciler) over a level-triggered
  reconcile loop; disposable one-container-per-task launching over a host-mounted
  workspace; result validation (no valid `result.json` ⇒ failed, never
  rubber-stamped); human-in-the-loop rescue with same-session resume and write-only
  secret injection; in-memory state with a disk snapshot — no database.
- **Agent** (`agent/`): one image baking Claude Code and Qwen Code; the Agent
  Profile's `tool` picks the runtime; headless self-heal loop resumes a session that
  ended its turn waiting on background work.
- **Operator UI** (`app/`): Angular 21 + Tailwind 4 + spartan/ui (vendored, MIT), fully
  rewritten for this release — Live/History run views, run→task drill-down with
  transcripts and token usage, YAML workflow editor with live server-side
  validation, templates/skills/profiles/credentials management.
- Optional OIDC Bearer-token auth (`FORDISM_AUTH_*`); network-gated by default.
- Out of the box: create one Agent Profile and the bundled example workflows run.
