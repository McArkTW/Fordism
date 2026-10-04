# The Fordism agent image for kimi — the shared base plus this one CLI.
#
# Workspace: $WS/.kimi-code/config.toml (KIMI_CODE_HOME)
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: the profile's URL and key, as an ordinary Anthropic endpoint
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# Kimi Code CLI, not the Python kimi-cli: kimi-cli 1.52.0 turned every invocation into a one-line
# "no longer maintained" notice and exit 0, so a task got no agent at all. Pinning an older
# kimi-cli would only freeze a dead package; this npm package is its maintained successor and
# still installs the `kimi` command. Pinned, like codex, so a release cannot change flags under us.
RUN npm install -g @moonshot-ai/kimi-code@2.1.1     && kimi --version

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
