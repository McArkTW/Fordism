# Changelog

## Unreleased

### More agent runtimes

- **Three more agent CLIs**: `gemini-cli`, `codex`, and `opencode`, alongside `claude-code` and
  `qwen-code`. The launcher's environment now follows the tool's model *dialect* (Anthropic /
  OpenAI-compatible / Google) rather than a per-tool branch, so a new OpenAI-compatible CLI is a
  single enum line. Each resumes across containers (human-in-the-loop, rework, self-heal); tools
  that cannot be assigned a fixed session id resume their sole per-container session by "latest".
  Pick the runtime per Agent Profile. **The published image drops none of this — but note the
  agent image, which bakes all five in, is built by operators, not published** (see below).

### Security hardening

Makes Fordism defensible for a wider, authenticated group. It does **not** make it safe on the
public internet — running a workflow is still running code on your Docker host — so the guidance
stands: trusted network, trusted operators, a VPN or authenticating proxy for remote access.

- **Login lockout and rate limiting.** Failed sign-ins lock out after a threshold, keyed on both
  the account and the caller IP, so neither a spread-out nor a single-host attack slips through; a
  lockout answers identically to a wrong password, leaking nothing. The admin bootstrap is throttled
  the same way. Behind a proxy, `FORDISM_TRUSTED_PROXY=true` keys on the real client IP.
- **Two-factor for local accounts (TOTP).** Enrolled from the new Security page — any authenticator
  app, no outbound mail or network — with one-time recovery codes shown once and stored only as
  hashes. The secret never leaves the server. Sign-in takes a code on top of the password; a group
  can require it, or an org can disable local login and lean on SSO's own MFA.
- **Agent container hardened at the edge, untouched inside.** The launcher drops all Linux
  capabilities, sets `no-new-privileges`, and caps memory/CPU/PIDs — while the agent keeps full
  control of `/workspace` and still installs packages and runs tests. Egress is now **off by
  default** (`network: none`); `fordism-only` and `full` are opt-in per step. A rescue secret
  reaches only the step that declared or asked for its key, not every later container in the run.
- **Audit log.** Sign-ins, writes, and token mints are recorded — actor, IP, action, allowed or
  refused — append-only, on the admin-only Audit Log page (`audit.read`).

**Upgrading.** The network default changed from `fordism-only` to `none`: a step that quietly relied
on reaching core over HTTP must now declare `network: fordism-only` (package-installing steps already
set `network: full` and are unaffected). Seeded groups are only created when missing, so add
`audit.read` to a non-admin group by hand if its members should read the trail.

## 1.0.0 — first public release (2026)

Fordism — a workflow engine that runs AI agents as disposable containers.

