# The Foundry

**A workflow engine that runs AI agents as disposable containers.** You define a
**Workflow** (YAML) as a series of steps; the Foundry fans each step through a
strategy **orchestrator** to a one-shot **agent container** (Claude Code or Qwen
Code) that does the task in a mounted workspace and reports a result. The engine
dispatches, collects, and reaps the containers; a run that produces no valid
result is marked **FAILED**, never rubber-stamped.

Agents are **disposable and least-privileged**: each runs in its own container,
sees only its `/workspace` (task · skills · memory · result), and calls its model
provider directly through its **Agent Profile**. Point a workflow at whatever work
you like — an ad-hoc task or a real backlog item.

## Repository layout

| Path | What |
|---|---|
| `core/` | Java 25 / Javalin — the **workflow engine**: strategy orchestrators, Dispatcher/Collector/Reaper, the container launcher, and the Agent Profile / template / skill stores. |
| `app/` | Angular 21 (Fuse) — the operator UI: **Workflows · Runs · Templates · Skills · Agent Profiles**. Built to a static SPA; the container is nginx (and the edge). |
| `agent/` | The containerized agent runner — one image baking **both agent CLIs** (Claude Code + Qwen Code); the Agent Profile's `tool` (`claude-code` \| `qwen-code`) selects which drives the task. The launcher runs one disposable container per task over a host-mounted `/workspace`. |
| `deploy/` | Deploy assets: `envs/` (per-env `*.env.example` templates) and `scripts/deploy.sh` (pull + `compose up` + health check). |

## Build & run

Everything is containerized — the only requirement is **Docker**. The stack is
GPU-free; there is **no LLM gateway** (LiteLLM was removed) — each agent calls its
model provider **directly**, at the `baseUrl` carried by its configured **Agent
Profile** (Claude → `https://api.anthropic.com`, a qwen profile → ollama's `/v1` on a
GPU host, …). The profile also carries a `tool` (`claude-code` \| `qwen-code`), and one
agent image bakes both CLIs — so the profile picks both the endpoint and the runtime.

**Run locally** (builds images from source):
```bash
git clone https://github.com/hpi-secret/tds-foundry && cd tds-foundry
cp .env.example .env        # fill secrets; set OLLAMA_BASE_URL to a GPU host
docker compose up -d        # builds core · app · agent from source
```
Open `http://localhost` (or `WEB_PORT`) → the operator UI (Workflows · Runs ·
Templates · Skills · Agent Profiles). The **app container is nginx**: it serves the
SPA and proxies `/api` → `foundry-core`.

**Services:** `foundry-core` (Java 25 / Javalin) · `foundry-app` (Angular / Fuse,
nginx). **There is no database** — core keeps run/task state in memory behind a `store`
port and snapshots it to disk; the Postgres container was removed 2026-08-08 because
core ships no JDBC driver and nothing ever connected to it. The **agent image** (bakes
Claude Code + Qwen Code) is **spawned one container per task** by the launcher — not a
long-lived service. There is no LLM-gateway service; provider keys live per Agent
Profile in the Foundry UI.

**Deploy** by hand on any Docker host: `docker compose up -d --build` — or let
**Jenkins** do it (below).

### How it ships (CI/CD)
CI/CD is **Jenkins**, self-hosted on the lab box (`jenkins.local`) — a multibranch
job that runs the repo `Jenkinsfile`. The box is private, so there are no webhooks,
and the job carries **no periodic scan** (`<triggers/>`): a build starts only when
the repo scan is fired, which is what `git pushb` below does.

| Trigger | Does |
|---|---|
| **pull request** | secret-scan (gitleaks) + `docker compose build` — status posted back to the PR (the merge gate) |
| **push to `main`** | build → **deploy UAT** (auto) + health check |
| **`v*` tag** | build → **deploy PRD** (auto, zero-click) + health check |

One image is built on the box and the **same artifact** is promoted UAT → PRD — the
two environments differ only by env file (db, port, ADO tag scope). Cutting a `v*`
tag *is* the deploy decision — there's no separate approval click (a red build still
never deploys; real validation happens earlier on UAT via `main`). This suits a
single-admin, network-gated lab; a customer-facing deployment would reinstate a gate.
GitHub stays the code + PR home; only the automation runs on Jenkins. Rollback =
re-tag an older commit. (GitHub Actions were removed; `deploy/scripts/deploy.sh` is a manual
fallback.)

