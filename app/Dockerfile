# fordism-app — Angular static SPA served by nginx, which also proxies /api to
# fordism-core. Node 24 for the build (the workspace pins Node >= 24).
FROM node:26-alpine AS build
WORKDIR /src
COPY package*.json ./
RUN npm ci
COPY . .
# The gates: lint for style, specs because `ng build` type-checks but only they
# prove a template still renders.
RUN npm run lint
RUN npm run test
RUN npm run build

FROM nginx:alpine
COPY --from=build /src/dist/fordism-app/browser /usr/share/nginx/html
COPY default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80
