# Operator guide

You write a workflow in YAML. Fordism turns each step into a disposable container running an AI
agent, watches the filesystem for its result, and reaps it. This is how to drive that.

## The model

```
Workflow YAML → Orchestrator → Dispatcher → [agent container] → Collector → Reaper
```

The orchestrator for your chosen strategy decides which step is next. The dispatcher launches one
container for it. The agent works inside a mounted workspace and writes its result to a file. The
collector reads that file; the reaper removes the container.

**The filesystem is the protocol.** An agent reports by writing `result/result.json` into its
workspace — there is no callback and no queue. Core polls for that file.

> **An agent that writes no valid result is "rotten" and the task fails.** Chat output is never
> accepted as a result, so a model that talks convincingly but produces nothing cannot be mistaken
> for a success. This is the rule the whole engine is built around.

## Your first run

1. **Build and start.** The agent image sits behind a profile so `up` never tries to run it as a
   service — it is a recipe the launcher spawns from, not something long-lived.

   ```bash
   cp .env.example .env
   docker compose --profile build-only build
   docker compose up -d
   ```

2. **Create the admin account.** The first screen asks for your `FORDISM_ADMIN_SECRET` along with an
   email and password. If you left the variable unset, core generated one and printed it to its log
   on startup. The secret is spent by this one bootstrap; afterwards it does nothing, even across a
   restart.

3. **Add an Agent Profile.** A profile is where a model provider lives: a `baseUrl`, a write-only API
   key, a model, and the `tool` that drives the task (`claude-code` or `qwen-code`). There is no
   gateway; agents call the provider directly. Define exactly one profile and it becomes the default
   for everything.

4. **Run an example.** Workflows → `linear-example` → Run. Watch it on the Live page, then open the
   run to read the agent's result files, transcript, and token usage.

## Writing a workflow

A workflow is one YAML file. Unknown keys are rejected at parse time rather than ignored, so a typo
fails loudly instead of silently changing what runs.

```yaml
name: ship-the-fix
description: Draft a fix, then gate it on review.
strategy: rework
parameters: [requirement]
steps:
  - id: work
    template: generic
    task: |
      Write a Python function that satisfies this requirement,
      and save it to answer_work.txt:
      ${requirement}
  - id: gate
    template: generic
    includePreviousResult: true
    task: |
      Review the function in task/previous-result/answer_work.txt.
      Answer "pass" or "fail" with reasons.
    onFail:
      retry: work
      maxAttempts: 3
```

### Top-level keys

| Key | What it does |
|---|---|
| `name` | Identifies the workflow. Also its filename. |
| `description` | Shown in the workflow list. |
| `strategy` | One of the six below. Decides which orchestrator drives the run. |
| `parameters` | Values the operator supplies at run time, interpolated into tasks as `${name}`. |
| `steps` | The list of steps. Every strategy except `reconciler` uses this. |
| `generator` | `reconciler` only — the single step re-run each iteration. |
| `maxIterations` | `reconciler` only — the iteration budget before the run fails. |
| `tags` | Free-form labels for organizing workflows. |

### Step keys

| Key | What it does |
|---|---|
| `id` | Names the step so other steps can reference it. |
| `template` | The Agent Template that supplies skills, credentials, and standing instructions. |
| `task` | The instruction written to `task/task.md`. Use a block scalar for anything multi-line. |
| `includePreviousResult` | Mounts the previous step's `result/` at `task/previous-result/`. |
| `dependsOn` | `graph` only — the step ids that must collect first. |
| `when` | `conditional` only — a predicate, e.g. `classify.result == 'yes'`. |
| `forEach` | `map-reduce` only — fans out over a list parameter; each task sees `${item}`. |
| `onFail` | `rework` only — `retry` names the step to re-seed, `maxAttempts` bounds it. |
| `config` | Per-step overrides: `model`, `timeoutSeconds`, `network`, `maxAttempts`. |

### Parameters

A bare list is the shorthand. Spell a parameter out when you want a label, a type (`text`,
`textarea`, or `number`), a default, help text, or to mark it required.

```yaml
parameters:
  - name: requirement
    label: What should it do?
    type: textarea
    required: true
    help: One sentence is enough.
```

> **Network access is per step and closed by default.** Omit `config.network` and the agent joins
> only the launcher network, reaching core and nothing else. `none` isolates it completely; `full`
> really is the open internet.

## The six strategies

| Strategy | How steps are selected |
|---|---|
| `linear` | Steps in order, one at a time. Each can receive the previous step's workspace. |
| `graph` | Steps as a DAG. A step waits for its `dependsOn`; independent steps run in parallel. |
| `conditional` | A step runs only when its `when` predicate over an earlier step's verdict holds. |
| `map-reduce` | Exactly two steps: the first fans out over a list, the second reduces the results. |
| `rework` | A gate step re-seeds an earlier step until it passes; a spent `maxAttempts` fails the run. |
| `reconciler` | One generator step re-run until it reports done, bounded by `maxIterations`. |

