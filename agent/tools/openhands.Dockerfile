# The Fordism agent image for openhands — the shared base plus this one CLI.
#
# Workspace: no tool-specific directory
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: the profile's URL and key, as an ordinary Anthropic endpoint
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# OpenHands is Python 3.12-only, so it gets its own uv-managed interpreter rather than the image's.
RUN UV_TOOL_DIR=/opt/uv-tools UV_TOOL_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_DIR=/opt/uv-python \
       uv tool install openhands --python 3.12 \
    && chmod -R a+rX /opt/uv-tools /opt/uv-python
# OpenHands decides prompt caching by matching the model id against a hard-coded list that stops at
# claude-sonnet-4-6. An unlisted id caches nothing and says nothing: one plan run re-sent 1.48M
# input tokens at full price. Add the newer ids; drop this patch once upstream ships them.
RUN F=/opt/uv-tools/openhands/lib/python3.12/site-packages/openhands/sdk/llm/utils/model_features.py \
    && sed -i 's/^PROMPT_CACHE_MODELS: list\[str\] = \[/PROMPT_CACHE_MODELS: list[str] = [\n    "claude-sonnet-5",\n    "claude-opus-5",\n    "claude-fable-5",/' $F \
    && grep -q '"claude-sonnet-5"' $F

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
