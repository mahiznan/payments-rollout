#!/usr/bin/env bash
# Builds the payment-api image INSIDE the cluster (no local Docker or JDK needed).
#
#   1. Creates the BuildConfig + ImageStream on the first run (binary build)
#   2. Uploads a copy of app/ (without target/) to the cluster
#   3. The build pod runs the Dockerfile and pushes payment-api:latest to the
#      internal registry
set -euo pipefail

cd "$(dirname "$0")/.."
NAME=payment-api

if ! oc get bc/"$NAME" >/dev/null 2>&1; then
  echo ">> Creating BuildConfig and ImageStream '$NAME'"
  oc new-build --binary --strategy=docker --name="$NAME"
fi

# Stage a clean copy so the locally built target/ (about 22 MB) is not uploaded
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo ">> Staging app/ (excluding target/)"
rsync -a --exclude target/ app/ "$STAGE"/

echo ">> Starting build"
oc start-build "$NAME" --from-dir="$STAGE" --follow
