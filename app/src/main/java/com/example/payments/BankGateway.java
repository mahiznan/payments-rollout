package com.example.payments;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simulated downstream card network / bank.
 * Each call takes a random time, so there are always requests in flight.
 * It can be switched "down" to demonstrate readiness vs liveness.
 */
@Component
public class BankGateway {

    private final AtomicBoolean up = new AtomicBoolean(true);
    private final long minLatencyMs;
    private final long maxLatencyMs;

    public BankGateway(
            @Value("${payments.bank.min-latency-ms}") long minLatencyMs,
            @Value("${payments.bank.max-latency-ms}") long maxLatencyMs) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    public boolean isUp() {
        return up.get();
    }

    public void setUp(boolean value) {
        up.set(value);
    }

    /** Simulates the network round trip to the bank. */
    public void approve() {
        if (!up.get()) {
            throw new BankUnavailableException();
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(minLatencyMs, maxLatencyMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling bank", e);
        }
    }
}
