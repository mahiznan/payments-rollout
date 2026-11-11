# Zero-Downtime Rollout: Payment Authorization API on OpenShift Local (CRC)

Status: **Approved (all defaults accepted). Building phase by phase, see section 9. Nothing built or deployed yet.**
(v2 replaces the generic "hello" demo with a real-world payment service.)

## 1. Use case

A **payment authorization API**, the kind of service a checkout page calls to approve a card charge.
Why it suits a rollout test:

- Calls are `POST`s that **must not be dropped**. A failed call means a lost sale or a confused customer.
- Each call waits on a slow downstream (the card network or bank), so there are **always requests in flight** when a pod gets SIGTERM. That is the case graceful shutdown exists for.
- Clients retry on failure, so the API is **idempotent**. A retry with the same `Idempotency-Key` must not charge twice.
- Real services have a downstream dependency that should affect **readiness but not liveness**.

## 2. Goal

Deploy the service with **3 replicas** to CRC (reduced from 5 because the CRC VM has little free memory), roll out a new version while **k6** sends steady payment traffic, and show **zero failed requests and zero duplicate or lost authorizations**.

| Concept | Where it shows up |
|---|---|
| Readiness probe | Includes a downstream "bank gateway" check, so a pod that can't reach it gets no traffic |
| Liveness probe | App-internal state only, so a bank outage doesn't restart every pod |
| Startup probe | Covers JVM boot so liveness doesn't kill a slow start |
| Graceful shutdown | In-flight authorizations (200 to 800ms) finish before the pod exits |
| `preStop` sleep | Covers the router's endpoint-removal race |
| `terminationGracePeriodSeconds` | Longer than preStop + shutdown timeout |
| RollingUpdate | `maxUnavailable: 0`, `maxSurge: 1` |
| k6 | Proves no dropped calls, and a contrast run shows the settings matter |

## 3. Environment (checked on this machine)

- `oc` 4.17.1, logged in as `developer`, project `my-project`, `https://api.crc.testing:6443`
- CRC 2.64 / OpenShift 4.22
- `k6` v2.3.0 installed
- **No local Docker or JDK needed.** The image is built inside the cluster with a binary build (`oc new-build --binary`, `oc start-build --from-dir`).
- `oc adm top` has no Metrics API on CRC, so I'll check capacity with `oc describe node`.

## 4. Design decisions (defaults unless you object)

1. **`Deployment`, not `DeploymentConfig`** (DC is deprecated).
2. **In-cluster binary build** with a multi-stage Dockerfile (Maven build, then slim JRE). Needs the build pod to reach Maven Central.
3. **Spring Boot 4.x, Java 25**, configured with **YAML** (`application.yaml`). **Only two dependencies:** `spring-boot-starter-webmvc` (REST) and `spring-boot-starter-actuator` (health probes). No validation starter (inputs are checked by hand), no Lombok, no database, no test or devtools extras. Authorizations are tracked in a per-pod in-memory map for idempotency (see caveat in section 5). Exact starter names and the Maven/JRE base images (`25`) are confirmed at build time, since Boot 4 renamed some starters.
4. **Plain HTTP route.** k6 runs on this Mac.
5. **Sizing:** request **96Mi / 50m CPU**, limit 512Mi per pod. (Changed from 256Mi: the CRC node already had about 92% of its memory *requests* allocated, leaving only about 790Mi schedulable. Actual free memory is much larger, and the 512Mi limit still protects the node.)

## 5. The application: `payment-api`

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/payments/authorize` | Body: `{ "orderId", "amount", "currency", "cardToken" }`. Header: `Idempotency-Key`. Simulates a bank call of 200 to 800ms, returns `{ "authId", "status": "APPROVED", "orderId", "version", "pod" }`. |
| `GET /api/v1/payments/{authId}` | Look up an authorization |
| `GET /actuator/health/liveness` | App-internal state only |
| `GET /actuator/health/readiness` | `readinessState` + `bankGateway` indicator |

Behavior:

- **Validation:** done by hand in the controller (no validation starter). A missing key or an amount `<= 0` returns `400`, so k6 can tell app errors from rollout errors.
- **Deterministic `authId`** derived from the `Idempotency-Key`. A retry on any pod returns the same `authId`, so duplicates are detectable even across pods.
- **`bankGateway` health indicator** is a simulated downstream check. A toggle (`POST /admin/bank/down`, restricted to localhost or an env flag) lets us show readiness failing while liveness stays `UP`.
- **`APP_VERSION` env var** is echoed in responses, so a rollout can be triggered by changing one env var without rebuilding.

**Caveat:** the idempotency store is per-pod memory. A real service would use Redis or a database. For this demo, the deterministic `authId` gives the same duplicate-detection guarantee without extra infrastructure, and I'll note it in the README. If you'd rather have a real store (Postgres or Redis on CRC), say so, but it costs RAM and complexity.

Key `application.yaml`:

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,bankGateway
```

