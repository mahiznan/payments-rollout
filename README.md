# Zero-downtime rollouts on OpenShift: a payment API demo

A small payment-authorization API (Spring Boot 4, Java 25) deployed to OpenShift Local (CRC).
A k6 load test sends steady traffic while a rollout replaces every pod, and the same test is
repeated after each hardening step, so the effect of each setting is measured and not assumed.

## Result

Same test every time: about 40 authorizations per second for 180 s, 3 replicas, and a rollout
(`APP_VERSION` v1 to v2) triggered 30 s in.

| Phase | Configuration | Failed requests | Notes |
|---|---|---|---|
| 5 | Naive: no probes, no graceful shutdown, `maxUnavailable: 1` | **460** of 7,787 (5.90%) | One continuous 13 s outage: 378 x 503, 7 x 502, 75 timeouts |
| 6 | + startup/liveness/readiness probes, `maxUnavailable: 0` | **4** of 7,885 (0.05%) | All 4 were timeouts, one per old pod being killed |
| 7 | + graceful shutdown, `preStop` sleep, grace period | **0** of 7,923 (0.00%) | Slowest request 0.83 s, no timeouts, all checks passed |

An extra Phase 6 run with the rollout triggered at 60 s instead of 30 s gave 7 failures of 7,908
(0.08%): one 502 and six timeouts.

**Caveats.** Each configuration was run once (Phase 6 twice), so treat 0 failures as "the fix works
in this run", not as a guarantee. Load is generated on a Mac and goes through the CRC VM and
router, so very high rates may measure the generator and not the app. Results are in `results/`.
`results/phase5-naive-retest.txt` is a partial run with no final summary and is not used above.

## What each setting does

| Setting | Where | What it fixes |
|---|---|---|
| Readiness probe | `k8s/deployment.yaml` | A new pod gets no traffic until the JVM is really listening. Without it, Kubernetes treats a pod as Ready the moment its container starts, so the rollout raced ahead and replaced every old pod while the new ones were still booting. |
| `maxUnavailable: 0`, `maxSurge: 1` | `k8s/deployment.yaml` | An old pod is removed only after a new pod is Ready. Never fewer than 3 ready pods. |
| Startup probe | `k8s/deployment.yaml` | Gives a slow JVM boot up to 150 s without liveness restarting it. |
| Liveness probe | `k8s/deployment.yaml` | Restarts a stuck container. Uses app-internal state only, so a bank outage does not restart pods. |
| `server.shutdown: graceful` | `app/src/main/resources/application.yaml` | On SIGTERM the server stops taking new requests and lets in-flight ones finish. |
| `timeout-per-shutdown-phase: 20s` | `application.yaml` | The most the app waits for in-flight requests. Typically under 1 s here. |
| `preStop` sleep 5 | `k8s/deployment.yaml` | The router learns a pod is leaving a few seconds after SIGTERM. The app keeps serving during the sleep, so no request hits a closed port. |
| `terminationGracePeriodSeconds: 45` | `k8s/deployment.yaml` | Hard limit before SIGKILL. Must exceed `preStop` + drain: 45 > 5 + 20. |

Shutdown timeline for one pod:

```
t=0s    pod marked Terminating. In parallel: removed from endpoints (router updates)
        and preStop starts (sleep 5, app still serving)
t=5s    SIGTERM: no new requests accepted, in-flight requests finish (up to 20 s)
t~6s    app exits (typical here)
t=45s   SIGKILL if still running
```

## The application

`payment-api` has two dependencies: `spring-boot-starter-webmvc` and `spring-boot-starter-actuator`.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/payments/authorize` | Header `Idempotency-Key`, body `{orderId, amount, currency, cardToken}`. Simulates a 200 to 800 ms bank call. |
| `GET /api/v1/payments/{authId}` | Look up an authorization (only on the pod that created it) |
| `GET /actuator/health/liveness` | App-internal state only |
| `GET /actuator/health/readiness` | `readinessState` + `bankGateway` |

The `authId` is derived from the idempotency key (`auth_` + first 20 hex characters of its SHA-256),
so a retry on any pod returns the same id. The idempotency store itself is per-pod memory, which is
a simplification: a real service would use Redis or a database shared by all replicas.
`bankGateway` is in the readiness group only, so a bank outage removes a pod from traffic but
does not restart it.

## Repository layout

```
app/                  Spring Boot app, Dockerfile (multi-stage: Maven + JDK 25, then JRE 25)
k8s/                  deployment.yaml, service.yaml, route.yaml
k6/payments-load.js   load test: checks status, APPROVED, idempotent retries, version counts
scripts/build.sh      in-cluster image build (oc new-build --binary / oc start-build)
scripts/deploy.sh     oc apply -f k8s/ and wait for the rollout
scripts/rollout.sh    change APP_VERSION to trigger a rollout and wait for it
results/              raw k6 output of every run
PLAN.md               the original phased plan
```

## Reproduce

```bash
./scripts/build.sh                       # build the image in the cluster
./scripts/deploy.sh                      # 3 replicas, Service, Route