**Push with `git pushb`.** A local alias in `.git/config` (not committed) that pushes
and then fires the repo scan, which is what starts the build. It uses a revocable
`ci-trigger` API token on the Jenkins `admin` account, stored as `JENKINS_CI_TOKEN`
in `~/.vcosmos/credential.json`. After a plain `git push`, fire the scan by hand —
`curl -X POST -u admin:$JENKINS_CI_TOKEN http://jenkins.local/job/tds-foundry/build?delay=0`
— or the commit sits on `main` and UAT keeps serving the old build.

> The Jenkins `github-token` credential expired on 2026-07-22 and silently broke this
> job's branch indexing (`401 Bad credentials`) — builds simply stopped without an
> obvious signal. If nothing has built for a while, check the job's *Scan Repository
> Log* first. Also: Jenkins rewrites `credentials.xml` and job configs from memory on
> shutdown, so `systemctl stop jenkins` **before** editing them by hand.

## Code style

Three gates plus a review bar. Every gate fails the build, so a rule here is not a suggestion.

| Gate | Covers |
|---|---|
| `./gradlew build` → `banVar` | `core` / `agent` — `var` is banned, explicit types only |
| `./gradlew build` → `banStyle` | `core` — no `cfg`, no closed-set token compared with `.equals`, no method past three parameters (records exempt: a data carrier at a boundary is a definition, not a call site) |
| `ng lint` + Prettier | `app` — angular-eslint, single quotes, Tailwind class ordering |

Not gated — applied in review:

- **No DI in Java.** Construct and pass dependencies yourself; the whole graph is `Foundry.main`
  on one screen. Angular's `inject()` is idiomatic and expected in `app`.
- **OOP first.** Objects own their state and the behaviour over it — no anemic struct plus a
  `*Utils` bag. Plain DTOs at a boundary are the exception.
- **No boolean flag arguments.** A method that branches on a flag is doing two things; split it.
- **No abbreviations** outside `id url http api ado pr llm` — `workItemId`, never `wid`.
- **Failure and absence are explicit.** Throw with enough context to debug; return an empty
  collection or `Optional`, never a silent `null`.
- **Construction is not work.** A constructor assigns its fields; it does not touch the disk.
  Migrations and seeding are called from `Foundry.main`, where the wiring already lives.
- **Comments say why.** No restatement of the line above, no commented-out code.
- **Angular:** single-file components — split the template out past ~100 lines or when it needs
  real scoped CSS. Signals, standalone, `@if`/`@for`.

Two of the review rules moved up into `banStyle` because they kept being fixed and then quietly
reappearing. If a rule matters, gate it; if it can't be gated, expect to enforce it by reading.

## Security

**No secrets live in this repository.** All runtime credentials — the per-Agent-Profile
provider API keys and the GitHub token (for CI) — are read at run time from `.env`
locally (or `deploy/envs/*.env` on a server), which is git-ignored and never committed.
Provider keys live per Agent Profile (write-only in the UI), never in the repo. The
`data/` directory is runtime state. Binary assets (`*.exe`, `*.msi`, `*.dll`) are
configured for **Git LFS** should any ever be added.

**What an agent can reach.** Nothing by default. A credential on the Credentials page reaches a
container only because an **Agent Template declared its key**, so a drafting agent that reads a
work item never holds a token that can push. The grant is captured when the task is seeded, not
at launch, so editing a template cannot change what an already-queued task receives. Egress is
whatever the step's `network` says — `full` really is the open internet.

That last point is a deliberate change of position, and worth knowing if you remember the older
docs: the grant used to hang off the *credential*, which named the profiles allowed to have it,
and "bound to nothing" meant no agent could get it. It now hangs off the **template**, so anyone
who can edit a template can grant any stored credential. Equivalent in effect only if editing a
template and handing out a secret are the same privilege in your head — decide that deliberately,
because the code no longer decides it for you.

Enforced rather than described: `CredentialStoreTest` proves an agent receives only the keys its
template declared, and `ApiShapeTest` proves no endpoint ever serializes an API key or a
credential value.

## Docs
- [Project status](./STATUS.md) — current state, decisions, next up
