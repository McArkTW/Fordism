# The Fordism agent image for hermes — the shared base plus this one CLI.
#
# Workspace: $WS/.hermes-home
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: a provider named "profile" in its config — the name "bedrock" is reserved for AWS session credentials
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# Pinned: the thinking-replay patch below targets 0.19.0's source.
RUN export UV_TOOL_DIR=/opt/uv-tools UV_TOOL_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_DIR=/opt/uv-python     && uv tool install 'hermes-agent[anthropic,bedrock]==0.19.0' --python 3.12     && chmod -R a+rX /opt/uv-tools /opt/uv-python

# Patch: hermes strips signed thinking from history for any non-anthropic.com endpoint, so Claude loses its
# reasoning every turn. See "Thinking replay" in the README. The build fails if the target text moved.
COPY tools/patches/apply.py tools/patches/hermes-thinking-replay.json /tmp/patch/
RUN python3 /tmp/patch/apply.py /tmp/patch/hermes-thinking-replay.json && rm -rf /tmp/patch

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
