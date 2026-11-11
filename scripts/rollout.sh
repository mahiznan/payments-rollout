#!/usr/bin/env bash
# Triggers a rollout by changing APP_VERSION and waits for it to finish.
# Changing an env var changes the pod template, so the Deployment creates a new
# ReplicaSet and replaces the pods, with no rebuild of the image.
#
#   ./scripts/rollout.sh v2
set -euo pipefail

VERSION="${1:?usage: rollout.sh <version>   e.g. rollout.sh v2}"

echo ">> $(date +%T) Rolling out APP_VERSION=$VERSION"
oc set env deployment/payment-api APP_VERSION="$VERSION"

oc rollout status deployment/payment-api --timeout=300s
echo ">> $(date +%T) Rollout finished"
oc get pods -l app=payment-api
