# foundry-core — the workflow engine (Javalin + tinylog). Gradle shadowJar build.
# Build context is the repo ROOT:  docker build -f core/Dockerfile -t foundry/foundry-core:local .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY core ./core
WORKDIR /src/core
# test BEFORE shadowJar: a failing suite (e.g. QuestionDiesWithRunTest) must never produce an image
RUN chmod +x gradlew && ./gradlew --no-daemon test shadowJar

FROM eclipse-temurin:25-jre
WORKDIR /app
# curl for the compose healthcheck; the docker CLI for the launcher (spawns agents against
# the host socket mounted at runtime — no daemon ships in this image).
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://download.docker.com/linux/static/stable/x86_64/docker-27.5.1.tgz \
       | tar xz -C /usr/local/bin --strip-components=1 docker/docker
COPY --from=build /src/core/build/libs/foundry-core.jar app.jar
COPY core/workflows ./workflows
ENV FOUNDRY_WORKFLOWS_DIR=/app/workflows
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
