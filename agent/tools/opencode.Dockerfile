# The Fordism agent image for opencode — the shared base plus this one CLI.
#
# Session: `opencode run --continue` — the sole session this container started.
# Everything this tool reads and writes is under /workspace, which is host-mounted and kept.
# With one image per tool nothing collides over $HOME.
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# Pinned, and smoke-run so a wrong-arch or broken install fails the build, not the first task.
# The npm package resolves its own per-arch binary through optionalDependencies, so this is
# multi-arch on amd64 and arm64 with nothing to branch on.
RUN npm install -g opencode-ai@1.18.25     && opencode --version

COPY entrypoint.sh /usr/local/bin/fordism-agent
COPY CLAUDE.md      /doctrine/CLAUDE.md
COPY skills/        /doctrine/skills/
RUN chmod +x /usr/local/bin/fordism-agent

# Claude Code refuses --dangerously-skip-permissions as root, so run as a non-root user.
# Pin that user to uid/gid 1001 so workspace files on the host are owned by a stable non-root uid,
# not uid 1000. HOME points at the mounted workspace so sessions/transcripts persist and resume.
RUN groupmod -g 1001 node && usermod -u 1001 -g 1001 node
USER node
ENV HOME=/workspace
# pip drops console scripts in $HOME/.local/bin, which is not on the default PATH — so
# `pip install pytest && pytest` fails with "command not found" one step after appearing to work.
ENV PATH=/workspace/.local/bin:$PATH
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/fordism-agent"]
