#!/usr/bin/env bash
#
# Fordism agent v3 runtime.
#
# One-shot agent worker — claude-code or qwen-code, chosen by AGENT_TYPE — over a
# host-mounted workspace. The container is disposable;
# everything durable lives in /workspace (a host mount): task in, result out, and Claude
# Code's own session/transcript under $HOME/.claude — so a run can be inspected and
# *resumed by a different container* after this one is gone.
#
#   AGENT_MODE=work    -> start the named session, do task/task.md, submit
#   AGENT_MODE=resume  -> resume the SAME session id in a fresh container and continue
#
set -uo pipefail

WS=/workspace
CFG="$WS/config.json"
RES="$WS/result/result.json"
DOCTRINE=/doctrine/CLAUDE.md

# One fixed, deterministic session id + display name for every Fordism agent, so the
# session is a stable handle that any later container can resume.
SID="${FORDISM_SESSION_ID:-f0000000-0000-4000-8000-00000000fa01}"
SNAME="A fordism agent task."

# --- read config (portable, no jq) -----------------------------------------
jget() { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*\"?[^\",}]*\"?" "$CFG" 2>/dev/null \
         | head -1 | sed -E "s/.*:[[:space:]]*\"?([^\"]*)\"?\$/\1/"; }
MODEL="$(jget model)"
TIMEOUT="$(jget timeout)"; TIMEOUT="${TIMEOUT:-600}"   # seconds; default 10 min
MODE="${AGENT_MODE:-work}"
ATYPE="${AGENT_TYPE:-claude-code}"   # claude-code | qwen-code — which CLI drives the task
# Each CLI has its own idea of a sane default model, so the fallback is per-tool.
if [ "$ATYPE" = "qwen-code" ]; then MODEL="${MODEL:-qwen3}"; else MODEL="${MODEL:-sonnet}"; fi

# --- git credentials ------------------------------------------------------
# A credential helper rather than a file: git invokes it with the environment intact, so a
# shelled-out subprocess gets the token without one ever being written to disk. $HOME is the
# host-mounted workspace, which is kept forever and downloadable, so anything written here
# outlives the run. gh needs nothing — it reads GH_TOKEN/GITHUB_TOKEN itself.
#
# Scoped to github.com. An unscoped helper answers for EVERY https remote, so a task that
# clones an internal git server hands it a GitHub token that host has no business holding.
if [ -n "${GITHUB_TOKEN:-}" ]; then
  git config --global credential."https://github.com".helper '!f(){ echo username=x-access-token; echo "password=$GITHUB_TOKEN"; };f'
  git config --global user.name  "${GIT_AUTHOR_NAME:-fordism-agent}"
  git config --global user.email "${GIT_AUTHOR_EMAIL:-fordism-agent@mcark.tw}"
fi

