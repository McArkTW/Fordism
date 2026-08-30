# The Fordism agent — a one-shot CLI agent (claude-code, qwen-code, gemini-cli, codex or
# opencode, chosen at run time by AGENT_TYPE) over a host-mounted workspace. The launcher runs
# one container per task; it reads task/task.md, does the work, writes result/, and exits.
# The model backend is chosen at run time by env, per the tool's dialect: ANTHROPIC_* for
# claude-code, OPENAI_* for qwen-code/codex/opencode, GEMINI_API_KEY for gemini-cli — each
# pointing straight at the resolved endpoint, no gateway.
FROM node:22-slim

# BuildKit sets this to the arch being built for (amd64 / arm64). Every apt package and npm
# install below is architecture-neutral; the two GitHub release tarballs are not, and the two
# vendors spell the arch differently — gh uses Go's name, PowerShell uses its own.
ARG TARGETARCH

# openssh-client is REQUIRED: skills that reach remote machines shell out to `ssh`/`scp`, so
# without it a workflow's log-collection steps silently degrade to API-only
# (`ssh: command not found`). unzip lets agents open downloaded log bundles.
# gh is here so an agent can open a pull request. Without it a workflow whose job ends in a
# change can only ever describe the diff it would have applied.
#
# python3 and libicu72 are the runtime half of the same problem. An agent that can read code but
# cannot execute it can never say "the test passed" — a real implement run returned a correct
# patch for two Action repos and had to report every check as unrun, because python3, pwsh and
# pytest were all absent. libicu72 is pwsh's dependency, not ours; without it pwsh installs and
# then fails at startup, which looks like a PowerShell bug rather than a missing package.
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash curl ca-certificates coreutils git openssh-client unzip \
       python3 python3-pip python3-venv libicu72 \
    && rm -rf /var/lib/apt/lists/*

# Debian marks its Python as externally managed, so a bare `pip install` fails with PEP 668 even
# inside a throwaway container. Agents run project test suites, not a curated dependency set, so
# take the marker off rather than making every one of them discover --break-system-packages.
RUN rm -f /usr/lib/python3*/EXTERNALLY-MANAGED

# uv, because that is what many projects' own scripts/ wrappers call to build and test.
# An agent that pip-installs by hand is not running the project's tests, it is running its own.
# The installer picks its own arch — nothing to pass.
RUN curl -fsSL https://astral.sh/uv/install.sh | env UV_INSTALL_DIR=/usr/local/bin sh \
    && uv --version

# PowerShell: half the Action repos are .ps1 driven, so "run the tests" means pwsh for those.
# Installed from the release tarball like gh above rather than Microsoft's apt repo — one less
# key and source list to rot, and it pins the version explicitly.
#
# `uname -m` is the fallback because the legacy builder sets no TARGETARCH, and both spellings
# are accepted for the same reason. An arch neither names fails the build: a silent x64 fallback
# produces an image whose pwsh is an Exec format error the first time a workflow needs it, which
# reads to an operator as a broken skill rather than a wrong image.
RUN build_arch="${TARGETARCH:-$(uname -m)}" \
    && case "${build_arch}" in \
         amd64|x86_64) pwsh_arch=x64 ;; \
         arm64|aarch64) pwsh_arch=arm64 ;; \
         *) echo "no PowerShell tarball is published for arch ${build_arch}" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://github.com/PowerShell/PowerShell/releases/download/v7.4.6/powershell-7.4.6-linux-${pwsh_arch}.tar.gz" -o /tmp/pwsh.tar.gz \
    && mkdir -p /opt/microsoft/powershell/7 \
    && tar zxf /tmp/pwsh.tar.gz -C /opt/microsoft/powershell/7 \
    && chmod +x /opt/microsoft/powershell/7/pwsh \
    && ln -s /opt/microsoft/powershell/7/pwsh /usr/bin/pwsh \
    && rm /tmp/pwsh.tar.gz \
    && pwsh --version

# gh's release assets are named with Go's arch — and the same name again inside the tarball, so
# the strip path is built from it too. Normalised through the same case as pwsh above rather than
# using TARGETARCH raw, so the uname fallback reaches this download as well.
RUN build_arch="${TARGETARCH:-$(uname -m)}" \
    && case "${build_arch}" in \
         amd64|x86_64) gh_arch=amd64 ;; \
         arm64|aarch64) gh_arch=arm64 ;; \
         *) echo "no gh release is published for arch ${build_arch}" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://github.com/cli/cli/releases/download/v2.62.0/gh_2.62.0_linux_${gh_arch}.tar.gz" \
       | tar xz -C /usr/local --strip-components=1 "gh_2.62.0_linux_${gh_arch}/bin/gh" \
    && gh --version

# All agent CLIs are baked in; AGENT_TYPE picks which one runs at container start. Each npm
# package resolves its own per-arch binary through optionalDependencies, so this one line is
# multi-arch on both amd64 and arm64 with nothing to branch on. Pinned, and each is smoke-run
# below so a wrong-arch or broken install fails the build, not the first task.
RUN npm install -g \
      @anthropic-ai/claude-code@2.1.251 \
      @qwen-code/qwen-code@0.22.3 \
      @google/gemini-cli@0.57.0 \
      @openai/codex@0.151.0 \
      opencode-ai@1.18.25 \
    && claude --version && qwen --version && gemini --version && codex --version && opencode --version

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
