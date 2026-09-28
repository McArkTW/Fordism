# The Fordism agent image for pi — the shared base plus this one CLI.
#
# Workspace: $WS/.pi
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: the profile's URL and key, as an ordinary Anthropic endpoint
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# @earendil-works, not @mariozechner: pi moved there in May 2026 and the old name is deprecated on npm
# ("please use @earendil-works/pi-coding-agent instead") and frozen at 0.73.1. 0.85.1 keeps Claude's signed
# thinking blocks with empty text (pi-ai api/anthropic-messages.ts), which 0.73.1 dropped. Pinned because
# the entrypoint's models.json follows this version's docs (docs/models.md).
RUN npm install -g @earendil-works/pi-coding-agent@0.85.1

# Patch: 0.85.1 labels a reply with the model Bedrock reports (claude-sonnet-5) instead of the requested id
# (global.anthropic.claude-sonnet-5), so pi-ai's same-model check fails and signed thinking is dropped on
# replay. Backport of upstream 1283afd0d ("preserve thinking replay through renamed Anthropic models",
# 2026-09-17, not yet released) — drop this when a release after 0.85.1 is pinned. Unlike upstream, fallback
# pricing still keys on the requested model. The build fails if the target text moved.
COPY tools/patches/apply.py tools/patches/pi-thinking-replay.json /tmp/patch/
RUN python3 /tmp/patch/apply.py /tmp/patch/pi-thinking-replay.json && rm -rf /tmp/patch

COPY entrypoint.sh /usr/local/bin/fordism-agent
COPY CLAUDE.md      /doctrine/CLAUDE.md
COPY skills/        /doctrine/skills/
RUN chmod +x /usr/local/bin/fordism-agent

# Claude Code refuses --dangerously-skip-permissions as root, so run as a non-root user.
# Pin that user to uid/gid 1001 so workspace files on the host are owned by `lab` (uid 1001),
# not uid 1000. HOME points at the mounted workspace so sessions/transcripts persist and resume.
RUN groupmod -g 1001 node && usermod -u 1001 -g 1001 node
USER node
ENV HOME=/workspace
# pip drops console scripts in $HOME/.local/bin, which is not on the default PATH — so
# `pip install pytest && pytest` fails with "command not found" one step after appearing to work.
ENV PATH=/workspace/.local/bin:$PATH
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/fordism-agent"]
