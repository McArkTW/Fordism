---
name: fordism-agent
description: The Fordism agent contract — the workspace layout and how to report your result. Always follow this when running as a Fordism agent.
---

# Fordism agent contract

You are a Fordism agent running autonomously in a disposable container. Your only world is
`/workspace`:

| Path | What |
| --- | --- |
| `task/task.md` | **Your task.** Other files under `task/` are its inputs. |
| `skills/` | Reusable capabilities — this contract plus any skills the task selected. Read them when relevant. |
| `memory/` | Authoritative presets / knowledge. Treat as ground truth. |
| `result/` | **Everything you produce goes here.** |

## How to do the work and report the result

1. **Read `task/task.md`** and do the task fully.
2. **Write every output or artifact under `result/`.** Example: if the task says *"create
   color.md"*, write it to **`result/color.md`**. Any files, reports, code, data → `result/`.
3. **Finish by writing `result/result.json`** with exactly this shape (use the Write tool):
   ```json
   { "state": "finished", "summary": "<one concise line>", "artifacts": ["color.md"] }
   ```
   - `summary` — one line describing the outcome. For a plain question, this *is* your answer
     (e.g. `"Paris"`).
   - `artifacts` — the file names you created under `result/` (use `[]` if none).
   - `verdict` — **required when your task asks you to judge something** (a review gate, a
     yes/no classification, "is the goal met?"). One lowercase word, on its own, and nothing
     else: `pass` · `fail` · `yes` · `no` · `done`. The workflow branches on this field.

     Put your reasoning in `summary`, never the verdict in prose. The engine used to look for
     the word inside `summary`, so a passing review that said *"no failures found"* contained
     "fail" and sent the work back to be redone. Write the field and that cannot happen:

     ```json
     { "state": "finished", "verdict": "pass",
       "summary": "Meets the requirement; no failures found.", "artifacts": [] }
     ```
4. **If you genuinely cannot complete the task**, write instead:
   ```json
   { "state": "failed", "summary": "<why you could not>" }
   ```
5. **If you need a human decision or information to proceed** — and cannot reasonably continue
   without it — **ask** by writing instead:
   ```json
   { "state": "asked", "question": "<exactly what you need from the human>", "summary": "<why you stopped>" }
   ```
   Your question goes to the **Questions** inbox. A human answers, and you are **resumed in the
   same session** with their reply — pick up from there and finish. Use this sparingly; prefer
   completing the task when you reasonably can.

   Ask only when an answer would actually unblock you. If you have simply run out of attempts, or
   the thing you need does not exist, write `failed` — a question nobody can answer sits in an
   inbox that cannot help you.

6. **If what you are missing is a credential** — a token, key, password or secret — add a
   `secrets` array naming the **environment variables** you need, and do *not* ask for the value in
   the `question` text:
   ```json
   {
     "state": "asked",
     "secrets": ["REGISTRY_USER", "REGISTRY_TOKEN"],
     "question": "Pushing the image needs registry credentials — publish.sh reads these two variables and no credential is present in this workspace.",
     "summary": "Stopped for missing registry credentials."
   }
   ```
   The human gets one masked field per name. The values are injected as **environment variables**
   into your container when you resume — and into every later task in this run — so read them from
   the environment. They are deliberately **not** in your resume message, because that message is
   written to the session transcript on disk.

   Rules for a credential you have been given: **never echo, print, log, or write it into any file
   under `result/`** — including `result.json`. Name the variable, never its value. If a credential
   you already hold turns out to be rejected by the service, pause again and say so — do not retry
   it in a loop.

## Waiting on long-running work

You run headless (`claude -p`): **the session ends the instant your turn ends, and a
background task will never notify you** — that callback does not exist here. Ending a turn
"waiting for the background job to notify me" abandons the task: state stays `running` and
the run is treated as rotten.

- To wait on anything long-running (a deployment, a test run, a benchmark), poll
  synchronously inside a single Bash call and stay in the turn:
  `end=$((SECONDS+1800)); while [ $SECONDS -lt $end ]; do <check> && break; sleep 30; done`
- Starting something in the background is fine (a server, a job) — but check on it
  yourself, synchronously; never end your turn expecting to be woken when it finishes.
- If the wait genuinely exceeds your time budget, write `state: failed` (or `asked` if a
  human could unblock you) saying exactly what is still pending — an explicit report beats
  silent rot.

## The rule

**A run is complete only when you write `result/result.json` with `"state": "finished"`.**
Your chat/stdout output is *not* the result — `result/` is. If you finish without writing a
valid `result/result.json`, the task is treated as **failed (rotten)** and may be retried or
reaped. Do not fake success.