# terminal 1: load for 3 minutes
k6 run -e DURATION=180s k6/payments-load.js 2>&1 | tee results/my-run.txt
# terminal 2: 30 s after k6 starts
./scripts/rollout.sh v2

# failures per second and status
grep ' FAIL ' results/my-run.txt | awk '{print substr($1,18,8), $6}' | sort | uniq -c

./scripts/rollout.sh v1                  # reset between runs
```

## CRC notes

- The CRC VM disk is small (about 31 GB). Below about 4.9 GB free the kubelet reports DiskPressure
  and evicts build pods. Check with `crc status`.
- Memory *requests* on the node were about 92% allocated, so the pods request only 96Mi
  (limit 512Mi). That is why the demo uses 3 replicas and not 5.
- `crc ip` returns `127.0.0.1` here, and ssh to the VM is on port 2222:
  `ssh -p 2222 -i ~/.crc/machines/crc/id_ed25519 core@127.0.0.1`.

## Readiness vs liveness demo

Shows that when the downstream bank gateway fails on **one** pod, that pod is taken out of traffic
(readiness) but **not restarted** (liveness). The `bankGateway` health indicator is in the readiness
group only, so liveness never calls it.

```yaml
management.endpoint.health.group:
  liveness:  { include: livenessState }
  readiness: { include: readinessState,bankGateway }
```

**Security note:** the switch is off by default. While `ADMIN_ENABLED=true`, `/admin/bank/*` is
reachable through the public Route, so keep that window short and turn it off afterwards (step 7).

### Steps

1. Enable the demo switch. This changes the pod template, so it rolls new pods:
   ```bash
   oc set env deployment/payment-api ADMIN_ENABLED=true
   oc rollout status deployment/payment-api
   ```

2. Pick one pod and check the starting state:
   ```bash
   POD=$(oc get pods -l app=payment-api -o jsonpath='{.items[0].metadata.name}'); echo $POD
   oc get endpoints payment-api          # 3 pod IPs
   oc get pods -l app=payment-api        # 3 x 1/1, RESTARTS 0
   ```

3. Terminal A: forward a local port to that single pod, and leave it running. `port-forward` is a
   foreground process on your machine that tunnels to one pod. It stores nothing in the cluster, and
   it stops when you press Ctrl+C or when the pod is replaced.
   ```bash
   oc port-forward pod/$POD 8081:8080
   ```
   Terminal B (optional): `oc get pods -l app=payment-api --watch`

4. Terminal C: check, then take the bank down on that pod only:
   ```bash
   curl -s -w ' [%{http_code}]\n' localhost:8081/actuator/health/readiness   # UP   [200]
   curl -s -w ' [%{http_code}]\n' localhost:8081/actuator/health/liveness    # UP   [200]

   curl -s -X POST localhost:8081/admin/bank/down                            # {"bankUp":false}
   curl -s -w ' [%{http_code}]\n' localhost:8081/actuator/health/readiness   # DOWN [503]
   curl -s -w ' [%{http_code}]\n' localhost:8081/actuator/health/liveness    # UP   [200]
   ```

5. Wait about 20 s. Readiness needs 3 failed probes at 5 s intervals, so about 15 s:
   ```bash
   oc get pods -l app=payment-api               # that pod 0/1, RESTARTS still 0
   oc get endpoints payment-api                 # only 2 IPs now
   oc get pod $POD -o jsonpath='restarts={.status.containerStatuses[0].restartCount}{"\n"}'
   oc get events --field-selector involvedObject.name=$POD | tail -5     # "Readiness probe failed"
   ```

6. Bring the bank back. One passing readiness check (within 5 s) restores the pod:
   ```bash
   curl -s -X POST localhost:8081/admin/bank/up
   sleep 10
   oc get pods -l app=payment-api               # 3 x 1/1
   oc get endpoints payment-api                 # 3 IPs again
   ```

7. Clean up. Stop the port-forward with Ctrl+C in Terminal A, then remove the demo switch:
   ```bash
   oc set env deployment/payment-api ADMIN_ENABLED-
   oc rollout status deployment/payment-api
   ```

### Result

Confirmed by the author on CRC: the pod went `0/1` and dropped out of the endpoints while
`RESTARTS` stayed `0`, and it rejoined once the bank was switched back up. The raw command output
was not saved, so no timings are quoted beyond the probe settings above.

| Probe | Bank down | Effect |
|---|---|---|
| Readiness (every 5 s, 3 failures) | Fails, 503 | Pod removed from Service endpoints after about 15 s. Not restarted. |
| Liveness (every 10 s, 3 failures) | Stays UP, 200 | No restart |

If liveness included the bank check, a bank outage would restart **every** pod at once, which cannot
fix the bank and would turn a partial outage into a full one.

Things to know: a request that the router sends to the pod before readiness fails (up to about 15 s)
would still get a 503. Readiness limits the damage but does not make failure detection instant.
