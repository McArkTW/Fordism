# Project Status

_Last updated: 2026-08-08. Six defects fixed and ~6,300 lines deleted, including Postgres (core
ships no JDBC driver and never connected). A run can be **abandoned**; the UI split into **Live**
(what needs you) and **History** (searchable, paged); **Questions** removed — a question is a run
that needs you. Tests where there were none (**71**) and a **banStyle** gate so the style rules
stop depending on anyone remembering them. Jenkins CI/CD · two envs on tds-lab._

⚠️ **PRD runs `v0.7.0` (`7e9debd`), which is four commits behind `main`.** Abandon-a-run,
Live/History, the repeated/absent query-parameter fixes and the Agent-templates split are on **UAT
only** until a `v0.7.1` tag is cut. Read `/api/version` on each environment rather than assuming
the newest tag carries the newest work.

**2026-08-26 — the agent self-heals a clean early exit.** A headless (`claude -p`) agent that ends
its turn "waiting for a background job" exits rc 0 with `result.json` still `running` and used to
rot (run faa9cb01 on UAT: PR opened, dev task mid-run, report never written). The entrypoint now
resumes the same session in the same container every 60 s until a terminal state or the step's
`timeoutSeconds` budget is spent — same container so the processes and `/tmp` files the agent left
behind survive. qwen-code is excluded (no session resume). The doctrine (`agent/CLAUDE.md`) and the
`foundry-agent` skill now also state the rule: background tasks never notify a headless agent —
poll synchronously.

## Overview
The Foundry — a container-first **workflow engine that runs AI agents as disposable
containers**. A Workflow (YAML) fans its steps through a strategy **orchestrator** to
one-shot **agent containers** (Claude Code or Qwen Code) that do the task in a mounted
workspace and report a result. Built from a clean repo (`hpi-secret/tds-foundry`); the
full stack runs locally end-to-end and is **deployed + CI/CD-live on tds-lab** (UAT + PRD, Jenkins).

## Stack & decisions (locked)
| Area | Decision |
|---|---|
| Backend | **foundry-core** — **Java 25** / Javalin, **no DI, plain OOP**, hand-wired in `App.main()` |
| Frontend | **foundry-app** — Angular 21 / Fuse, **static SPA served by nginx** (also the edge: proxies `/api` → core) |
| Agent | **foundry-agent** — a **disposable Claude Code / Qwen Code container** (`node:22-slim`, **both CLIs baked in**, no Java). Its whole world is a host-mounted `/workspace` (`task/` · `skills/` · `memory/` · `result/` · `config.json`). The Agent Profile's `tool` picks the runtime at launch (`AGENT_TYPE`: `claude-code` → `claude -p` \| `qwen-code` → `qwen --yolo -p`); it does the task, writes `result/result.json`, and exits. **Ephemeral** — core (launcher) `docker run`s one per task; no valid `result.json` ⇒ rotten ⇒ FAILED. |
| JSON / logging | **Gson** + **tinylog** across both Java services |
| Build | **Gradle 9.6.1** (wrapper) + Shadow 9.0.0 → fat jar. Shipping builds run **in Docker**; local `./gradlew` verification uses the host **JDK 25** (`~/.jdks/ms-25.0.3`) |
| State | **in-memory** task/run repos (a `store` port) + a disk **`JsonStateStore`** snapshot restored on boot. **Postgres was removed 2026-08-08** — core shipped no JDBC driver and never opened a connection, so three environments ran a database for nothing. It is still the intended swap; bring the container back alongside a real store implementation, not before. `RunQuery` is already shaped like the SQL it will become (WHERE, ORDER BY, keyset cursor) |
| LLM | **no gateway (LiteLLM removed)** — each **Agent Profile** carries its own `baseUrl`, write-only API key, and a `tool` (`claude-code` \| `qwen-code`). One agent image bakes **both** CLIs; the launcher points the agent's env **straight at the provider** per tool — `claude-code` → `ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN` (Claude → `https://api.anthropic.com`), `qwen-code` → `OPENAI_BASE_URL`/`OPENAI_API_KEY`/`OPENAI_MODEL` (a qwen profile → Ollama's `/v1` on a GPU host). `LLM_BASE_URL` (from `OLLAMA_BASE_URL`) is the default backend when no profile is set |
| Packaging | containerized & **GPU-free**; `docker compose up` = **core · app** (the launcher spawns agent containers on demand); Ollama is external. No database, no LLM-gateway service. The agent image sits behind a `build-only` profile — built, never run |
| Access | **network-gated** — the `FOUNDRY_ACCESS_TOKEN` gate was dropped; no login |
| CI/CD | **Jenkins** (native, on tds-lab — `jenkins.local`) — multibranch `tds-foundry`, `Jenkinsfile` in repo. `main` → **UAT** (auto) · tag `vX.Y.Z` → **PRD** (auto — cutting the tag *is* the deploy decision; tags auto-build). GitHub Actions removed; GitHub is the code/PR home only. |
| Images | built **on the box** by Jenkins (`foundry/foundry-*:local`); the *same* artifact is promoted UAT→PRD, differing only by env file (db/port/ADO-tag scope) |
| Live | both environments run on **tds-lab**: UAT `foundry-uat.local` (`foundry-managed-test`) · PRD `foundry.local` (`foundry-managed`), behind the nginx edge |

