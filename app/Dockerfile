# fordism-app — Angular static SPA served by nginx, which also proxies /api to
# fordism-core. Node 24 for the build (the workspace pins Node >= 24).
FROM node:24-alpine AS build
WORKDIR /src
COPY package*.json ./
RUN npm ci
COPY . .
# lint for style, then build (which type-checks every template). The Vitest suite is NOT
# run here: the app is an arch-neutral static bundle, so under the release's arm64 leg this
# step runs the whole suite through QEMU emulation — slow, and flaky on async-timing specs
# that are solid on real hardware (app.spec's one-tick wait for /me + a lazy route). Tests
# are authoritative in CI's `app` job (amd64) and its `docker compose build`; running them
# again, emulated, on the publish path bought nothing but a flaky release.
RUN npm run lint
RUN npm run build

FROM nginx:alpine
COPY --from=build /src/dist/fordism-app/browser /usr/share/nginx/html
COPY default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80
