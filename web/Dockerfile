# Stage 1: Build SvelteKit static application
FROM node:20-alpine AS build
WORKDIR /app

# Install dependencies first for Docker layer caching
COPY package*.json ./
RUN npm ci

# Copy the rest of the application code and build
COPY . .
RUN npm run build

# Stage 2: Serve using Nginx
FROM nginx:alpine
COPY --from=build /app/build /usr/share/nginx/html

# Rendered by the nginx image's envsubst entrypoint on startup. The FILTER is
# essential: without it envsubst would also eat nginx's own runtime variables
# ($uri, $host, $http_upgrade, ...) and produce a broken config.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_FILTER='^(NGINX_PORT|BACKEND_HOST|BACKEND_PORT)$'

# Defaults match the Docker Compose topology; DataRobot overrides them per-container.
ENV NGINX_PORT=80 \
    BACKEND_HOST=backend \
    BACKEND_PORT=8080

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
