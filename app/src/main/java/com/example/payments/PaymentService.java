package com.example.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.payments.dto.AuthorizeRequest;
import com.example.payments.dto.AuthorizeResponse;

@Service
public class PaymentService {

    private final BankGateway bank;
    private final String version;
    private final String pod;

    /**
     * Idempotency store, keyed by authId. Held in this pod's memory only.
     * A real service would use Redis or a database shared by all replicas.
     * The authId is deterministic, so duplicates are still detectable across pods.
     */
    private final Map<String, AuthorizeResponse> store = new ConcurrentHashMap<>();

    public PaymentService(BankGateway bank, @Value("${app.version}") String version) {
        this.bank = bank;
        this.version = version;
        this.pod = System.getenv().getOrDefault("HOSTNAME", "local");
    }

    public AuthorizeResponse authorize(String idempotencyKey, AuthorizeRequest request) {
        String authId = authIdFor(idempotencyKey);

        AuthorizeResponse existing = store.get(authId);
        if (existing != null) {
            return existing.asReplay();
        }

        bank.approve(); // slow downstream call, may throw BankUnavailableException

        AuthorizeResponse created = new AuthorizeResponse(
                authId, "APPROVED", request.orderId(), request.amount(),
                request.currency(), version, pod, false);
        AuthorizeResponse raced = store.putIfAbsent(authId, created);
        return raced != null ? raced.asReplay() : created;
    }

    public Optional<AuthorizeResponse> find(String authId) {
        return Optional.ofNullable(store.get(authId));
    }

    /** "auth_" + first 20 hex chars of SHA-256(key). The same key always gives the same id. */
    static String authIdFor(String idempotencyKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return "auth_" + HexFormat.of().formatHex(hash).substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
