# Fordism

**A workflow engine that runs AI agents as disposable containers.** You define a
**Workflow** (YAML) as a series of steps; Fordism fans each step through a strategy
**orchestrator** to a one-shot **agent container** (Claude Code or Qwen Code) that
does the task in a mounted workspace and reports a result. The engine dispatches,
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
- **Two agent runtimes, one image.** The agent image bakes both Claude Code and Qwen
  Code; each Agent Profile carries a `baseUrl`, a write-only API key, a model, and the
  `tool` that drives the task. No LLM gateway — agents call providers directly.

## Repository layout

| Path | What |
|---|---|
| `core/` | Java 25 / Javalin — the **workflow engine**: strategy orchestrators, Dispatcher/Collector/Reaper, the container launcher, and the Agent Profile / template / skill stores. |
| `app/` | Angular — the operator UI: **Workflows · Runs · Templates · Skills · Agent Profiles · Credentials**. Built to a static SPA; the container is nginx (and the edge, proxying `/api` → core). |
| `agent/` | The containerized agent runner — one image baking **both agent CLIs** (Claude Code + Qwen Code); the Agent Profile's `tool` selects which drives the task. The launcher runs one disposable container per task over a host-mounted `/workspace`. |
| `deploy/` | Deploy assets: per-env `*.env.example` templates and `scripts/deploy.sh` (pull + `compose up` + health check). |

## Quickstart

Everything is containerized — the only requirement is **Docker**.

```bash
git clone https://github.com/mcark/fordism && cd fordism
cp .env.example .env        # defaults are fine for a local try-out
docker compose up -d --build
```

Open `http://localhost` (or `WEB_PORT`), then:

1. **Create an Agent Profile** (Agent Profiles page) — e.g. `baseUrl`
   `https://api.anthropic.com`, your Anthropic API key, model, tool `claude-code`.
   With exactly one profile defined, it is the default for everything.
2. **Run an example** — Workflows → `linear-example` → Run. Watch it on the Live
   page; drill into the run for the agent's result files, transcript, and token usage.

There is **no database** — core keeps run/task state in memory behind a `store` port
and snapshots it to disk under `FORDISM_ROOT`.

## Security

> **Do not expose Fordism to the internet.** By default the API requires no
> authentication, core mounts the host Docker socket to spawn agent containers, and
> agents run with their CLI permission prompts disabled. Run it on a network you
> trust, for operators you trust.

- **Auth is optional and pluggable.** Set `FORDISM_AUTH_PUBKEY` (your identity
  provider's RS256 public key, PEM), `FORDISM_AUTH_ISSUER`, and — recommended —
  `FORDISM_AUTH_AUDIENCE` to verify Bearer identity tokens. Empty means auth off:
  network-gate the deployment instead.
- **No secrets live in this repository.** Provider API keys live per Agent Profile
  (write-only in the UI); agent credentials live on the Credentials page and reach a
  container only because an **Agent Template declared the key** — a drafting agent
  that reads a work item never holds a token that can push. The grant is captured
  when the task is seeded, so editing a template cannot change what an already-queued
  task receives.
- **Egress is per step.** A step's `network` is `none`, `full`, or omitted — omitted
  (the default) joins only the launcher network, so the agent reaches core and nothing
  else; `full` really is the open internet.

Enforced rather than described: `CredentialStoreTest` proves an agent receives only
the keys its template declared, and `ApiShapeTest` proves no endpoint ever serializes
an API key or a credential value.

## Code style

Three gates plus a review bar. Every gate fails the build, so a rule here is not a suggestion.

| Gate | Covers |
|---|---|
| `./gradlew build` → `banVar` | `core` — `var` is banned, explicit types only |
| `./gradlew build` → `banStyle` | `core` — no `cfg`, no closed-set token compared with `.equals`, no method past three parameters (records exempt: a data carrier at a boundary is a definition, not a call site) |
| `ng lint` + Prettier | `app` — angular-eslint, single quotes, Tailwind class ordering |

Not gated — applied in review:

- **No DI in Java.** Construct and pass dependencies yourself; the whole graph is `Fordism.main`
  on one screen. Angular's `inject()` is idiomatic and expected in `app`.
- **OOP first.** Objects own their state and the behaviour over it — no anemic struct plus a
  `*Utils` bag. Plain DTOs at a boundary are the exception.
- **No boolean flag arguments.** A method that branches on a flag is doing two things; split it.
- **No abbreviations** outside `id url http api pr llm` — `workItemId`, never `wid`.
- **Failure and absence are explicit.** Throw with enough context to debug; return an empty
  collection or `Optional`, never a silent `null`.
- **Construction is not work.** A constructor assigns its fields; it does not touch the disk.
  Migrations and seeding are called from `Fordism.main`, where the wiring already lives.
- **Comments say why.** No restatement of the line above, no commented-out code.
- **Angular:** single-file components — split the template out past ~100 lines or when it needs
  real scoped CSS. Signals, standalone, `@if`/`@for`.

## License

Apache-2.0 — see [LICENSE](./LICENSE). Copyright 2026 The Fordism Authors.
Created and maintained by Tony ([tony19907051](https://github.com/tony19907051)),
published under the [mcark](https://github.com/mcark) organization.
