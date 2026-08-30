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
| `onFail` | `rework` only — `retry` names the step to re-seed, `maxAttempts` bounds it, `mode` is `retry` (default) or `resume`. |
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

> **Network access is per step and off by default.** The agent talks to core through the mounted
> workspace, not the network, so a step that omits `config.network` gets **none** — fully isolated.
> Set `fordism-only` for the rare step that must reach core over HTTP, or `full` for the open
> internet (which a package install needs). A misspelled value fails closed, to `none`.
>
> This changed in the hardening release: the default was `fordism-only`. A workflow whose step quietly
> relied on reaching core must now say `network: fordism-only`; a step that installs packages already
> declared `network: full` and is unaffected.

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

### Rework: retry or resume

A failing gate sends the work back one of two ways, chosen by `onFail.mode`:

```yaml
onFail:
  retry: work        # the step to send it back to
  maxAttempts: 3
  mode: resume       # or: retry (the default)
```

`retry` plants a new task over a fresh workspace, staged with the retry step's own last result. The
agent starts a new session: it reads what it wrote last time as an input, but it does not remember
writing it, and it never sees what the gate objected to.

`resume` continues the session that is already there and hands the agent the gate's own summary —
rework as correction rather than as a second first attempt. It reuses the existing workspace, so a
skill or template edited between attempts is **not** re-staged; if that matters, use `retry`. A step
that never reached dispatch has no session to continue, and falls back to `retry` on its own.

A misspelled `mode` is a parse error, not a silent `retry`.

### Reconciler: delegating to whole runs

A reconciler iteration can do the work itself, or hand it to other workflows by naming them in its
result:

```json
{ "state": "finished", "verdict": "working",
  "runs": [ { "workflow": "fix-one-service", "parameters": { "service": "billing" } } ] }
```

Core starts one child run each and the iteration is not over until every one of them has ended. A
child that did not end `DONE` fails the parent — a reconciler whose delegated work failed has not
converged. The child runs are listed on the parent's run page, and each links back to it.

The `runs` field is read only under `reconciler`; any other strategy ignores it, so an agent that
writes it under a linear workflow has asked for nothing rather than quietly forked the instance.

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

Sign-in is local accounts, Google, GitHub, or Microsoft (Entra ID). What someone can do comes from
the groups they belong to.

### Two-factor (local accounts)

A local account can add TOTP from the **Security** page: scan the secret into any authenticator app,
confirm one code, and save the one-time recovery codes shown once (the server keeps only their
hashes). After that, sign-in asks for a code on top of the password; a recovery code stands in when
the authenticator is gone. It needs nothing outbound — no mail, no network — which is why it is the
second factor here rather than an emailed code. To turn it off, confirm your password on the Security
page. An org that would rather not manage TOTP can require an SSO provider (which brings its own MFA)
and disable local sign-in.

Failed sign-ins are rate-limited and lock out after a handful of tries, keyed on both the account and
the caller's IP. Behind a reverse proxy, set `FORDISM_TRUSTED_PROXY=true` so the lock keys on the real
client IP rather than the proxy's.

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
| `workflow` | `read` · `write` · `write.delete` · `run` |
| `run` | `read` · `answer` · `control.abandon` · `workspace.download` |
| `template` | `read` · `write` |
| `skill` | `read` · `write` · `plugin.write` |
| `profile` | `read` · `write` |
| `credential` | `read` · `write` |
| `user` | `read` · `write` |
| `group` | `read` · `write` |
| `token` | `read` · `write` — your own API tokens |
| `audit` | `read` — the audit trail; admins only |

Three of those are finer than they were in 1.0: `workflow.write.delete`, `skill.plugin.write` and
`run.control.abandon`. Each is a descendant of the permission that used to cover it, so a group
granting `workflow.*`, `skill.*` or `run.*` is unaffected — but a group granting the bare leaf
`skill.write` no longer installs a plugin, and one granting `run.control` no longer stops a run.
That narrowing is the point; widen those groups by hand if you did not want it.

`run.workspace.download` is deliberately separate from `run.read`: a workspace bundle can contain
anything the agent wrote, which is a wider thing to hand out than the ability to watch a run.

`skill.plugin.write` is separate from `skill.write` for the same shape of reason: installing a
plugin points this instance at a URL and stages whatever comes back into the library every agent
reads. Listing the installed plugins stays `skill.read` — that is reading the library.

### API tokens

A token calls the API without a browser — a script, a CI job, a cron. Make one on the **API Tokens**
page; the value is shown once and stored only as a hash, so a lost token is replaced, not recovered.

```bash
curl -H "Authorization: Bearer fordism_pat_…" localhost/api/runs
```

Three rules hold it in place:

- **A token only narrows.** What it may do is the intersection of its own grants and the grants its
  owner holds through their groups. A token can never gain a permission its owner lacks, and it
  loses one the moment its owner does.
- **A token cannot mint or revoke a token.** Those routes are refused to token-borne calls — a
  leaked token that could make more would be a leak with no end.
- **Deleting or locking an account revokes its tokens**, as it does its sessions.

The `X-Fordism-Request` header that browser writes must carry is not needed on a token call: nothing
attaches an `Authorization` header on a victim's behalf, so there is no cross-site request to forge.

Existing installs upgrading from 1.0 keep the groups they have — the seeded groups are only created
when missing, never widened — so add `token.*` to a group by hand if its members should be able to
mint one.

> **You cannot lock yourself out.** Any edit or deletion that would leave no group granting `*` to
> anybody is refused — including deleting the last admin user.

## Operating notes

**Where state lives.** There is no database. Core keeps runs and tasks in memory and snapshots them to
disk under the root you mount, alongside workflows, templates, skills, profiles, and credentials. Back
up that one directory and you have backed up everything.

**A workflow runs code on your Docker host.** Every route requires a session and a permission, the
login locks out guessers, local accounts can carry a second factor, the agent container is a non-root
sandbox, and every change is audited — but core mounts the host Docker socket to spawn agents, so
anyone who can edit and run a workflow can run code on the host. That is the tool working as designed
(a Jenkins job makes the same bargain), and hardening the login does not change it. Give access as you
would shell access.

If you expose Fordism beyond a trusted network, put real controls in front: TLS, an authenticating
reverse proxy or a VPN, `FORDISM_COOKIE_SECURE=true`, and `FORDISM_TRUSTED_PROXY=true` so the rate
limiter sees the real client IP. To narrow the blast radius of a compromised agent, isolate the agent
runtime from the host — rootless or socket-proxied Docker, or a sandbox runtime such as gVisor or
Kata — so an escape lands somewhere other than your machine.

**The audit log.** Sign-ins, writes, and token mints are recorded — actor, IP, action, allowed or
refused — on the admin-only Audit Log page and in `audit.log` under the state root. It is append-only
and rolls once past a size cap, so it survives a restart and cannot be quietly edited from the app.

**Cookies over plain HTTP.** Leave `FORDISM_COOKIE_SECURE` off for a LAN deployment on plain HTTP — a
Secure cookie is silently dropped there, which looks exactly like a broken login. Turn it on the
moment you put TLS in front.

**Watch the budget.** Each run's drill-down reports token usage per task. A `rework` or `reconciler`
workflow with a generous attempt budget can spend a lot before it gives up, so set `maxAttempts` and
`maxIterations` deliberately.
