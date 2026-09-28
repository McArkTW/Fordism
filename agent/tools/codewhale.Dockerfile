# The Fordism agent image for codewhale — the shared base plus this one CLI.
#
# Workspace: $WS/.codewhale
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: <WS>/.codewhale/config.toml — it ignores the ANTHROPIC_BASE_URL it documents
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# Codewhale's install.sh fails with "checksum not found for codewhale-linux-x64" although the
# release's codewhale-artifacts-sha256.txt lists it, so fetch the release binaries and check them here.
RUN cd /tmp && B=https://github.com/Hmbown/CodeWhale/releases/latest/download     && curl -fsSLO "$B/codewhale-artifacts-sha256.txt" && curl -fsSLO "$B/codewhale-linux-x64" && curl -fsSLO "$B/codew-linux-x64"     && grep -E ' (codewhale|codew)-linux-x64$' codewhale-artifacts-sha256.txt | sha256sum -c -     && install -m 755 codewhale-linux-x64 /usr/local/bin/codewhale && install -m 755 codew-linux-x64 /usr/local/bin/codew     && rm -f codewhale-artifacts-sha256.txt codewhale-linux-x64 codew-linux-x64

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
