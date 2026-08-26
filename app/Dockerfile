# foundry-app — Angular (Fuse) static SPA served by nginx, which also proxies /api to
# foundry-core. The browser holds the Heimdall identity token and sends it as a Bearer;
# nginx passes it through untouched.
FROM node:22-alpine AS build
WORKDIR /src
COPY package*.json ./
RUN npm ci
COPY . .
# The gate: `ng build` type-checks, but only the specs prove a template still renders.
RUN npm run test
RUN npm run build

FROM nginx:alpine
COPY --from=build /src/dist/foundry-app/browser /usr/share/nginx/html
COPY default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80
