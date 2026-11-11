package com.example.payments.dto;

import java.math.BigDecimal;

/**
 * Result of an authorization.
 *
 * @param authId   deterministic id derived from the Idempotency-Key
 * @param version  value of APP_VERSION, so a rollout is visible in responses
 * @param pod      hostname of the pod that first processed this authorization
 * @param replayed true when this response was served from the idempotency store
 */
public record AuthorizeResponse(
        String authId,
        String status,
        String orderId,
        BigDecimal amount,
        String currency,
        String version,
        String pod,
        boolean replayed) {

    public AuthorizeResponse asReplay() {
        return new AuthorizeResponse(authId, status, orderId, amount, currency, version, pod, true);
    }
}
