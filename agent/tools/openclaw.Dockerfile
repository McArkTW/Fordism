# The Fordism agent image for openclaw — the shared base plus this one CLI.
#
# Workspace: $WS/.openclaw.json
#   Everything this tool reads and writes is under /workspace, which is host-mounted and
#   kept. Nothing goes to /tmp: with one image per tool nothing collides over $HOME.
# Bedrock: the profile's URL and key, as an ordinary Anthropic endpoint
#
# ARG BASE lets CI pin the base tag it just built.
ARG BASE=fordism/fordism-agent-base:local
FROM ${BASE}

# OpenClaw refuses Node 22 ("requires Node >=24.16.0 <25 || >=26.1.0"), so it gets its own Node 24
# under /opt and a launcher that runs it there; the image's Node stays 22 for every other CLI.
RUN V=$(curl -fsSL https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt | grep -oE 'node-v24\.[0-9.]+-linux-x64\.tar\.gz' | head -1) \
    && curl -fsSL "https://nodejs.org/dist/latest-v24.x/$V" | tar -xz -C /opt \
    && mv "/opt/${V%.tar.gz}" /opt/node24 \
    && PATH=/opt/node24/bin:$PATH npm install -g --prefix /opt/openclaw openclaw@latest \
       --allow-scripts=openclaw,@google/genai,koffi,tree-sitter-bash,protobufjs \
    && printf '#!/bin/sh\nexec /opt/node24/bin/node /opt/openclaw/lib/node_modules/openclaw/openclaw.mjs "$@"\n' > /usr/local/bin/openclaw \
    && chmod 755 /usr/local/bin/openclaw

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
