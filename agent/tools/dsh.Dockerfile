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

# Pinned, and to 0.2.0-rc.2 or later: 0.1.5-rc.1 asked for its ~70 @deepseek-ai/dsh-* parts by caret
# range, so a fresh install mixed it with newer parts whose plugin tree it could not load (Cordis
# failed on dsh-sandbox-local). From 0.2.0-rc.2 every part is pinned exactly, so the tree is the one
# that release was tested with.
# No thinking-replay patch any more: 0.2.0-rc.2 stores the requested model id as the replay
# identity, which is what the patch did — a reply Bedrock labels with another name keeps its thinking.
RUN npm install -g @deepseek-ai/dsh@0.2.0-rc.2

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
