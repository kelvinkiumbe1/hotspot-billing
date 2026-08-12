#!/usr/bin/env bash
# Build the billing app jar WITH the React frontend baked in — the artifact the
# LOCAL provisioner launches one instance of per tenant (the no-Docker cousin of
# new-tenant.sh). Mirrors what the Dockerfile does, for a plain dev box.
#
#   ./deploy/build-local-app.sh
#
# Produces target/hotspot-billing-*.jar. Re-run after frontend/backend changes
# so freshly-provisioned local tenants get the current UI.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The machine's JAVA_HOME is often an old JDK (Java 8), which can't compile the
# app's records/switch-expressions. Force Liberica like the run scripts do —
# override with ZIDI_JDK if yours lives elsewhere.
export JAVA_HOME="${ZIDI_JDK:-/c/Program Files/BellSoft/LibericaJDK-26}"
echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

echo "==> Building frontend"
( cd frontend && npm install --no-audit --no-fund --silent && npm run build )

echo "==> Baking the built frontend into the backend static dir"
rm -rf src/main/resources/static
mkdir -p src/main/resources/static
cp -r frontend/dist/. src/main/resources/static/

echo "==> Packaging the jar (tests skipped)"
if [ -x ./mvnw ]; then ./mvnw -q -DskipTests clean package; else ./mvnw.cmd -q -DskipTests clean package; fi

echo "==> Done:"
ls -la target/*.jar
