# The Fordism agent image for grok — the shared base plus this one CLI.
#
# Workspace: $WS/.grok
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: api_key only in config.toml; x-api-key as well gives 400 "Cannot provide both"
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

RUN mkdir -p /opt/grok     && curl -fsSL https://x.ai/cli/install.sh | HOME=/opt/grok GROK_BIN_DIR=/opt/grok/bin bash     && ln -sf /opt/grok/bin/grok /usr/local/bin/grok     && chmod -R a+rX /opt/grok

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
