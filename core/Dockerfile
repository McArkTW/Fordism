# fordism-core — the workflow engine (Javalin + tinylog). Gradle shadowJar build.
# Build context is the repo ROOT:  docker build -f core/Dockerfile -t fordism/fordism-core:local .
# Multi-arch: everything here is architecture-neutral except the static docker CLI below.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY core ./core
WORKDIR /src/core
# The wrapper's distribution download is fetched here, and under QEMU it is slow enough to hit
# the JDK's default socket timeouts — the v1.0.0 release build died in exactly this line, in
# org.gradle.wrapper.Download, on the linux/arm64 leg while the amd64 leg was still going. The
# download is the only step that can fail for a reason that has nothing to do with the code, so
# it is separated out and retried; the build itself is not retried, because a failing suite must
# fail once and stay failed.
RUN chmod +x gradlew \
    && for attempt in 1 2 3; do \
         ./gradlew --no-daemon --version && break; \
         echo "gradle wrapper download attempt ${attempt} failed; retrying" >&2; \
         sleep 15; \
       done \
    && ./gradlew --no-daemon --version
# test BEFORE shadowJar: a failing suite (e.g. QuestionDiesWithRunTest) must never produce an image
RUN ./gradlew --no-daemon test shadowJar

FROM eclipse-temurin:25-jre
# BuildKit sets this to the arch being built for (amd64 / arm64). Declared because the static
# docker CLI is the only thing in this image that is not architecture-neutral, and download.docker.com
# files it under the kernel's name for the arch (x86_64 / aarch64), not Go's.
ARG TARGETARCH
WORKDIR /app
# curl for the compose healthcheck; the docker CLI for the launcher (spawns agents against
# the host socket mounted at runtime — no daemon ships in this image).
#
# `uname -m` is the fallback because the legacy builder sets no TARGETARCH at all, and a build
# that used to work must not start failing on an engine with BuildKit turned off. Both spellings
# are accepted for the same reason. An arch neither names fails the build rather than falling
# back to x86_64: a core image carrying the wrong CLI starts fine and then cannot launch a
# single agent — a health check that passes and a workflow that never runs.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && build_arch="${TARGETARCH:-$(uname -m)}" \
    && case "${build_arch}" in \
         amd64|x86_64) docker_arch=x86_64 ;; \
         arm64|aarch64) docker_arch=aarch64 ;; \
         *) echo "no static docker CLI is published for arch ${build_arch}" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://download.docker.com/linux/static/stable/${docker_arch}/docker-27.5.1.tgz" \
       | tar xz -C /usr/local/bin --strip-components=1 docker/docker \
    && docker --version
COPY --from=build /src/core/build/libs/fordism-core.jar app.jar
COPY core/workflows ./workflows
ENV FORDISM_WORKFLOWS_DIR=/app/workflows
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
