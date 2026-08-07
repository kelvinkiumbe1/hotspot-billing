# One image serving both the API and the UI, so a tenant is a single
# container behind one URL rather than two processes to keep alive.

# --- 1. Build the React app ---
FROM node:22-alpine AS ui
WORKDIR /ui
# Copy manifests first so `npm ci` is cached until dependencies change.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# --- 2. Build the Spring Boot jar, with the UI inside it ---
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /app
COPY pom.xml ./
# Warm the dependency cache before the sources land.
RUN mvn -q -B dependency:go-offline
COPY src/ ./src/
# Spring serves anything in static/ from the classpath root.
COPY --from=ui /ui/dist/ ./src/main/resources/static/
RUN mvn -q -B -DskipTests package

# --- 3. Runtime ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run as root, and own the upload directory so writes succeed.
RUN addgroup -S spa && adduser -S spa -G spa \
    && mkdir -p /app/uploads && chown -R spa:spa /app
USER spa

COPY --from=api --chown=spa:spa /app/target/*.jar app.jar

# Task photos and logos live here; mount a volume so they survive a redeploy.
VOLUME ["/app/uploads"]

ENV SERVER_PORT=8080 \
    UPLOAD_DIR=/app/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# The plans endpoint touches the database, so a healthy answer means the
# app is genuinely serving rather than merely started.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/api/plans >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