## 6. Folder layout

```
payments-rollout/
├── PLAN.md
├── README.md                 <- written after the run, with real results
├── app/
│   ├── pom.xml
│   ├── Dockerfile            <- multi-stage: maven build -> JRE runtime
│   └── src/main/java/com/example/payments/
│       ├── PaymentApplication.java
│       ├── PaymentController.java
│       ├── PaymentService.java
│       ├── BankGatewayHealthIndicator.java
│       └── dto/ (AuthorizeRequest, AuthorizeResponse)
│   └── src/main/resources/application.yaml
├── k8s/
│   ├── deployment.yaml       <- 3 replicas, probes, preStop, strategy
│   ├── service.yaml
│   └── route.yaml
├── k6/
│   └── payments-load.js
└── scripts/
    ├── build.sh              <- oc new-build / start-build
    ├── deploy.sh             <- oc apply -f k8s/
    └── rollout.sh            <- change version, watch rollout
```

## 7. Deployment spec (key parts)

```yaml
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }
  template:
    spec:
      terminationGracePeriodSeconds: 45   # > preStop(5) + shutdown timeout(20)
      containers:
      - name: payment-api
        ports: [{ containerPort: 8080 }]
        env: [{ name: APP_VERSION, value: v1 }]
        resources:
          requests: { cpu: 50m, memory: 96Mi }
          limits:   { memory: 512Mi }
        startupProbe:   { httpGet: {path: /actuator/health/liveness,  port: 8080}, periodSeconds: 5,  failureThreshold: 30 }
        livenessProbe:  { httpGet: {path: /actuator/health/liveness,  port: 8080}, periodSeconds: 10, failureThreshold: 3 }
        readinessProbe: { httpGet: {path: /actuator/health/readiness, port: 8080}, periodSeconds: 5,  failureThreshold: 3 }
        lifecycle:
          preStop: { exec: { command: ["sh","-c","sleep 5"] } }
```

### What `preStop` and `terminationGracePeriodSeconds` do

When OpenShift stops a pod (rollout, scale-down, node drain), two things start **at the same time**: the pod is removed from the Service endpoints, and the container is told to stop. They are not sequenced, so the router can send a request to a pod that is already shutting down.

- **`preStop` sleep (5s):** a hook that runs **before** SIGTERM is sent to the app. Sleeping 5s gives the router time to learn the pod is leaving, while the app keeps serving normally. Then SIGTERM arrives, graceful shutdown starts, and the remaining in-flight requests finish.
- **`terminationGracePeriodSeconds` (45s):** the **total time budget** for the whole shutdown, covering the `preStop` hook plus the app's graceful shutdown. If the container is still running when it expires, it gets SIGKILL and in-flight requests are cut off. The default is 30s.

Timeline for one pod:

```
t=0s   pod marked Terminating, removed from endpoints (async)
       preStop runs: sleep 5s      <- app still serving, router catches up
t=5s   SIGTERM -> readiness flips to 503, server stops accepting new requests,
       in-flight requests finish (up to 20s)
t<=25s app exits cleanly
t=45s  hard deadline: SIGKILL if still running
```

Rule: `terminationGracePeriodSeconds` > `preStop` + `timeout-per-shutdown-phase` (45 > 5 + 20, leaving 20s of margin).

## 8. k6 load test: `payments-load.js`

- Executor: `constant-arrival-rate`, about 40 authorizations/s for the whole rollout (lowered if the Mac or router is the bottleneck).
- Each iteration: unique `orderId` and `Idempotency-Key`, `POST /authorize`, then `GET /{authId}`. The GET may hit a different pod, which is expected to `404` since the store is per-pod, so it is **not** counted as a failure. Only the POST is asserted.
- Checks per POST: status `200`, `status == "APPROVED"`, and `authId` matches the deterministic value for the key.
- A fraction (about 10%) of iterations **replays the same key** and checks that the `authId` is identical, which is the idempotency check.
- Thresholds: `http_req_failed: rate==0`, `checks: rate==1`, `http_req_duration: p(95)<1500`.
- A per-`version` counter shows the **v1 to v2 transition** in the summary.

