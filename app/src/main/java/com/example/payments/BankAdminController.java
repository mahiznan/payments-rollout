package com.example.payments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payments.dto.ErrorResponse;

/**
 * Demo-only switch to simulate a bank outage on ONE pod (used with oc port-forward).
 * Disabled unless payments.admin.enabled=true (env ADMIN_ENABLED), because the
 * route is public and these endpoints must not be reachable in normal operation.
 */
@RestController
@RequestMapping("/admin/bank")
public class BankAdminController {

    private final BankGateway bank;
    private final boolean enabled;

    public BankAdminController(BankGateway bank, @Value("${payments.admin.enabled}") boolean enabled) {
        this.bank = bank;
        this.enabled = enabled;
    }

    @PostMapping("/down")
    public ResponseEntity<?> down() {
        return switchBank(false);
    }

    @PostMapping("/up")
    public ResponseEntity<?> up() {
        return switchBank(true);
    }

    private ResponseEntity<?> switchBank(boolean up) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not found"));
        }
        bank.setUp(up);
        return ResponseEntity.ok(java.util.Map.of("bankUp", bank.isUp()));
    }
}