## Code style
See [Code style](./README.md#code-style) — three CI gates (`banVar`, `banStyle`, `ng lint`) plus the
review bar. `banStyle` fails the build on `cfg`, on a closed-set token compared with `.equals`, and
on a method past three parameters; it found four offenders the first time it ran.

## Package structure
**core** is the Java workflow engine; **agent** is a shell-driven container (not Java).
```
core   com.hp.vcosmos.foundry   (the workflow engine)
├── Foundry · App        (composition root/main + the Javalin app)
├── config/      FoundryConfiguration
├── model/       task/ (Task · TaskSeed · TaskConfiguration · TaskState · TaskMode · ReportedState ·
│                       TaskResult · NetworkPolicy)
│                workflow/ (Workflow · Step · Strategy · Parameter) · run/ (WorkflowRun · WorkflowRunState)
├── store/       TaskRepository · WorkflowRunRepository (+ InMemory impls) · RunQuery · JsonStateStore
├── workspace/   TemplateStore · AgentTemplate · TemplateView · WorkspaceStager · TaskResults ·
│                WorkspaceArchive · ResultFile · TokenUsage
├── launch/      ContainerLauncher · DockerContainerLauncher · ModelRegistry · AgentBackend ·
│                SessionIdentifierFactory · Proc
├── parse/       WorkflowLoader
├── field/       Dispatcher · Collector · Reaper · OrphanCuller · FieldView · CullPolicy
├── orchestrate/ Engine · Orchestrator · OrchestratorRegistry · ReconcileLoop ·
│                strategy/ (Linear · Rework · Graph · MapReduce · Conditional · Reconciler)
├── agentprofile/ AgentProfile · AgentProfileStore
├── skill/       SkillStore · SkillState
├── credential/  Credential · CredentialStore · CredentialView
└── web/         App(routes) · Api · Json · Views · WorkflowController · RunController ·
                 TemplateController · SkillController · AgentProfileController · CredentialController

agent   (a disposable Claude Code / Qwen Code container — NO Java)
├── Dockerfile     node:22-slim + BOTH CLIs baked in (Claude Code + Qwen Code)
├── entrypoint.sh  branches on AGENT_TYPE (claude-code → `claude -p` · qwen-code → `qwen --yolo -p`);
│                  runs the workspace contract, writes result/result.json, enforces rotten→FAILED
├── CLAUDE.md      the agent doctrine (the contract brief)
└── skills/foundry-agent/SKILL.md   built-in skill = the result contract, staged into every workspace
```
Flow: `Workflow YAML → Orchestrator (per strategy) → Dispatcher → [Task = agent container] → Collector / Reaper`, driven by `ReconcileLoop`.

## Done — working, verified live
- [x] **The signed-in account is visible (2026-08-13)** — Heimdall login landed with no consumer:
  `/api/auth/me` verified the token and stored a profile, but nothing in the app ever called it,
  and `layout/ui/user.ts` was dead code whose docblock still said Foundry has no login. The sidebar
  now carries an account block (initials · display name · email) with Appearance and Sign out under
  it; a 401 drops the stored token so the next load re-authenticates. Two things the type-checker
  could not see were caught by rendering it: `bg-primary` paints nothing (the theme defines only
  numbered shades — use `bg-primary-600`/`text-primary-50`, the pair `material.css` maps
  primary/on-primary to), and the same is true of every `bg-primary/15` chip already shipped on
  Agent Profiles and History — **unfixed, they render with no background**.
  - **The app has tests now, and they gate the image** — `ng test` (vitest + jsdom) runs in
    `app/Dockerfile` before `ng build`, so a broken template fails CI. `src/test-setup.ts` stubs
    `matchMedia`, without which nothing that reaches Theming can be instantiated.
  - **The Heimdall service name follows the host** — it was pinned to `foundry-uat`. PRD login
    still cannot work: Heimdall's `_SEED` registers `foundry-uat` but not `foundry`, and a
    registration is one exact `redirect_uri`.
  - **`HeimdallAuth.verify` ignores `aud`** — a token minted for any other lab service
    (`imitation`, `reactor-uat`, `release`) is accepted by Foundry as a valid identity. Signature,
    `exp` and `iss` are checked; the audience is not. Not fixed here: the check needs core to know
    its own service name, i.e. a new env var through `docker-compose.yml` and both env files.
- [x] **All six strategies proven end-to-end (2026-08-08)** — including **Graph**, which had never
  run: `left`/`right` fanned out in parallel and `join` seeded only once both collected. Rework
  escalates correctly at its attempts cap; the ask→answer→**resume** loop continues the same session
  (agent wrote the word from the reply) and the supplied secret reached it as an env var **without
  appearing in any of the 15 workspace files, transcript included**.
- [x] **Six defects fixed (2026-08-08, `e7756f1`)** — the agent's git credential helper was unscoped
  and offered `GITHUB_TOKEN` to every https remote; the `NEEDS_RESCUE → ASKED` rename shipped with no
  read-compat, so an older snapshot restored a null state; a typo in `dependsOn` left a graph run
  ACTIVE forever (now refused at parse, with the valid ids); control flow branched on finding a word
  inside prose, so a gate reporting "no failures found" reworked its own passing work (`result.json`
  now carries an explicit `verdict`); workspaces were `chmod 777` and kept forever (now chgrp'd to the
  agent gid and 775 — **verified on the box**); and a throw in the collect or reap phase abandoned the
  rest of the tick for every task on the instance.
- [x] **Creating a workflow, broken since `553821e`** — `save()` read the name with `pathParam`, which
  throws on the create route. "New workflow → Save" had been failing in the UI. Found by trying it.
- [x] **~6,300 lines deleted (`9106242`, `67afcbe`, `eb11f5f`)** — the 13 archived pages, the jobs
  module against endpoints that do not exist, the access-token scaffolding for a gate that was
  dropped, three unused Fuse domains, all of `docs/` (14 files carried vocabulary the code had
  renamed, and two asserted a security posture the credential grant had removed), and **Postgres**.
- [x] **Abandon a run (2026-08-08, `d329bf4`)** — `POST /api/runs/{id}/abandon`. The state is the whole
  instruction; `OrphanCuller` enforces "no task may be live under a run that has ended", marking the
  task terminal **before** killing the container — kill first and the Reaper decides it rotted and
  re-queues it. Also repairs a leak older than the feature: an orchestrator failing a run left its
  other running containers alive, holding launcher slots forever. **Verified live**: abandoned a
  running probe, container gone, no retry across four ticks.
- [x] **Live and History (2026-08-08, `7267aa3`)** — the Runs page did two jobs badly. **Live** shows
  what is running and what is waiting, no filter, asked above running and oldest asked first; empty is
  a designed state. **History** is every run, searchable, filterable, cursor-paged, and **does not
  poll** — a searched list that rearranges itself while you read it is unusable. Both link to
  `/runs/:id`, so a run renders in one place; a step is linkable at `/runs/:id/tasks/:taskId`.
  **Questions is gone** — a question is a run that needs you, and the run detail could already answer
  one. The Live nav item carries the count so you still notice from elsewhere; `/questions` redirects.
- [x] **Agent templates split (`11f0d56`)** — list / `:id` / `new`, with a deactivate guard. Picking a
  template used to swap the form in place and silently discard typed instructions.
- [x] **Tests, from zero to 71** — the verdict logic, snapshot compatibility for every former enum
  spelling, the loader's reference checks, the API's field names (pinned against the shapes read off
  running UAT), the credential grant, and the culler. Two of them caught real bugs while being
  written: a comment that lied about `${missing}`, and a cursor that would drop or repeat rows when
  runs share a millisecond.
- [x] **`banStyle` gate** — `cfg`, closed-set `.equals`, methods past three parameters. It found four
  offenders on its first run, two of which were argument lists I had claimed were already fixed.
- [x] **Workflow-engine core** — `Workflow YAML → Orchestrator (per strategy) → Dispatcher → [Task = agent container] → Collector / Reaper`, driven by a `ReconcileLoop`. Six orchestrators registered — **five proven end-to-end with a live qwen3 agent** on 2026-07-19 (**Linear · Map-Reduce · Conditional · Rework · Reconciler** — fan-out/reduce, conditional branch, rework→human-escalation, and reconcile-until-done each drove a multi-step run), and **Graph** on 2026-08-08 (see the top of this block). Java 25 / Javalin / Gson / tinylog; `banVar` gate. HTTP: `/api/health` · `/api/version` · `/api/workflows[/{name}/run]` · `/api/runs/{id}` · `/api/tasks/{id}/result` · `/api/templates` · `/api/skills` · `/api/agent-profiles`.
- [x] **Disposable agent, two runtimes** — one image bakes **Claude Code + Qwen Code**; the launcher `docker run`s **one container per Task**, mounting a `/workspace` (`task/` · `skills/` · `memory/` · `result/` · `config.json`). The Agent Profile's `tool` picks the runtime at launch; the agent writes `result/result.json` and exits — **no valid result ⇒ rotten ⇒ FAILED** (the wrapper validates, never rubber-stamps). Cross-container **resume** works (`HOME=/workspace` persists the session). **Both runtimes verified on UAT**: `claude-code` → `api.anthropic.com` (real Claude) and `qwen-code` → Ollama-direct → qwen3 each drive a run and create files (qwen3 ~90% single-shot).
- [x] **Agent Profiles + direct providers** — no gateway (**LiteLLM removed**); each profile carries `baseUrl` + write-only key + `tool`, and the launcher points the agent straight at the provider. Skills library + template manifests wire through to the launched agent (detail in the P0–P6 block under Next up).
- [x] **app** — Angular / Fuse SPA served by nginx (also the edge, proxying `/api` → core): **Workflows · Runs · Templates · Skills · Agent Profiles** pages over the core API.
- [x] **Deployed + CI/CD live (2026-07-15)** — both environments run on **tds-lab** behind an nginx edge: UAT `foundry-uat.local` (ADO tag `foundry-managed-test`) · PRD `foundry.local` (`foundry-managed`). **CI/CD is Jenkins** (native on the box, `jenkins.local`): a multibranch job runs the repo `Jenkinsfile` when its repo scan is fired — the job has no periodic trigger, so `git pushb` (push + scan) is the way to ship — `main` → UAT (auto), `v*` tag → PRD (auto — tags auto-build then deploy, no approval click). One `foundry/foundry-*` image built on the box, promoted to both. GitHub Actions removed; GitHub is the code/PR home. Old Foundry retired + archived.
- [x] **PRD deploy hardened + made zero-click (2026-07-15)** — Foundry moved out of the shared `lab` home; live deploys bind only jenkins-owned paths; legacy trees archived to `/var/backups/foundry`. PRD cut over to a real release tag (`v0.1.1`), and its deploy is now **zero-click**: tags **auto-build** (`basic-branch-build-strategies`, ignore tags >1 day) and the `Approve PRD` input gate was **removed** (commit `fe88002`) — cutting a `v*` tag is the deploy decision. A red build still never deploys; validation happens on UAT first. Reinstate a gate if Foundry ever goes customer-facing / multi-team.
- [x] **Zero-click PRD deploy CONFIRMED end-to-end (2026-07-16)** — verified against the box's Jenkins build record: tag **`v0.1.2`** build #1 = SUCCESS, trigger `BranchIndexingCause` (auto-detected, no manual click), stages Checkout → Secret-scan → Build → *Deploy UAT skipped (when-conditional)* → **Deploy PRD** with **no `input`/Approve** step, and the Deploy-PRD stage's `curl localhost:8087/api/health` returned `>> PRD healthy`. The push-`v*`-tag → auto-build → auto-deploy-PRD → health-green path is proven live. (Verify future deploys the same way: SSH the box → `/var/lib/jenkins/jobs/tds-foundry/branches/<mangled>/builds/N/`; tag dirs are name-mangled, e.g. `v0-1-2.nba2f5`.)
- [x] **`/api/version` endpoint (2026-07-16)** — core now serves `GET /api/version` → `{service, version, gitSha, builtAt}`, stamped by CI at deploy (Jenkinsfile exports `FOUNDRY_GIT_SHA`/`FOUNDRY_VERSION`/`FOUNDRY_BUILT_AT` → compose → core env; safe `dev`/`unknown` fallbacks locally). You can now confirm **which build an env is running over HTTP** (`curl http://foundry.local:8087/api/version`) instead of reading Jenkins records on the box — closing the observability gap that made the `v0.1.2` check a manual SSH.
- [x] **Local build** — `JAVA_HOME=~/.jdks/ms-25.0.3 ./gradlew shadowJar` (core, `banVar` + compile) and `ng build` (app) run on the PC for fast pre-deploy verification; Docker stays the shipping path.
- [x] **Runs are investigable in-UI (2026-07-19)** — run → task drill-down with the agent's **summary**, its `result/` files rendered inline, the session **transcript**, **token usage**, per-task duration, copyable run/task ids, and downloads (`result.zip`, `workspace.zip`). Result files were regularised to `logs/{output.log,errors.log,transcript.jsonl}` with deliverables + `result.json` at the top. ⚠️ The enabling fix: the Collector `docker rm -f`s the container the instant the agent writes `result.json:finished`, so **any post-run wrapper step races and dies** — stdout/stderr must stream to files *during* the run, and usage/transcript are extracted in **core** from the host-persisted workspace, never by the wrapper afterwards.
- [x] **Rescue — the human in the loop (2026-07-19/20)** — an agent that cannot proceed writes `result.json` `state:needs_rescue` + `question` instead of guessing; the Collector maps it to `NEEDS_RESCUE`, `Engine.tick` pauses the run (uniform across all six strategies), and the task surfaces both inline and in a dedicated **Rescue inbox** (`GET /api/rescues`). A reply (`POST /api/tasks/{id}/rescue`) re-arms the *same* task in `resume` mode, and a fresh container continues the *same session*. **Verified E2E on UAT:** agent asked "X.md or Y.md?" → replied "Use Y.md." → resumed container produced `Y.md`, run DONE.
- [x] **`NEEDS_HUMAN` → `NEEDS_RESCUE` rename (2026-07-20, PRD `v0.6.3`)** — one word everywhere: task state, run state, `result.json` wire token, UI and docs. Shipped in two steps — `v0.6.2` carried a Gson `@SerializedName(alternate)` + dual-token reads so nothing had to deploy atomically, then `v0.6.3` removed the shims once both live snapshots had migrated themselves on first write-back. Verified on UAT across both releases: 47 runs / 56 tasks restored intact, rescue pause→reply→resume green, rework escalation green. **See Migration floor below.**

## Next up
### UX — make agent work observable & trustworthy
_Core principle: agents fail silently (e.g. qwen3 printing a tool call as text and "finishing" with nothing) — the UI's #1 job is letting you see what an agent actually did and why a run ended as it did._
_Done in this pass (2026-07-19/20) — see the Done block above: Runs live drill-down, the agent's actual work (result files + transcript), tokens per task, result-file clarity, the Rescue inbox, IA/nav (operate above configure), and a parameter form on the Workflows run action._

- [ ] **Run visualization per strategy shape** — linear=steps · graph=DAG · map-reduce=fan-out→join · rework=retry+escalation. Makes the engine legible and the per-strategy demos compelling.
- [ ] **Agent Profile "Test" button** — connection/health check (reachable? trivial task succeeds?) to kill provider-config debugging.
- [ ] **Teaching empty states** — convey the model (Workflow → Run → Agent) + a one-click sample run so a new user's first minute works.

### Other
- [ ] **Demo workflows — 1–2 per strategy** — ship ready-to-run showcase workflows covering each orchestration strategy (linear · rework · graph · map-reduce · conditional · reconciler) so Foundry's capabilities can be demonstrated out of the box.
- [ ] **qwen3 reliability — bake the fix** — experiment DONE (2026-07-19): the **strong contract prompt** is the win (10/10 vs ~90% baseline; temp-0 *hurt*, thinking-mode irrelevant), and correctness held **15/15 across a 5-level complexity ladder** — the only residual is a ~13% *contract-miss* (work done, `result.json` skipped). Remaining: **bake the strong prompt into the agent doctrine** (`agent/CLAUDE.md` + `foundry-agent` skill) + **wire rotten→retry** as the backstop.
- [ ] **Run agents on real workloads** — beyond demo tasks: point a `claude-code` profile at a real repo/task and have it produce real artifacts (a story → PR). Machinery is ready; needs the repo + credential decisions.
- [x] ~~**Per-run model variation / skills-library + Agent Profiles plan (P0–P6)**~~ — **DONE 2026-07-18, on UAT** (`gitSha 7613dee`). A template now carries its own backend + capabilities, resolved through to the launched agent:
  - **Skills library** (P0/P1): `SkillStore` + `/api/skills` (namespaced names, zip upload) + Skills page.
  - **Agent Profiles** (P2): `AgentProfileStore` — one JSON per profile under `/foundry/agent-profiles`, **API key write-only** (stripped from browser views; a blank key on save preserves the stored one) — `/api/agent-profiles` CRUD + `GET /api/models` (live `/v1/models` query per profile via `ModelCatalog`, manual fallback). `ModelRegistry.backend(profile, model)` resolves a named profile → `[baseUrl, token, tool, model]`. Each profile's `tool` (`claude-code` \| `qwen-code`, blank → `claude-code`) selects the **agent runtime**: **one image bakes both CLIs**, and the launcher wires `ANTHROPIC_*` (Anthropic dialect) or `OPENAI_*` (OpenAI-chat dialect) per tool. **Renamed from the old "LLM source"** — a boot migration moves any records left in `llm-sources/` into `agent-profiles/`. **LiteLLM/the gateway was removed** — agents call providers directly.
  - **Template = manifest** (P3): `<root>/<name>/manifest.json` = `{agentProfile, model, skills[], memory}` (`AgentTemplate`; reads the legacy `llmSource` key too); pre-manifest templates are migrated on read (synthesized, legacy inline files still staged).
  - **Templates page** (P4): Agent Profile dropdown, model picker (derived chips + manual fallback), skill multiselect, memory.
  - **Runtime wiring** (P5): `Dispatcher.applyTemplate` resolves the manifest onto the `Task` (its profile + model win); `stageInto` copies each named library skill (`SkillStore.copyInto`) into `workspace/skills/` + writes the memory seed; the launcher uses the resolved profile's backend.
  - **E2E** (P6): on UAT, template `p6-worker` (profile `p6-src` @ `http://p6-verify.local:4000`, key `sk-p6-secret`, model `p6-model-x`, skill `qa/p6-probe`) → the run's workspace got `skills/qa_p6-probe/SKILL.md` + `memory/seed.md`, `config.json` model = `p6-model-x`, and the **agent container** launched with `ANTHROPIC_BASE_URL=http://p6-verify.local:4000` + `ANTHROPIC_AUTH_TOKEN=sk-p6-secret`. Test artifacts cleaned up.
  - **Both agent runtimes verified on UAT**: a `claude-code` profile → `https://api.anthropic.com` (real Claude) and a `qwen-code` profile → Ollama-direct → qwen3 each drive a run and create files. qwen3 is ~90% reliable single-shot (a model-level flakiness, not a wiring bug — see Next up). The **Agent Profiles** CRUD page (`/agent-profiles`, over `/api/agent-profiles`) is built (was API-only when this plan landed).
- [ ] **Remove the unused SSR scaffolding** from `app` — the app ships as a static bundle on nginx, so `server.ts` / `main.server.ts` and the `@angular/ssr` + `express` dependencies are dead weight and extra attack surface.
- [ ] **P6 direct-request intake** (optional) — agent `/tasks` + core `POST /api/requests` so an agent can be asked directly, not only via the Foundry queue.
- [ ] **Tests for `app`** — the harness now exists and is a build gate (`ng test`, vitest + jsdom, `src/test-setup.ts` stubs `matchMedia`), but it covers **one component**: 6 specs on the sidebar account block. `core` has 71; `agent` is shell and has none. Every page a bug could hide on is still untested — spec the Runs/Live pages next, they are where the two UAT bugs landed.
- [ ] **CI on its own identity** — Jenkins uses tony's GitHub PAT, which is **also its multibranch `github-token` credential** (needs scopes `repo` + `read:org`). ⚠️ Don't revoke that PAT without first updating the Jenkins credential — doing so silently breaks scanning + all auto-builds. **PAT rotated 2026-07-15** (it had leaked into a `lab`-readable clone; Jenkins credential updated). Still to do: give CI a dedicated **bot** account / GitHub App with its own rate budget. The box no longer authors commits (the `/opt/foundry` clone was removed 2026-07-15) — author from the PC clone.

## Migration floor
- **No floor — every former name still reads.** The paused state has been renamed twice
  (`NEEDS_HUMAN` → `NEEDS_RESCUE` → `ASKED`, across the task state, the run state and the
  `result.json` wire token). The second rename shipped without a read-compat alternate, so any
  snapshot written before it deserialized those states to **`null`** — Gson maps an unknown enum
  constant to null rather than throwing — and the first orchestrator to switch on one failed the
  run. `TaskState` and `WorkflowRunState` now carry `@SerializedName(alternate)` for **both** old
  names, so a snapshot from any version restores. Keep the alternates when renaming again, or
  reintroduce the floor.
- **Four fields are enums now, and three of them are persisted.** `TaskMode` (`work`/`resume`),
  `AgentTool` (`claude-code`/`qwen-code`) and `Strategy` all carry `@SerializedName` for the exact
  lowercase spellings the old string fields wrote, so old snapshots and profile files still read.
  Verified on the box after v0.7.0: `mode {work: 138, resume: 5}`, strategies all lowercase.
- **Rolling back past v0.7.1 breaks an abandoned run.** `ABANDONED` is a new `WorkflowRunState`
  constant; a build that predates it reads those runs as null. Unavoidable when adding a state —
  restoring an older build means sed'ing `ABANDONED` → `FAILED` in `state.json` first.

## Open questions
- **The app is one component away from having no tests.** Two real bugs shipped to UAT during an
  earlier session's UI work and were caught by screenshotting the page, not by any gate: an absent
  query parameter binds as `undefined` (so `state().split()` threw and the page rendered as empty
  boxes), and `?state=A&state=B` was read as only `A`. Both compiled and linted clean. A harness and
  a build gate now exist (2026-08-13) and the account block is specced, but neither of those two
  bugs would be caught today — the pages they lived on still have no specs. Largest remaining risk.
- **Run retention.** Nothing ever deletes a run or its workspace — 80 runs, each keeping a full
  workspace on disk forever. Abandon stops work; it does not reclaim anything. Filtering makes the
  growth tolerable rather than fixing it.
- Skills: reference `tds-skills` (current default) vs. bundle a snapshot?
- PRD host: **resolved — stays on tds-lab (single box).** No second on-prem box planned; logical isolation (separate compose project/port/DB/ADO-tag) is enough for the internal, network-gated lab. If physical isolation ever matters the path is **cloud (Azure)** + a registry + deploy-only node, not new lab hardware.
- ~~Install a host **JDK 25** for non-Docker local builds?~~ **Resolved** — the PC already has an IntelliJ-managed **Microsoft OpenJDK 25** at `~/.jdks/ms-25.0.3`; `JAVA_HOME=~/.jdks/ms-25.0.3 ./gradlew build` runs `core`/`agent` (incl. `banVar`) locally without Docker. Node + `app/node_modules` are present too, so `ng lint`/`ng build` run locally. Docker stays the shipping path; local build is for fast verification.

## Links
- Repo: https://github.com/hpi-secret/tds-foundry (`main` is the source; Jenkins polls it)
- CI/CD: **Jenkins** on tds-lab — `http://jenkins.local` · live envs `http://foundry-uat.local` (UAT) · `http://foundry.local` (PRD)
- Build, deploy and code style: see the [README](./README.md)