mkdir -p "$WS/result" "$WS/result/logs" "$WS/skills"
# Always stage the built-in fordism-agent skill (the result contract) alongside the task's skills.
cp -r /doctrine/skills/* "$WS/skills/" 2>/dev/null || true
# NB: token usage + the session transcript are NOT written here — the Collector can reap this
# container the instant the agent writes result.json:finished (racing any post-run step). The
# CORE reads them from the host-persisted .claude/.qwen store after collection instead. stdout
# and stderr are streamed to files DURING the run (below) so they survive an early reap.

# read the "state" the AGENT wrote into result/result.json (the completion signal)
rstate() { grep -oE "\"state\"[[:space:]]*:[[:space:]]*\"[a-z]+\"" "$RES" 2>/dev/null | head -1 | grep -oE "[a-z]+\"$" | tr -d '"'; }

now() { date -u +%FT%TZ; }
set_state() { # $1=state  $2=extra-json(optional)
  local st="$1" extra="${2:-}"
  printf '{"state":"%s","sessionId":"%s","sessionName":"%s","model":"%s","mode":"%s","updatedAt":"%s"%s}\n' \
    "$st" "$SID" "$SNAME" "$MODEL" "$MODE" "$(now)" "${extra:+,$extra}" > "$RES"
}
# minimal JSON string-ification of Claude's output for the summary field
jsafe() { printf '%s' "$1" | tr '\n\r\t' '   ' | sed 's/\\/\\\\/g; s/"/\\"/g' | cut -c1-600; }

# Skip Claude Code first-run onboarding (HOME=/workspace -> config at /workspace/.claude.json)
CJ="$WS/.claude.json"
[ -f "$CJ" ] || printf '%s\n' '{"hasCompletedOnboarding":true,"bypassPermissionsModeAccepted":true,"theme":"dark"}' > "$CJ"

echo "[agent] type=$ATYPE mode=$MODE model=$MODEL timeout=${TIMEOUT}s session=$SID"

cd "$WS"
BRIEF="$(cat "$DOCTRINE" 2>/dev/null)"
# Standing instructions from the Agent Template. Carried in the prompt rather than left as a file,
# so they apply whichever CLI drives the task.
INSTRUCTIONS="$(cat "$WS/instructions.md" 2>/dev/null)"
TASK="$(cat "$WS/task/task.md" 2>/dev/null)"
PROMPT="${BRIEF}"
[ -n "$INSTRUCTIONS" ] && PROMPT="${PROMPT}"$'\n\n---\nStanding instructions for this agent (authoritative):\n'"${INSTRUCTIONS}"
PROMPT="${PROMPT}"$'\n\n---\nYour task (task/task.md):\n'"${TASK}"

# The step's whole wall-clock budget, shared by the first run and every self-resume below.
EPOCH_DEADLINE=$(( $(date +%s) + TIMEOUT ))

if [ "$ATYPE" = "qwen-code" ]; then
  # Qwen Code — OpenAI-chat dialect; endpoint/model/key arrive as OPENAI_* env from the launcher.
  set_state running '"phase":"starting","tool":"qwen-code"'
  mkdir -p "$WS/.qwen"
  [ -f "$WS/.qwen/settings.json" ] || printf '%s\n' '{"selectedAuthType":"openai"}' > "$WS/.qwen/settings.json"
  # Qwen Code discovers skills under $HOME/.qwen/skills (HOME=/workspace) and invokes them by
  # description; it does not read /workspace/skills, where the Template stages them. Without this
  # mirror the library is on disk but not model-invocable, and the agent invents what it never read.
  mkdir -p "$WS/.qwen/skills"
  cp -r "$WS"/skills/* "$WS/.qwen/skills/" 2>/dev/null || true
  QP="$PROMPT"; [ "$MODE" = "resume" ] && QP="Continue the task. The human's reply to your question: ${RESUME_PROMPT:-}"
  timeout "$TIMEOUT" qwen --yolo --model "$MODEL" -p "$QP" >"$WS/result/logs/output.log" 2>"$WS/result/logs/errors.log"; rc=$?
elif [ "$MODE" = "resume" ]; then
  set_state running '"phase":"resuming"'
  Q="${RESUME_PROMPT:-You are resuming an earlier session in a brand-new container. Continue the task from where you left off.}"
  timeout "$TIMEOUT" claude -p --resume "$SID" \
          --model "$MODEL" --dangerously-skip-permissions "$Q" \
          >"$WS/result/logs/output.log" 2>"$WS/result/logs/errors.log"; rc=$?
else
  # Claude Code — Anthropic dialect; ANTHROPIC_BASE_URL/AUTH_TOKEN from the launcher.
  set_state running '"phase":"starting","tool":"claude-code"'
  timeout "$TIMEOUT" claude -p --session-id "$SID" --name "$SNAME" \
          --model "$MODEL" --dangerously-skip-permissions "$PROMPT" \
          >"$WS/result/logs/output.log" 2>"$WS/result/logs/errors.log"; rc=$?
fi

# --- self-heal: a clean exit with no terminal result ------------------------
# In -p mode the process exits the instant the model ends its turn. A model that stops to
# "wait for a background job" (that callback does not exist headless) leaves state at
# running with rc 0 — rotten, even though the session can simply be resumed and told to
# finish. Resume it in THIS container every minute until it reports a terminal state or
# the step's timeout budget is spent. Same container on purpose: processes the agent left
# running and their output under /tmp survive between attempts, so a resumed agent can
# read what its background job produced. qwen-code is excluded — no session to resume.
# rc != 0 (crash, or timeout = rc 124) never loops: the budget is spent or the CLI died.
if [ "$ATYPE" != "qwen-code" ]; then
  NUDGE="You ended your turn without writing result/result.json with a terminal state, so your session was resumed. Background tasks are NOT tracked across the restart — check their output files or processes directly, and never end a turn waiting for one: poll with a sleep loop inside a single Bash call instead. Finish the task now and write result/result.json."
  RESUME_COUNT=0
  while [ "$rc" -eq 0 ]; do
    case "$(rstate)" in finished|asked|failed) break ;; esac
    sleep 60
    BUDGET_LEFT=$(( EPOCH_DEADLINE - $(date +%s) ))
    [ "$BUDGET_LEFT" -le 0 ] && break
    RESUME_COUNT=$((RESUME_COUNT + 1))
    echo "[agent] clean exit but state='$(rstate)' — self-resume #$RESUME_COUNT (${BUDGET_LEFT}s of budget left)"
    timeout "$BUDGET_LEFT" claude -p --resume "$SID" \
            --model "$MODEL" --dangerously-skip-permissions "$NUDGE" \
            >>"$WS/result/logs/output.log" 2>>"$WS/result/logs/errors.log"; rc=$?
  done
fi

# Completion contract (fordism-agent skill): the AGENT must write result/result.json with
# state:finished. The wrapper VALIDATES that — it does NOT rubber-stamp stdout. A model that
# only emits junk (no real tool use, never writes result.json) leaves state at "running" -> rotten.
ST="$(rstate)"
if [ "$ST" = "finished" ] || [ "$ST" = "asked" ] || [ "$ST" = "failed" ]; then
  # The agent reported a TERMINAL state, so that is the answer — keep its result.json as-is.
  # The collector maps asked -> ASKED and failed -> FAILED.
  #
  # This is checked BEFORE rc on purpose. rc used to win, which meant a CLI that exited
  # non-zero AFTER the agent had written a valid result overwrote it: completed work was
  # reported as failed and its summary replaced by "exit 124". `timeout` firing a moment
  # after the final write is enough to do it. It also clobbered the agent's own honest
  # "state":"failed" — the reason it gave for giving up was replaced by a generic rotten
  # message. A non-zero rc after a terminal state is worth noting, not worth believing.
  echo "[agent] agent-reported state='$ST' — keeping its result.json"
  [ "$rc" -ne 0 ] && echo "[agent] note: $ATYPE exited rc=$rc after the result was written"
elif [ "$rc" -ne 0 ]; then
  set_state failed "\"rc\":$rc,\"error\":\"$ATYPE exit $rc (crash/timeout; see result/logs/errors.log)\""
  echo "[agent] FAILED rc=$rc"
else
  set_state failed "\"rc\":$rc,\"error\":\"rotten: agent did not write result/result.json state:finished (was '${ST:-running}'; see result/logs/output.log)\""
  echo "[agent] ROTTEN — no valid result/result.json (state='${ST:-running}')"
fi
exit 0
