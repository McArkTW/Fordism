#!/usr/bin/env bash
#
# Fordism agent v3 runtime.
#
# One-shot agent worker — claude-code or qwen-code, chosen by AGENT_TYPE — over a
# host-mounted workspace. The container is disposable;
# everything durable lives in /workspace (a host mount): task in, result out, and the CLI's
# own session/transcript under $HOME (.claude for claude-code, .qwen for qwen-code) — so a
# run can be inspected and *resumed by a different container* after this one is gone. Both
# tools resume; the session id is the handle, and it is the same one either way.
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
# Each CLI has its own idea of a sane default model, so the fallback is per-tool. A profile
# almost always sets the model, so this only bites a profile that left it blank.
case "$ATYPE" in
  qwen-code)  MODEL="${MODEL:-qwen3}" ;;
  gemini-cli) MODEL="${MODEL:-gemini-2.5-pro}" ;;
  codex)      MODEL="${MODEL:-gpt-5-codex}" ;;
  opencode)   MODEL="${MODEL:-claude-sonnet-4-5}" ;;
  *)          MODEL="${MODEL:-sonnet}" ;;
esac

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

# Claude Code discovers skills under $HOME/.claude/skills and the project's .claude/skills —
# HOME and cwd are both /workspace, so both resolve to /workspace/.claude/skills. The Template
# stages into /workspace/skills, which is neither: without this mirror the library is on disk but
# never registered, so nothing is model-invocable and the doctrine's "read skills/" is all that
# makes it work. Same fix as the qwen-code mirror below.
# Rebuilt, not merged: on resume the volume already holds the previous run's mirror, and cp -r
# over it would leave a skill the library has since dropped in place for the model to find.
rm -rf "$WS/.claude/skills"
mkdir -p "$WS/.claude/skills"
cp -r "$WS"/skills/* "$WS/.claude/skills/" 2>/dev/null || true

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

# --- the Agent Profile, as every OpenAI-dialect tool needs it ------------------------------
# AGENT_FORMAT is the profile's format, which core has already checked this tool speaks: anthropic
# gets each tool's Anthropic provider, openai-chat its OpenAI-compatible one. P_ROOT drops a
# trailing /v1, for clients that append /v1/messages themselves.
P_URL="${OPENAI_BASE_URL:-}"; P_KEY="${OPENAI_API_KEY:-}"; P_MODEL="${OPENAI_MODEL:-$MODEL}"
P_ROOT="${P_URL%/}"; P_ROOT="${P_ROOT%/v1}"
case "${AGENT_FORMAT:-openai-chat}" in anthropic) P_API=anthropic ;; *) P_API=openai ;; esac

# Core's model proxy. When the launcher sets FORDISM_PROXY, every URL handed to a tool keeps its
# path and swaps its origin for FORDISM_PROXY, so the call reaches core, which forwards it to the
# profile's real origin and records its token usage. The key the tool holds is already a per-task
# token core swaps for the real one. Unset — the default — nothing here changes.
px() { printf '%s%s' "$FORDISM_PROXY" "$(printf '%s' "$1" | sed -E 's#^[A-Za-z][A-Za-z0-9+.-]*://[^/]*##')"; }
if [ -n "${FORDISM_PROXY:-}" ]; then
  [ -n "$P_URL" ] && P_URL="$(px "$P_URL")"
  [ -n "$P_ROOT" ] && P_ROOT="$(px "$P_ROOT")"
  [ -n "${OPENAI_BASE_URL:-}" ] && OPENAI_BASE_URL="$(px "$OPENAI_BASE_URL")"
  [ -n "${ANTHROPIC_BASE_URL:-}" ] && ANTHROPIC_BASE_URL="$(px "$ANTHROPIC_BASE_URL")"
fi

# Mirror the staged skills into the dir a tool discovers them in ($HOME = /workspace), for the
# CLIs that invoke skills by description. Rebuilt, not merged, so a dropped skill does not linger.
mirror_skills() {  # $1 = the tool's skills dir under $WS
  rm -rf "$WS/$1"
  mkdir -p "$WS/$1"
  cp -r "$WS"/skills/* "$WS/$1/" 2>/dev/null || true
}

case "$ATYPE" in
  qwen-code)
    # Qwen Code — OpenAI-chat dialect; endpoint/model/key arrive as OPENAI_* env from the launcher.
    mkdir -p "$WS/.qwen"
    [ -f "$WS/.qwen/settings.json" ] || printf '%s\n' '{"selectedAuthType":"openai"}' > "$WS/.qwen/settings.json"
    mirror_skills ".qwen/skills"
    ;;
  gemini-cli)
    # Gemini CLI — Google dialect; GEMINI_API_KEY (+ optional GOOGLE_GEMINI_BASE_URL) from launcher.
    mkdir -p "$WS/.gemini"
    mirror_skills ".gemini/skills"
    ;;
  codex)
    # Codex — OpenAI dialect. It does not read OPENAI_BASE_URL; a custom endpoint is a model
    # provider in ~/.codex/config.toml, keyed to the OPENAI_API_KEY the launcher set. Written each
    # start so an edited profile takes effect. Skills: none of its own — the doctrine prompt's
    # "read skills/" is what puts the staged library in reach.
    #
    # wire_api follows the profile's AGENT_FORMAT, and defaults to "responses": current codex has
    # dropped chat-completions, so an endpoint serving only /chat/completions 404s every call. It
    # was hardcoded to "chat" here, which is the wrong default for exactly that reason — but a
    # profile that really does point at a chat-only gateway can still say openai-chat and get it.
    case "${AGENT_FORMAT:-openai-responses}" in openai-chat) CX_WIRE=chat ;; *) CX_WIRE=responses ;; esac
    mkdir -p "$WS/.codex"
    {
      printf 'model = "%s"\n' "${MODEL}"
      printf 'model_provider = "fordism"\n'
      printf '[model_providers.fordism]\n'
      printf 'name = "fordism"\n'
      printf 'base_url = "%s"\n' "${OPENAI_BASE_URL}"
      printf 'env_key = "OPENAI_API_KEY"\n'
      printf 'wire_api = "%s"\n' "${CX_WIRE}"
    } > "$WS/.codex/config.toml"
    ;;
  aider)
    # aider sees only files that are in git, so the workspace is made a repo and its inputs staged.
    # /workspace is owned by the host, not this user: without safe.directory git — and aider's
    # GitPython under it — refuses it as "dubious ownership". Exported so aider inherits it.
    export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0="$WS"
    git init -q "$WS" && git -C "$WS" add -A -- $(cd "$WS" && ls -d task skills memory 2>/dev/null) \
      || echo "[agent] aider: staging the workspace in git failed; aider will see no files" >&2
    AIDER_SETTINGS=""; ANTHROPIC_KEYS=""
    if [ "$P_API" = anthropic ]; then
      # aider's docs list only ANTHROPIC_API_KEY, but it runs on litellm, which honours
      # ANTHROPIC_API_BASE. Without it aider ignores the profile's endpoint and reaches
      # api.anthropic.com, failing there as "invalid x-api-key".
      printf -- '- name: anthropic/%s\n  use_temperature: false\n  cache_control: true\n' "$P_MODEL" \
        > "$WS/aider-models.yml"
      AIDER_SETTINGS=1; ANTHROPIC_KEYS=1
    fi
    ;;
  crush)
    # crushrc is Bash that crush evaluates, so "$PROFILE_API_KEY" is read from the environment
    # rather than written here. Its anthropic type appends /v1/messages itself, so it gets the root.
    #
    # request-timeout: crush aborts a stream after 60 s with nothing arriving (an idle timer reset
    # on every part). A model sends nothing while it thinks, so a long think was cut off — and crush
    # then spun at 100% CPU until the task timed out. 600 s lets a slow reply finish and still ends
    # a truly stuck stream.
    mkdir -p "$WS/.config/crush"
    if [ "$P_API" = anthropic ]; then CTYPE=anthropic; CBASE="$P_ROOT"; else CTYPE=openai-compat; CBASE="$P_URL"; fi
    printf 'provider add profile --type %s --base-url "%s" --api-key "$PROFILE_API_KEY"\nmodel add "profile/%s" --context-window 200000 --default-max-tokens 32000\nmodel large "profile/%s"\noption request-timeout 600\n' \
      "$CTYPE" "$CBASE" "$P_MODEL" "$P_MODEL" > "$WS/.config/crush/crushrc"
    ;;
  continue)
    # apiBase goes in for BOTH providers. Omitting it for anthropic pinned that provider to
    # api.anthropic.com, which answered "invalid x-api-key" for any other endpoint's key. Continue
    # appends /messages, not /v1/messages, so the /v1 belongs on the base.
    #
    # apiKey is a secrets reference, not the key: Continue resolves ${{ secrets.NAME }} from the
    # environment, so the key never reaches this file — which is in the workspace. The field must be
    # present (apiKey: null is refused), and omitting it is worse: the loader fills the value with
    # the string "undefined" and sends that as the key.
    if [ "$P_API" = anthropic ]; then CBASE="    apiBase: $P_ROOT/v1"$'\n'; else CBASE="    apiBase: $P_URL"$'\n'; fi
    printf 'name: fordism\nversion: 0.0.1\nschema: v1\nmodels:\n  - name: fordism\n    provider: %s\n    model: %s\n    apiKey: %s\n%s' \
      "$P_API" "$P_MODEL" '${{ secrets.PROFILE_API_KEY }}' "$CBASE" > "$WS/.continue.yaml"
    ;;
  dsh)
    export DSH_HOME="$WS/.dsh"; mkdir -p "$DSH_HOME"
    if [ "$P_API" = anthropic ]; then DAPI=anthropic-messages; DBASE="$P_ROOT"; else DAPI=openai-completions; DBASE="$P_URL"; fi
    # apiKeyEnv names an environment variable, so the key stays out of the workspace.
    printf 'llm-pi-ai:\n  providers:\n    profile:\n      api: %s\n      baseURL: %s\n      apiKeyEnv: PROFILE_API_KEY\n      models:\n        - id: %s\n' \
      "$DAPI" "$DBASE" "$P_MODEL" > "$DSH_HOME/settings.yaml"
    printf -- '- id: agent-default-model\n  config:\n    provider: profile\n    model: %s\n' "$P_MODEL" \
      > "$DSH_HOME/cordis.patch.yml"
    ;;
  openclaw)
    # "${PROFILE_API_KEY}" is substituted from the environment when the config loads.
    # reasoning:true on the Anthropic route: false turned openclaw's thinking off entirely, so it
    # sent thinking disabled; true gives its documented Claude default — the same baseline
    # claude-code sends. maxTokens 64000 matches that baseline's budget for thinking plus answer.
    if [ "$P_API" = anthropic ]; then
      OAPI=anthropic-messages; OBASE="$P_ROOT"; OREASON=true; OMAX=64000
    else
      OAPI=openai-completions; OBASE="$P_URL"; OREASON=false; OMAX=32000
    fi
    printf '{models:{providers:{profile:{baseUrl:"%s",apiKey:"${PROFILE_API_KEY}",api:"%s",models:[{id:"%s",name:"%s",reasoning:%s,input:["text"],cost:{input:0,output:0,cacheRead:0,cacheWrite:0},contextWindow:200000,maxTokens:%s}]}}}}\n' \
      "$OBASE" "$OAPI" "$P_MODEL" "$P_MODEL" "$OREASON" "$OMAX" > "$WS/.openclaw.json"
    ;;
  hermes)
    if [ "$P_API" = anthropic ]; then
      # A named provider in its own config, not --provider anthropic with ANTHROPIC_BASE_URL: that
      # reached api.anthropic.com. The name matters — `bedrock` is reserved and routes to AWS
      # session credentials — so a neutral one is used and the endpoint is treated as the
      # Anthropic-compatible gateway it is. api_key is a ${VAR} reference, so the key never reaches
      # this file. skills.external_dirs: hermes looks under $HERMES_HOME/skills only, so without it
      # the workspace's skills are "not found".
      export HOME="$WS/.hermes-home"; mkdir -p "$HOME/.hermes"
      printf 'skills:\n  external_dirs: [%s/skills]\nproviders:\n  profile:\n    base_url: %s/v1\n    api_key: ${PROFILE_API_KEY}\n    models:\n      %s: {}\n' \
        "$WS" "$P_ROOT" "$P_MODEL" > "$HOME/.hermes/config.yaml"
    fi
    ;;
  deepagents)
    mkdir -p "$WS/.deepagents"
    if [ "$P_API" = anthropic ]; then DBASE="$P_ROOT"; else DBASE="$P_URL"; fi
    # max_tokens: langchain-anthropic takes it from a model profile matched by exact name, and an
    # unrecognised id matches none, so it fell back to 4096. A reply that spent those on thinking
    # ended with no tool call, which the agent loop reads as done — "Task completed", no result.
    printf '[models.providers.%s]\nbase_url = "%s"\napi_key_env = "PROFILE_API_KEY"\nmodels = ["%s"]\n\n[models.providers.%s.params]\nmax_tokens = 64000\n' \
      "$P_API" "$DBASE" "$P_MODEL" "$P_API" > "$WS/.deepagents/config.toml"
    ;;
  kimi)
    # default_thinking = true: false (kimi's own default) sends thinking disabled. True with no
    # thinking capability declared sends no thinking field at all, so the model thinks at its own
    # default. The key sits inline here, which is why .kimi.toml is scrubbed after the task.
    if [ "$P_API" = anthropic ]; then KTYPE=anthropic; KBASE="$P_ROOT"; else KTYPE=openai_legacy; KBASE="$P_URL"; fi
    printf 'default_thinking = true\n\n[providers.profile]\ntype = "%s"\nbase_url = "%s"\napi_key = "%s"\n\n[models.profile]\nprovider = "profile"\nmodel = "%s"\nmax_context_size = 200000\n' \
      "$KTYPE" "$KBASE" "$P_KEY" "$P_MODEL" > "$WS/.kimi.toml"
    ;;
  codewhale)
    if [ "$P_API" = anthropic ]; then
      # Its docs list ANTHROPIC_BASE_URL but it does not honour it — pointed at a local server, no
      # request ever arrived. The config file is honoured. api_key_env names an environment
      # variable, so the key never lands in the file. Proxied, core's route is plain http on the
      # launcher network, which codewhale refuses for any host but loopback unless allowed.
      mkdir -p "$WS/.codewhale"
      printf 'provider = "anthropic"\n\n[providers.anthropic]\napi_key_env = "PROFILE_API_KEY"\nbase_url = "%s"\nmodel = "%s"\n%s' \
        "$P_ROOT" "$P_MODEL" "${FORDISM_PROXY:+allow_insecure_http = true
}" > "$WS/.codewhale/config.toml"
    fi
    ;;
  reasonix)
    # The one tool that cannot take its key from the environment: api_key_env names a slot in
    # <REASONIX_HOME>/.env, not an OS variable. The key therefore lands in the workspace, which is
    # host-mounted and kept — which is exactly what FORDISM_CREDENTIAL_FILES and CredentialScrub
    # exist for. Keep .reasonix/.env in that list.
    export REASONIX_HOME="$WS/.reasonix"; mkdir -p "$REASONIX_HOME"
    if [ "$P_API" = anthropic ]; then RAPI=anthropic; RBASE="$P_ROOT"; else RAPI=openai; RBASE="$P_URL"; fi
    printf 'PROFILE_API_KEY=%s\n' "$P_KEY" > "$REASONIX_HOME/.env"
    printf '{"providers":{"profile":{"type":"%s","base_url":"%s","api_key_env":"PROFILE_API_KEY","models":[{"id":"%s"}]}}}\n' \
      "$RAPI" "$RBASE" "$P_MODEL" > "$REASONIX_HOME/config.json"
    ;;
  opencode)
    # opencode — a provider in opencode.json, its key read from OPENAI_API_KEY. Which provider
    # depends on what the profile's endpoint actually serves (AGENT_FORMAT), because opencode
    # genuinely speaks both: the built-in anthropic provider for an Anthropic Messages endpoint,
    # the openai-compatible one otherwise. The AI SDK's anthropic provider wants the /v1 on the
    # base URL, so it is added here rather than expected in the profile.
    if [ "${AGENT_FORMAT:-openai-chat}" = "anthropic" ]; then
      A_ROOT="${OPENAI_BASE_URL%/}"; A_ROOT="${A_ROOT%/v1}"
      mkdir -p "$WS/.config/opencode"
      printf '{"provider":{"anthropic":{"options":{"baseURL":"%s/v1","apiKey":"{env:OPENAI_API_KEY}"},"models":{"%s":{}}}}}\n' \
        "${A_ROOT}" "${MODEL}" > "$WS/opencode.json"
      OPENCODE_PROVIDER=anthropic
    else
      printf '{"provider":{"fordism":{"npm":"@ai-sdk/openai-compatible","options":{"baseURL":"%s"},"models":{"%s":{}}}}}\n' \
        "${OPENAI_BASE_URL}" "${MODEL}" > "$WS/opencode.json"
      OPENCODE_PROVIDER=fordism
    fi
    mirror_skills ".config/opencode/skills"
    ;;
esac

# --- how each CLI is driven -------------------------------------------------
# One place that knows the two things that differ between the tools: the flag that names a new
# session, and the flag that resumes one. Both keep their session store under $HOME, which is the
# host-mounted /workspace, so the session outlives this container either way — that is what lets a
# question be answered, a rework be resumed, and the self-heal loop below exist at all.
#
# qwen-code was one-shot here until v1.1. It does have --session-id and --resume; what it also has
# is --chat-recording, and its own help says that without it "--continue/--resume will not work".
# It is passed explicitly rather than relied on as a default, because a default that flips turns
# every resume in this file into a silent new session.
# Each tool: how to START a new session, and how to RESUME one. claude-code and qwen-code can be
# handed a fixed session id ($SID), so resume targets it exactly. gemini-cli, codex and opencode
# generate their own id and cannot be told one — but a task container holds exactly one session, so
# "the latest / most recent / continue" resolves to that one. Each keeps its store under $HOME
# (=/workspace), so the session survives this container for a later one to resume.
# Whether this tool keeps a session a LATER container can pick up. The five that do are the five
# Fordism shipped with; the tools added since are one-shot — they have no session store under
# $HOME to resume, so `resume` for them re-runs the task with the human's answer appended, which
# works because the workspace still holds everything the first attempt did. Getting this list wrong
# in the generous direction is the expensive mistake: a resume flag a tool does not honour starts a
# silent NEW session, and the agent then answers a question it cannot see the context for.
sessioned() {
  case "$ATYPE" in
    claude-code|qwen-code|gemini-cli|codex|opencode) return 0 ;;
    *) return 1 ;;
  esac
}

agent_start() {   # $1 = prompt   $2 = seconds of budget
  case "$ATYPE" in
    qwen-code)  timeout "$2" qwen --yolo --chat-recording --session-id "$SID" --model "$MODEL" -p "$1" ;;
    gemini-cli) timeout "$2" gemini --approval-mode yolo --model "$MODEL" -p "$1" ;;
    codex)      timeout "$2" codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check \
                        --model "$MODEL" "$1" ;;
    opencode)   timeout "$2" opencode run --model "${OPENCODE_PROVIDER:-fordism}/$MODEL" "$1" ;;

    # --- one-shot tools. Each is handed the profile through its own configuration, written above.
    aider)      timeout "$2" env ${P_API:+OPENAI_API_BASE="$P_URL"} \
                        ${ANTHROPIC_KEYS:+ANTHROPIC_API_KEY="$P_KEY"} ANTHROPIC_API_BASE="$P_ROOT" \
                        aider --model "$P_API/$P_MODEL" \
                        ${AIDER_SETTINGS:+--model-settings-file "$WS/aider-models.yml"} \
                        --no-auto-commits --no-dirty-commits --no-gitignore --map-tokens 4096 \
                        --yes-always --no-check-update --no-show-release-notes --analytics-disable --no-pretty \
                        --read "$WS/skills/fordism-agent/SKILL.md" --message "$1" </dev/null ;;
    goose)      if [ "$P_API" = anthropic ]; then
                  # GOOSE_THINKING_EFFORT: unset, goose treats the effort as off and sends thinking
                  # disabled, unlike the model's own default. high matches claude-code's baseline.
                  timeout "$2" env GOOSE_DISABLE_KEYRING=1 GOOSE_MODE=auto GOOSE_PROVIDER=anthropic \
                          GOOSE_THINKING_EFFORT=high GOOSE_MODEL="$P_MODEL" \
                          ANTHROPIC_API_KEY="$P_KEY" ANTHROPIC_HOST="$P_ROOT" \
                          goose run --with-builtin developer -t "$1" </dev/null
                else
                  # goose wants host and path apart: http://h:11434/v1 -> host + v1/chat/completions.
                  BASE="${OPENAI_BASE_URL%/}"
                  GOOSE_HOST="$(printf '%s' "$BASE" | sed -E 's#^(https?://[^/]+).*#\1#')"
                  GOOSE_PATH="${BASE#"$GOOSE_HOST"}"; GOOSE_PATH="${GOOSE_PATH#/}"
                  timeout "$2" env GOOSE_DISABLE_KEYRING=1 GOOSE_MODE=auto GOOSE_PROVIDER=openai \
                          GOOSE_MODEL="$OPENAI_MODEL" OPENAI_HOST="$GOOSE_HOST" \
                          OPENAI_BASE_PATH="${GOOSE_PATH:+$GOOSE_PATH/}chat/completions" \
                          goose run --with-builtin developer -t "$1" </dev/null
                fi ;;
    copilot)    # BYOK against the profile's endpoint; COPILOT_OFFLINE keeps it from needing a
                # GitHub login. GH_TOKEN/GITHUB_TOKEN are cleared for this one process: the Copilot
                # CLI reads them as its OWN login and exits rc 1 in about four seconds on a classic
                # PAT ("Classic Personal Access Tokens (ghp_) are not supported by Copilot"). It is
                # a startup validation of a token it never uses, and it killed every copilot task
                # whose template granted GITHUB_TOKEN. `git` and `gh` in the agent's own shell still
                # see the token; only copilot is started without it.
                timeout "$2" env -u GH_TOKEN -u GITHUB_TOKEN \
                        COPILOT_PROVIDER_BASE_URL="$OPENAI_BASE_URL" COPILOT_PROVIDER_API_KEY="$OPENAI_API_KEY" \
                        COPILOT_MODEL="$OPENAI_MODEL" COPILOT_OFFLINE=true \
                        copilot -p "$1" --allow-all --no-ask-user -s --model "$OPENAI_MODEL" </dev/null ;;
    pi)         timeout "$2" env HOME="$WS/.pi" PROFILE_KEY="$P_KEY" \
                        pi -p --provider profile --model "$P_MODEL" --mode json "$1" </dev/null ;;
    crush)      timeout "$2" env PROFILE_API_KEY="$P_KEY" CRUSH_DISABLE_PROVIDER_AUTO_UPDATE=1 \
                        CRUSH_DISABLE_METRICS=1 crush run -q -m "profile/$P_MODEL" "$1" </dev/null ;;
    cline)      timeout "$2" env CLINE_API_KEY="$P_KEY" CLINE_BASE_URL="$P_URL" \
                        cline --yolo --model "$P_MODEL" -p "$1" </dev/null ;;
    continue)   timeout "$2" env PROFILE_API_KEY="$P_KEY" cn -p "$1" --config "$WS/.continue.yaml" --auto </dev/null ;;
    openhands)  # LLM_* apply only with --override-with-envs; --exit-without-confirmation is what
                # makes it exit rather than wait for a human once the task is done.
                if [ "$P_API" = anthropic ]; then LBASE="$P_ROOT"; else LBASE="$P_URL"; fi
                timeout "$2" env LLM_MODEL="$P_API/$P_MODEL" LLM_API_KEY="$P_KEY" ${LBASE:+LLM_BASE_URL="$LBASE"} \
                        openhands --headless -t "$1" --override-with-envs --exit-without-confirmation </dev/null ;;
    dsh)        timeout "$2" env PROFILE_API_KEY="$P_KEY" DSH_PERMISSION_MODE=danger-full-access \
                        dsh --profile headless "$1" </dev/null ;;
    openclaw)   # --timeout 9999: `agent exec` carries its own deadline, default 600 s, and killed
                # itself mid tool call with no result. Fordism already bounds the run with $TIMEOUT.
                timeout "$2" env PROFILE_API_KEY="$P_KEY" openclaw agent exec --config "$WS/.openclaw.json" \
                        --cwd "$WS" --timeout 9999 --model "profile/$P_MODEL" "$1" </dev/null ;;
    hermes)     if [ "$P_API" = anthropic ]; then
                  timeout "$2" env PROFILE_API_KEY="$P_KEY" hermes -z "$1" --provider profile -m "$P_MODEL" --yolo </dev/null
                else
                  timeout "$2" hermes -z "$1" -m "$P_MODEL" --yolo </dev/null
                fi ;;
    deepagents) timeout "$2" env PROFILE_API_KEY="$P_KEY" dcode -n "$1" --model "$P_API:$P_MODEL" -S all \
                        --allow-fs-tools ls,read_file,write_file,edit_file,glob,grep,execute </dev/null ;;
    kimi)       timeout "$2" kimi --config-file "$WS/.kimi.toml" -m profile -w "$WS" --print --yolo --prompt "$1" </dev/null ;;
    codewhale)  timeout "$2" env PROFILE_API_KEY="$P_KEY" ANTHROPIC_MODEL="$P_MODEL" \
                        codewhale --provider "$P_API" exec --auto "$1" </dev/null ;;
    reasonix)   timeout "$2" reasonix run --provider profile --model "$P_MODEL" --yes "$1" </dev/null ;;
    jcode)      timeout "$2" env JCODE_API_KEY="$P_KEY" JCODE_BASE_URL="$P_URL" \
                        jcode --yolo -m "$P_MODEL" -p "$1" </dev/null ;;
    grok)       timeout "$2" env GROK_API_KEY="$P_KEY" GROK_BASE_URL="$P_URL" \
                        grok --yolo -m "$P_MODEL" -p "$1" </dev/null ;;

    *)          timeout "$2" claude -p --session-id "$SID" --name "$SNAME" \
                        --model "$MODEL" --dangerously-skip-permissions "$1" ;;
  esac
}

agent_resume() {  # $1 = prompt   $2 = seconds of budget
  # A tool with no session cannot be resumed, so it is started afresh with the WHOLE task plus the
  # human's answer. That is not as good — the agent does not remember its own reasoning — but the
  # workspace still holds everything it wrote, so it picks up from artefacts rather than from
  # nothing. Silently passing a resume flag such a tool ignores would be worse: a brand-new session
  # handed only the answer, with no idea what the question was.
  if ! sessioned; then
    agent_start "${PROMPT}"$'\n\n---\nThis task was paused on a question you asked; the workspace holds what you had already done. The human answered:\n'"$1" "$2"
    return $?
  fi
  case "$ATYPE" in
    qwen-code)  timeout "$2" qwen --yolo --chat-recording --resume "$SID" --model "$MODEL" -p "$1" ;;
    gemini-cli) timeout "$2" gemini --approval-mode yolo --model "$MODEL" -r latest -p "$1" ;;
    codex)      timeout "$2" codex exec resume --last --dangerously-bypass-approvals-and-sandbox \
                        --skip-git-repo-check --model "$MODEL" "$1" ;;
    opencode)   timeout "$2" opencode run --continue --model "${OPENCODE_PROVIDER:-fordism}/$MODEL" "$1" ;;
    *)          timeout "$2" claude -p --resume "$SID" \
                        --model "$MODEL" --dangerously-skip-permissions "$1" ;;
  esac
}

if [ "$MODE" = "resume" ]; then
  set_state running "\"phase\":\"resuming\",\"tool\":\"$ATYPE\""
  Q="${RESUME_PROMPT:-You are resuming an earlier session in a brand-new container. Continue the task from where you left off.}"
  agent_resume "$Q" "$TIMEOUT" >"$WS/result/logs/output.log" 2>"$WS/result/logs/errors.log"; rc=$?
else
  set_state running "\"phase\":\"starting\",\"tool\":\"$ATYPE\""
  agent_start "$PROMPT" "$TIMEOUT" >"$WS/result/logs/output.log" 2>"$WS/result/logs/errors.log"; rc=$?
fi

# --- self-heal: a clean exit with no terminal result ------------------------
# In -p mode the process exits the instant the model ends its turn. A model that stops to
# "wait for a background job" (that callback does not exist headless) leaves state at
# running with rc 0 — rotten, even though the session can simply be resumed and told to
# finish. Resume it in THIS container every minute until it reports a terminal state or
# the step's timeout budget is spent. Same container on purpose: processes the agent left
# running and their output under /tmp survive between attempts, so a resumed agent can
# read what its background job produced.
# rc != 0 (crash, or timeout = rc 124) never loops: the budget is spent or the CLI died.
#
# This used to exclude qwen-code, on the grounds that it had no session to resume. It does — see
# agent_resume above — so a qwen agent that ends its turn early is now nudged to finish rather than
# reaped as rotten, which is the same deal claude-code has had all along.
NUDGE="You ended your turn without writing result/result.json with a terminal state, so your session was resumed. Background tasks are NOT tracked across the restart — check their output files or processes directly, and never end a turn waiting for one: poll with a sleep loop inside a single Bash call instead. Finish the task now and write result/result.json."
RESUME_COUNT=0
while [ "$rc" -eq 0 ]; do
  case "$(rstate)" in finished|asked|failed) break ;; esac
  # The budget is checked BEFORE the pacing sleep as well as after: a run that exits clean
  # with its budget already spent used to idle a full minute to learn there was nothing left
  # to resume into. The sleep is also capped at what remains, for the same reason.
  BUDGET_LEFT=$(( EPOCH_DEADLINE - $(date +%s) ))
  [ "$BUDGET_LEFT" -le 0 ] && break
  if [ "$BUDGET_LEFT" -lt 60 ]; then sleep "$BUDGET_LEFT"; else sleep 60; fi
  BUDGET_LEFT=$(( EPOCH_DEADLINE - $(date +%s) ))
  [ "$BUDGET_LEFT" -le 0 ] && break
  RESUME_COUNT=$((RESUME_COUNT + 1))
  echo "[agent] clean exit but state='$(rstate)' — self-resume #$RESUME_COUNT (${BUDGET_LEFT}s of budget left)"
  agent_resume "$NUDGE" "$BUDGET_LEFT" \
          >>"$WS/result/logs/output.log" 2>>"$WS/result/logs/errors.log"; rc=$?
done

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