Each ships as an example workflow in `core/workflows/` you can copy.

Branching strategies read the agent's `verdict` field, not its prose. That distinction matters: a
review that concluded *"no failures found"* once contained the word "fail" and sent good work back to
be redone.

## The agent contract

What an agent sees, and what it must write back. This is staged into every container as a skill, so
agents already know it — it is here so you know it too.

### The workspace

| Path | Contents |
|---|---|
| `task/task.md` | The task itself. Other files under `task/` are its inputs. |
| `task/previous-result/` | The previous step's output, when `includePreviousResult` is set. |
| `skills/` | Reusable capabilities the template selected. |
| `memory/` | Presets the agent treats as ground truth. |
| `result/` | Everything the agent produces, including its result file. |

### How a task ends

| State | Meaning |
|---|---|
| `finished` | Work done. Carries a one-line `summary`, an `artifacts` list, and a `verdict` when the task was a judgement. |
| `failed` | The agent could not do it and says why. An honest failure, kept as written. |
| `asked` | The agent needs a human decision. The run parks instead of guessing. |

```json
{ "state": "finished", "verdict": "pass",
  "summary": "Meets the requirement; no failures found.",
  "artifacts": ["answer_work.txt"] }
```

## When an agent asks

The question lands in the Questions inbox and the run parks. Your reply resumes the *same session* in
a fresh container, so the agent picks up with its context intact rather than starting over.

**If what it needs is a credential**, the agent names the environment variables it needs and never
asks for the value in prose. You get one masked field per name, and the values are injected as
environment variables into its container — deliberately not into the resume message, because that
message is written to the session transcript on disk.

> **Same-session resume is claude-code only today.** A qwen-code agent runs each task one-shot; it has
> no session to resume, so it also sits outside the self-heal loop that nudges an agent that ended its
> turn early.

## Templates and profiles

| Thing | What it decides |
|---|---|
| **Agent Profile** | Which provider and model an agent talks to, and which CLI drives it. The API key is write-only — no endpoint ever returns it. |
| **Agent Template** | The skills staged into the workspace, the credential keys granted, standing instructions, and optionally a profile or model that overrides the default. |
| **Skills** | Reusable capability documents. A template selects which ones a step's agent gets. |
| **Credentials** | Secrets stored centrally. One reaches a container only because a template declared its key. |

A grant is captured the moment a task is seeded, so editing a template cannot change what an
already-queued task receives. A retry is a new seed, and reads the template as it stands then.

## Users and permissions

Sign-in is local accounts, Google, or GitHub. What someone can do comes from the groups they belong
to.

A group holds members and grant patterns. Your permissions are the union of every group you are in. A
pattern ending in `.*` covers everything beneath it — `run.*` grants `run.read`, `run.answer` and
`run.workspace.download` alike — and bare `*` grants everything. Wildcards only work at the end:
`*.read` matches nothing.

### Groups that ship

| Group | Holds |
|---|---|
| `admins` | `*` — everything, including user and group administration. |
| `maintainers` | Every resource, but no user or group administration. |
| `operators` | Read everything, run workflows, answer questions, control runs — but not edit workflows or credentials. |
| `viewers` | Read-only, and not even workspace downloads. |

### The permissions themselves

| Family | Leaves |
|---|---|
| `workflow` | `read` · `write` · `run` |
| `run` | `read` · `answer` · `control` · `workspace.download` |
| `template` | `read` · `write` |
| `skill` | `read` · `write` |
| `profile` | `read` · `write` |
| `credential` | `read` · `write` |
| `user` | `read` · `write` |
| `group` | `read` · `write` |

`run.workspace.download` is deliberately separate from `run.read`: a workspace bundle can contain
anything the agent wrote, which is a wider thing to hand out than the ability to watch a run.

> **You cannot lock yourself out.** Any edit or deletion that would leave no group granting `*` to
> anybody is refused — including deleting the last admin user.

## Operating notes

**Where state lives.** There is no database. Core keeps runs and tasks in memory and snapshots them to
disk under the root you mount, alongside workflows, templates, skills, profiles, and credentials. Back
up that one directory and you have backed up everything.

**Keep it off the internet.** Every route requires a session and a permission, but core still mounts
the host Docker socket to spawn agents, and agents run with their CLI permission prompts disabled.
Anyone who can edit and run a workflow can run code on your Docker host. Run it on a network you
trust, for operators you trust.

**Cookies over plain HTTP.** Leave `FORDISM_COOKIE_SECURE` off for a LAN deployment on plain HTTP — a
Secure cookie is silently dropped there, which looks exactly like a broken login. Turn it on the
moment you put TLS in front.

**Watch the budget.** Each run's drill-down reports token usage per task. A `rework` or `reconciler`
workflow with a generous attempt budget can spend a lot before it gives up, so set `maxAttempts` and
`maxIterations` deliberately.
