# fordism-app — Angular static SPA served by nginx, which also proxies /api to
# fordism-core. Node 24 for the build (the workspace pins Node >= 24).
FROM node:24-alpine AS build
WORKDIR /src
COPY package*.json ./
RUN npm ci
COPY . .
# The gate: `ng build` type-checks, but only the specs prove a template still renders.
RUN npm run test
RUN npm run build

FROM nginx:alpine
COPY --from=build /src/dist/fordism-app/browser /usr/share/nginx/html
COPY default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80
