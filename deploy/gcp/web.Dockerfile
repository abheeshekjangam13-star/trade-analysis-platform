# The UI (trade-analysis-ui, passed in as the "ui" build context) built and served by Caddy, which also does
# HTTPS (free Let's Encrypt certificate for $DOMAIN) and sends /api/* to the api-gateway.
FROM node:20.18-alpine AS ui-build
WORKDIR /ui
COPY --from=ui package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY --from=ui . .
# Same-origin API: the browser calls https://$DOMAIN/api/..., which Caddy proxies to the gateway.
ENV VITE_API_BASE=
RUN npm run build

FROM caddy:2.8.4-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=ui-build /ui/dist /srv
