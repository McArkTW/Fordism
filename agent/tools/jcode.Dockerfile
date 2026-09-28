# The Fordism agent image for jcode — the shared base plus this one CLI.
#
# Workspace: $WS/.jcode-home
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: jcode --provider bedrock, AWS_BEARER_TOKEN_BEDROCK + AWS_REGION (see README: tool calls do not execute)
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# The installer defaults to $HOME. HOME is moved to /opt for the install so nothing lands in
# /root, which the agent user cannot read.
RUN mkdir -p /opt/jcode     && curl -fsSL https://jcode.sh/install | HOME=/opt/jcode JCODE_INSTALL_DIR=/usr/local/bin bash     && chmod -R a+rX /opt/jcode

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
