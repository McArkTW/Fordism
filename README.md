<p align="center"><img src="docs/logo.svg" width="96" alt="Fordism — the F is the line; the violet square is the unit that shipped"></p>

# Fordism

**A workflow engine that runs AI agents as disposable containers.** You define a
**Workflow** (YAML) as a series of steps; Fordism fans each step through a strategy
**orchestrator** to a one-shot **agent container** (Claude Code, Qwen Code, Gemini CLI,
Codex or opencode) that does the task in a mounted workspace and reports a result. The engine dispatches,
collects, and reaps the containers; a run that produces no valid result is marked
**FAILED**, never rubber-stamped.

Agents are **disposable and least-privileged**: each runs in its own container, sees
only its `/workspace` (task · skills · memory · result), and calls its model provider
directly through its **Agent Profile**. Point a workflow at whatever work you like —
an ad-hoc task or a real backlog item.

## How it works

```
Workflow YAML → Orchestrator (per strategy) → Dispatcher → [Task = agent container] → Collector / Reaper
```

- **Six strategies**: `linear` · `graph` · `conditional` · `map-reduce` · `rework` ·
  `reconciler`. Each orchestrator is a small, declarative reconcile function driven by
  a level-triggered engine loop (Kubernetes-controller style). Example workflows for
  every strategy ship in `core/workflows/`.
- **The filesystem is the agent protocol.** The agent writes `result/result.json`
  (`finished` / `failed` / `asked`) into a bind-mounted workspace; core polls, collects,
  and removes the container. No callback, no queue. An agent that produces no valid
  terminal result is *rotten* and the task fails or retries — the engine never invents
  success from chat output.
- **Human in the loop.** An agent that cannot proceed writes `asked` plus a question
  instead of guessing. The run parks, the question surfaces in the UI, and your answer
  resumes the *same session* in a fresh container. A requested credential is injected
  as an environment variable — never into the transcript.
- **Five agent runtimes, one image.** The agent image bakes Claude Code, Qwen Code,
  Gemini CLI, Codex, and opencode; each Agent Profile carries a `baseUrl`, a write-only
  API key, a model, and the `tool` that drives the task. The environment follows the
  tool's dialect — Anthropic, OpenAI-compatible, or Google — so adding another CLI is one
  enum line, not a new code path. No LLM gateway — agents call providers directly.
  Same-session resume — human-in-the-loop, rework, and the self-heal loop — works across
  runtimes: the session store lives under the host-mounted workspace, so a later container
  resumes what an earlier one started.

## Repository layout

| Path | What |
|---|---|
| `core/` | Java 25 / Javalin — the **workflow engine**: strategy orchestrators, Dispatcher/Collector/Reaper, the container launcher, and the Agent Profile / template / skill stores. |
| `app/` | Angular 21 + Tailwind 4 + spartan/ui — the operator UI: **Workflows · Runs · Templates · Skills · Agent Profiles · Credentials · Users · Groups**. Built to a static SPA; the container is nginx (and the edge, proxying `/api` → core). |
| `docs/` | The [operator guide](docs/usage.md): the workflow YAML reference, the six strategies, the result contract, and the permission model. |
| `agent/` | The containerized agent runner — one image baking **five agent CLIs** (Claude Code, Qwen Code, Gemini CLI, Codex, opencode); the Agent Profile's `tool` selects which drives the task. The launcher runs one disposable container per task over a host-mounted `/workspace`. |
| `deploy/` | Deploy assets: per-env `*.env.example` templates and `scripts/deploy.sh` (pull + `compose up` + health check). |

## Quickstart

Everything is containerized — the only requirement is **Docker**. Published images are
multi-arch: **amd64 and arm64**.

`fordism-core` and `fordism-app` are published to GHCR; **the agent image is not**. It bakes
in the agent CLIs (Claude Code and Codex are proprietary), so you build that one image yourself. You
clone the repo either way (the agent build needs it), then pull core and app rather than
building them:

```bash
git clone https://github.com/McArkTW/Fordism && cd Fordism
cp .env.example .env                                       # set FORDISM_ADMIN_SECRET
docker compose --profile build-only build fordism-agent   # the one image you build (once)
docker compose pull                                        # core + app from GHCR
docker compose up -d
```

Pin a release by setting `TAG=v1.0.0` in `.env` — `latest` follows the newest tag. The agent
image is what the launcher runs per task; build it once and `up` never rebuilds it. Building it
locally is also how you accept the agent CLIs' own licenses.

**Or build from source**, which is also the dev loop:

```bash
git clone https://github.com/McArkTW/Fordism && cd Fordism
cp .env.example .env                       # set FORDISM_ADMIN_SECRET; the rest works locally
docker compose --profile build-only build  # builds core, app, AND the agent image
docker compose up -d
```

Open `http://localhost` (or `WEB_PORT`), then:

1. **Create the admin account** — the first visit asks for your `FORDISM_ADMIN_SECRET`
   and an email + password. The secret is consumed by this one bootstrap and is inert
   afterwards.
2. **Create an Agent Profile** (Agent Profiles page) — e.g. `baseUrl`
   `https://api.anthropic.com`, your Anthropic API key, model, tool `claude-code`.
   With exactly one profile defined, it is the default for everything.
3. **Run an example** — Workflows → `linear-example` → Run. Watch it on the Live
   page; drill into the run for the agent's result files, transcript, and token usage.

From there, the [operator guide](docs/usage.md) covers writing workflows (the full YAML
reference), the six strategies, the agent's result contract, and the permission model.

There is **no database** — core keeps run/task state in memory behind a `store` port
and snapshots it to disk under `FORDISM_ROOT`.

## Skills

