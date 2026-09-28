# The Fordism agent image for aider — the shared base plus this one CLI.
#
# Workspace: $WS/aider-models.yml, $WS/skills
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: ANTHROPIC_API_BASE — undocumented, honoured by the litellm underneath
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# aider gets its own venv so an agent's `pip install` for a project cannot break it.
# boto3 is aider's Bedrock dependency: aider installs a missing provider library at run time, which
# fails here because the venv is root-owned and the agent is not root.
RUN python3 -m venv /opt/aider     && /opt/aider/bin/pip install --no-cache-dir aider-chat boto3     && ln -s /opt/aider/bin/aider /usr/local/bin/aider

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