## 9. Phases

Work is done **one phase at a time**. After each phase I stop, explain what was added and why, and wait for you to review and commit before starting the next. The repo is `payments-rollout/` (its own git repo, nothing is committed by me).

The deployment starts **naive** (no probes, no graceful shutdown) and the hardening is added step by step. This replaces the separate "contrast run": each phase's rollout under k6 load shows what the added setting fixes.

| Phase | What is added | How we verify | Suggested commit |
|---|---|---|---|
| **0** | Repo, `.gitignore`, this plan | `git status` clean after your commit | `Add rollout plan` |
| **1** | App skeleton: `pom.xml` (Boot 4, Java 25, 2 deps), main class, `application.yaml`, health endpoints only | Runs on the build pod in phase 3; locally if a JDK is available | `Add Spring Boot skeleton with actuator` |
| **2** | Payment API: authorize endpoint, idempotency, `bankGateway` health indicator, `APP_VERSION` | Code review, plus a local run if possible | `Add payment authorization API` |
| **3** | Multi-stage `Dockerfile`, `scripts/build.sh`, in-cluster build | Build `Complete`, image in the ImageStream | `Add Dockerfile and in-cluster build` |
| **4** | **Naive deploy:** `Deployment` (3 replicas, `maxUnavailable: 1, maxSurge: 1`, no probes), `Service`, `Route` | 3/3 pods Running, `curl` shows different pod names | `Add naive 3-replica deployment` |
| **5** | `k6/payments-load.js`, baseline run, then a **naive rollout** under load | Baseline has 0 failures. The rollout shows failed or dropped requests (expected). | `Add k6 load test and naive rollout results` |
| **6** | **Probes** (startup, liveness, readiness) and `maxUnavailable: 0, maxSurge: 1` | Re-run rollout: fewer or no failures from not-ready pods | `Add probes and safe rolling strategy` |
| **7** | **Graceful shutdown** (`application.yaml`), `preStop` sleep, `terminationGracePeriodSeconds` | Re-run rollout: **zero failures**, versions flip v1 to v2 | `Add graceful shutdown and preStop` |
| **8** | Readiness vs liveness demo (take `bankGateway` down on one pod), `README.md` with **actual observed results** | Pod leaves endpoints but is not restarted | `Add readiness demo and README` |

Notes:
- Phase 1 and 2 run nowhere until the image is built in phase 3, unless a working local JDK 25 is available. I'll check this at phase 1.
- Before phase 4, I check CRC capacity with `oc describe node` (room for about 4 pods: 3 replicas plus 1 surge).
- Results are reported as observed. The naive rollout in phase 5 is not guaranteed to fail, since drops depend on timing.

## 10. Risks and caveats

- **CRC memory.** Reduced to 3 replicas plus 1 surge pod because the node has about 790Mi of schedulable memory requests left.
- **With 3 replicas the Kubernetes default strategy (25%/25%) already equals `maxUnavailable: 0, maxSurge: 1`.** So the naive Phase 4 sets `maxUnavailable: 1` explicitly, and Phase 6 changes it to 0.
- **Internet from the build pod** (Maven Central). If blocked, fallback is to build the jar elsewhere and use a runtime-only Dockerfile.
- **Load generator limits.** The path is Mac to CRC VM to router to pod. A failure could be the router, not the app. The contrast run helps separate the two.
- **Contrast run is not guaranteed to fail.** Drops depend on timing. I'll report whatever actually happens.
- **The payment logic is simulated.** There is no real card processing, no real card data, and `cardToken` is a fake string.

## 11. Decisions (resolved)

1. Use case: payment authorization API. **Accepted.**
2. Idempotency: in-memory with a deterministic `authId`. **Accepted.**
3. Contrast run: **folded into the phases.** Phase 5 is the naive rollout, and phases 6 and 7 add the fixes.
4. `Deployment`, in-cluster build, **3 replicas** at a 96Mi request (changed from 5 at 256Mi because of CRC memory). **Accepted.**
5. Stack: Spring Boot 4.x, Java 25, YAML config, two dependencies only. **Requested.**
6. Workflow: one phase at a time, in a dedicated git repo. The user commits after reviewing each phase. **Requested.**
