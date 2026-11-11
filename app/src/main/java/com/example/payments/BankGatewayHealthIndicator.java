package com.example.payments;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Registered as the "bankGateway" health contributor (bean name minus the
 * HealthIndicator suffix). It is included in the READINESS group only, so a
 * bank outage stops traffic to the pod but does not make Kubernetes restart it.
 */
@Component
public class BankGatewayHealthIndicator implements HealthIndicator {

    private final BankGateway bank;

    public BankGatewayHealthIndicator(BankGateway bank) {
        this.bank = bank;
    }

    @Override
    public Health health() {
        return bank.isUp() ? Health.up().build() : Health.down().withDetail("reason", "bank gateway down").build();
    }
}
