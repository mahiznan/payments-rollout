// Steady payment-authorization traffic, used to watch what a rollout does to live requests.
//
//   Baseline (no rollout):  k6 run -e DURATION=60s  k6/payments-load.js
//   Rollout test:           k6 run -e DURATION=180s k6/payments-load.js
//
// Env vars: BASE_URL, RATE (requests/s), DURATION
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://payment-api-my-project.apps-crc.testing';
const RATE = parseInt(__ENV.RATE || '40');
const DURATION = __ENV.DURATION || '60s';

// Which app version answered (v1 -> v2 during a rollout)
const responsesByVersion = new Counter('responses_by_version');
// Failed requests, tagged by HTTP status (status 0 = connection error / timeout)
const failedRequests = new Counter('failed_requests');

export const options = {
  scenarios: {
    payments: {
      executor: 'constant-arrival-rate', // fixed request rate, whatever the latency
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    // The goal of the whole exercise: no failed requests, no failed checks
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    http_req_duration: ['p(95)<1500'],
    // If k6 itself cannot keep up, results are not trustworthy
    dropped_iterations: ['count==0'],

    // These empty-looking thresholds make k6 print the per-tag counts in the summary
    'responses_by_version{version:v1}': ['count>=0'],
    'responses_by_version{version:v2}': ['count>=0'],
    'responses_by_version{version:v3}': ['count>=0'],
    'failed_requests{status:0}': ['count>=0'],
    'failed_requests{status:500}': ['count>=0'],
    'failed_requests{status:502}': ['count>=0'],
    'failed_requests{status:503}': ['count>=0'],
    'failed_requests{status:504}': ['count>=0'],
  },
};

const HEADERS = { 'Content-Type': 'application/json' };

function authorize(key, orderId) {
  const body = JSON.stringify({
    orderId: orderId,
    amount: 49.9,
    currency: 'USD',
    cardToken: 'tok_load_test',
  });
  return http.post(`${BASE_URL}/api/v1/payments/authorize`, body, {
    headers: Object.assign({ 'Idempotency-Key': key }, HEADERS),
    timeout: '10s',
  });
}

function parse(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function record(res, what) {
  const ok = res.status === 200;
  if (!ok) {
    failedRequests.add(1, { status: String(res.status) });
    console.error(`${new Date().toISOString()} FAIL ${what} status=${res.status} error=${res.error || ''}`);
  }
  const data = ok ? parse(res) : null;
  if (data && data.version) {
    responsesByVersion.add(1, { version: data.version });
  }
  return data;
}

export default function () {
  const key = `k6-${__VU}-${__ITER}-${Date.now()}`;

  const res = authorize(key, `order-${__VU}-${__ITER}`);
  const first = record(res, 'authorize');
  check(res, {
    'authorize: status is 200': (r) => r.status === 200,
    'authorize: APPROVED': () => first !== null && first.status === 'APPROVED',
  });

  // About 10% of iterations retry the SAME Idempotency-Key, like a client retrying
  // after a timeout. It may land on a different pod, so only the authId is compared.
  if (first !== null && Math.random() < 0.1) {
    const retry = authorize(key, `order-${__VU}-${__ITER}`);
    const second = record(retry, 'retry');
    check(retry, {
      'retry: status is 200': (r) => r.status === 200,
      'retry: same authId (idempotent)': () => second !== null && second.authId === first.authId,
    });
  }
}