A **skill** is a folder holding a `SKILL.md` (plus whatever scripts and references it
needs) that an agent loads on demand. An Agent Template names the skills its tasks get;
the launcher stages exactly those into the workspace, and a disabled skill is staged for
nobody.

The whole library is managed from the **Skills** page — no host access to
`${FORDISM_ROOT}/skills/` needed. Write a skill in the browser or upload its folder
whole; search, sort and page the library; select any number and delete them together;
open one to read every file in it, not just its `SKILL.md`.

**Plugins** install a skills repo by URL — a GitHub repo, `owner/repo`, or a direct
`.zip` over HTTPS. The repo arrives as an archive rather than a clone (the core image
carries curl, not git). Each plugin owns one folder in the library, named after the
repo, so syncing it only ever replaces what that plugin installed; a plugin whose folder
name collides with a skill you wrote is refused rather than taking it over. Removing a
plugin asks whether its skills go with it — keep them and they become your own.

```bash
# what the Plugins page does, if you would rather see the API
curl -X POST localhost/api/skill-plugins -H 'X-Fordism-Request: 1' \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://github.com/anthropics/skills"}'
```

## Security

> **Running a workflow runs code on your Docker host.** Core mounts the host socket to
> spawn agents, and agents run with their CLI prompts disabled — so an operator account
> is, in effect, code execution on the machine Fordism runs on (the same bargain a
> Jenkins job or its script console makes). Treat access accordingly. If you expose it
> beyond a trusted network, put real controls in front — TLS, an authenticating reverse
> proxy or VPN, `FORDISM_COOKIE_SECURE=true`, `FORDISM_TRUSTED_PROXY=true` — and prefer
> isolating the agent runtime from the host (rootless/socket-proxied Docker, or a
> sandbox runtime like gVisor/Kata) so a compromised agent cannot reach the host.

- **Auth is always on.** Sign in with a local account (default), Google, GitHub, or
  Microsoft (Entra ID) — each enabled by its `FORDISM_AUTH_*` config. An OAuth login
  maps to an existing user or an explicit email/domain allowlist; there is no open
  registration. The first visit bootstraps the admin account with the one-time
  `FORDISM_ADMIN_SECRET`.
- **The login resists guessing.** Failed sign-ins are rate-limited and lock out after
  a threshold, keyed on both the account and the caller IP so neither a spread-out nor
  a single-host attack slips through; behind a trusted proxy set `FORDISM_TRUSTED_PROXY`
  so the real client IP is the key. The same throttle guards the admin bootstrap.
- **Two-factor for local accounts.** TOTP (any authenticator app, no outbound mail or
  network needed), enrolled from the Security page with one-time recovery codes; the
  secret is stored like a password hash and never leaves the server. Or lean on an SSO
  provider's own MFA and disable local login.
- **The agent container is a sandbox for the host.** It runs non-root with every Linux
  capability dropped and `no-new-privileges`, under memory/CPU/PID caps, and with **no
  network unless the step asks for it** — while keeping full control inside `/workspace`
  so it can still install packages and run tests. A run-supplied rescue secret reaches
  only the step that declared or asked for it, not every later container.
- **Every change is audited.** Who did what, from which IP, allowed or refused — sign-ins,
  writes, token mints — on the admin-only Audit Log page.
- **API tokens for scripts.** A personal token calls the API without a browser. Its
  value is shown once and stored only as a hash, it can only ever hold *fewer*
  permissions than the account that minted it, and it cannot mint another.
- **Permissions are groups of grants.** Every API route requires a dot-named
  permission (`workflow.run`, `run.answer`, `credential.write`, …); a group holds
  users and grant patterns, where a trailing `.*` covers all descendants and `*`
  covers everything. Four editable groups ship seeded: **admins** (`*`),
  **maintainers**, **operators**, **viewers**.
- **No secrets live in this repository.** Provider API keys live per Agent Profile
  (write-only in the UI); agent credentials live on the Credentials page and reach a
  container only because an **Agent Template declared the key** — a drafting agent
  that reads a work item never holds a token that can push. The grant is captured
  when the task is seeded, so editing a template cannot change what an already-queued
  task receives.
- **Egress is per step.** A step's `network` is `none` (the default), `fordism-only`,
  or `full`. The agent talks to core through the mounted filesystem, not the network,
  so most steps need nothing; `fordism-only` reaches core over HTTP, and `full` really
  is the open internet.

Enforced rather than described: `CredentialStoreTest` proves an agent receives only
the keys its template declared and `DockerContainerLauncherTest` that a rescue secret
does not leak into a sibling step; `ApiShapeTest` proves no API response shape carries
a key, a credential value, or a TOTP secret; `IdTokenVerifierTest` proves a forged or
mis-audienced OAuth token is refused; and the auth suite proves the login throttle, the
second factor, and that every non-exempt route rejects the unauthenticated and the
under-privileged.

## Contributing

Build gates, the code-style bar, and the PR rules live in [CONTRIBUTING.md](./CONTRIBUTING.md).
The short version: `banVar` / `banStyle` and `ng lint` + Prettier fail the build, so the style
is enforced, not argued.

## Commercial support

Fordism is for teams that need to run coding agents on their own infrastructure — self-hosted,
inside your network, pointed at whatever model endpoint you choose (including a local one, so it
can run air-gapped). If you're evaluating it for that, or want help deploying, hardening, or
extending it — or a feature prioritized — reach out at <tony19907051@gmail.com>.

## License

Apache-2.0 — see [LICENSE](./LICENSE). Copyright 2026 The Fordism Authors.
Created and maintained by Tony ([tony19907051](https://github.com/tony19907051)),
published under the [McArkTW](https://github.com/McArkTW) organization.