- **Microsoft (Entra ID) sign-in.** A fourth provider, sharing the OIDC id_token verifier
  Google sign-in already used — signature, issuer, audience, expiry and nonce in one place,
  with each provider keeping its own rule for what makes an address trustworthy (Google's
  `email_verified`; Entra's UPN, since a tenant-issued account is the verification).
  Configured with `FORDISM_AUTH_MICROSOFT_CLIENT_ID`, `_SECRET` and `_TENANT_ID`. The tenant
  must be the directory GUID: on `common`/`organizations`/`consumers` the id_token's issuer is
  only knowable from the token itself, so core refuses to start rather than pretend to check it.
- **API tokens.** Personal tokens for calling the API without a browser, on a new **API
  Tokens** page. The value is shown once and stored only as a SHA-256; what a token may do is
  the *intersection* of its grants and its owner's, so it can never gain a permission; it
  cannot mint or revoke a token; and deleting an account revokes its tokens with its sessions.
  A token call does not need the `X-Fordism-Request` header — nothing attaches an
  `Authorization` header on a victim's behalf.
- **qwen-code session resume and self-heal.** Both runtimes now resume: qwen-code is driven
  with `--session-id`/`--resume` (and `--chat-recording`, without which its own help says
  resume will not work), so answering a question, a resumed rework, and the headless self-heal
  loop all work whichever CLI drives the task.
- **`onFail.mode: resume` for rework.** A failing gate can now continue the retry step's
  existing session and hand it the gate's own summary, instead of starting it over against its
  last output. `onFail` is a parsed record rather than an untyped map, so a misspelled mode is
  a parse error instead of a silent `retry`. Staleness is now measured by when a task was last
  *armed* rather than created — a resumed task's creation time never moves, and the gate has to
  judge the corrected work rather than re-read last round's verdict.
- **Orchestrator reconcile tests.** All six strategies, driven tick by tick through a real
  `Engine` over in-memory repositories. The rules that were only comments before — a dead step
  never being walked past, a graph join waiting for every dependency, a rework's spent attempts
  failing rather than parking, a reconciler continuing from its last pass — now fail the build.
- **arm64 images.** `fordism-core`, `fordism-app` and `fordism-agent` publish as multi-arch
  manifests (amd64 + arm64) via buildx. The three arch-pinned downloads — the static docker CLI,
  `gh`, PowerShell — select their asset from `TARGETARCH`, and CI builds core and agent for
  arm64 and *runs* each binary, because a right-named tarball for the wrong arch installs
  quietly and dies as an Exec format error on first use.
- **Per-step `network` in the workflow editor.** A step's egress was parsed and enforced but
  invisible until a run; the outline now shows it, with `full` marked as a warning.
- **Finer user/group permissions.** `workflow.write.delete`, `skill.plugin.write` and
  `run.control.abandon` split off the permissions that used to cover them, plus `token.read` /
  `token.write` for your own API tokens. Each new leaf is a *descendant* of the old one, so a
  group granting `workflow.*`, `skill.*` or `run.*` is unaffected — but a group granting the
  bare leaf `skill.write` no longer installs a plugin, and `run.control` no longer stops a run.
  The permission vocabulary is now a file core pins to its enum and the app reads, with CI
  comparing the two, so the grant editor can no longer preview a permission that was renamed.
- **Child runs for the reconciler strategy.** An iteration can name whole workflows in its
  result and the engine starts one run each; the iteration is not over until every child has
  ended, and a child that did not end `DONE` fails the parent. Parents and children link to
  each other on the run page.

- **Engine** (`core/`, Java 25 / Javalin): six orchestration strategies (linear,
  graph, conditional, map-reduce, rework, reconciler) over a level-triggered
  reconcile loop; disposable one-container-per-task launching over a host-mounted
  workspace; result validation (no valid `result.json` ⇒ failed, never
  rubber-stamped); human-in-the-loop rescue with same-session resume and write-only
  secret injection; in-memory state with a disk snapshot — no database.
- **Auth & RBAC**: always-on authentication — local accounts, Google, GitHub, and
  Microsoft sign-in, each enabled by config; one-time admin bootstrap via
  `FORDISM_ADMIN_SECRET`; group-based permissions with wildcard grants
  (`workflow.*`, bare `*`) over dot-named route permissions; four seeded, editable
  groups (admins, maintainers, operators, viewers); cookie sessions with CSRF
  protection. OAuth sign-ins map only to existing users or an email/domain
  allowlist.
- **Agent** (`agent/`): one image baking Claude Code and Qwen Code (both pinned);
  the Agent Profile's `tool` picks the runtime; headless self-heal loop resumes a
  session that ended its turn waiting on background work, for either runtime.
- **Operator UI** (`app/`): Angular 21 + Tailwind 4 + spartan/ui (vendored, MIT) —
  Live/History run views, run→task drill-down with transcripts and token usage,
  YAML workflow editor with live server-side validation, templates/profiles/
  credentials management, and user/group administration.
- **Skills library**, managed entirely from the UI — no host access to
  `${FORDISM_ROOT}/skills/` required:
  - Four pages: the library, one skill, the editor, and the plugins.
  - The library lists every skill with its owner, file count and age; searchable
    across name, description and plugin; sortable on any column; paged; and
    readable either flat or grouped by the plugin that installed each skill.
  - Select any number of skills and delete them in one request, which reports per
    name rather than stopping at the first failure.
  - A skill is a folder, so every file in it is readable — not just `SKILL.md`.
    Text is served up to 256 KB; anything else is reported as binary rather than
    sent.
  - Write a skill in the browser or upload its whole folder, `scripts/` and all. A
    refused upload never touches the skill it was replacing.
  - **Plugins**: install a skills repo by URL (a GitHub repo, `owner/repo`, or a
    direct `.zip` over HTTPS — fetched as an archive, no git in the image); a
    separate `skill.plugin.write` permission, since that points the instance at a
    URL. Each
    plugin owns one folder in the library, so a sync only ever replaces what that
    plugin installed. A plugin whose folder name collides with a hand-written skill
    is refused rather than taking it over. Removing a plugin asks whether its
    skills go with it — keep them and they become your own.
  - Per-skill enable/disable, kept separate from content so a disabled skill is
    excluded from future runs without being deleted.
- Out of the box: bootstrap the admin, create one Agent Profile, and the bundled
  example workflows run.
