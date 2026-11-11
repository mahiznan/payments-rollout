#!/usr/bin/env bash
# Applies the manifests in k8s/ and waits for the Deployment to be fully rolled out.
set -euo pipefail

cd "$(dirname "$0")/.."

echo ">> Applying k8s/"
oc apply -f k8s/

echo ">> Waiting for rollout"
oc rollout status deployment/payment-api --timeout=300s

echo ">> Route:"
oc get route payment-api -o jsonpath='http://{.spec.host}{"\n"}'
