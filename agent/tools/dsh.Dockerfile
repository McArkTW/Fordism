# The Fordism agent image for dsh — the shared base plus this one CLI.
#
# Workspace: $WS/.dsh
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: the profile's URL and key, as an ordinary Anthropic endpoint
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# Pinned: the thinking-replay patch below targets 0.1.5-rc.1's source.
RUN npm install -g @deepseek-ai/dsh@0.1.5-rc.1

# Patch: a replayed reply is labelled with Bedrock's response model name, fails pi-ai's same-model check,
# and its signed thinking is dropped. See "Thinking replay" in the README. The build fails if the target moved.
COPY tools/patches/apply.py tools/patches/dsh-thinking-replay.json /tmp/patch/
RUN python3 /tmp/patch/apply.py /tmp/patch/dsh-thinking-replay.json && rm -rf /tmp/patch

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
