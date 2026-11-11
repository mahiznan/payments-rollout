package com.example.payments;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payments.dto.AuthorizeRequest;
import com.example.payments.dto.ErrorResponse;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/authorize")
    public ResponseEntity<?> authorize(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) AuthorizeRequest request) {

        String problem = validate(idempotencyKey, request);
        if (problem != null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(problem));
        }
        return ResponseEntity.ok(service.authorize(idempotencyKey, request));
    }

    @GetMapping("/{authId}")
    public ResponseEntity<?> get(@PathVariable String authId) {
        return service.find(authId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("authorization not found on this pod")));
    }

    @ExceptionHandler(BankUnavailableException.class)
    public ResponseEntity<ErrorResponse> bankDown(BankUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(e.getMessage()));
    }

    /** Hand-written validation, so the project needs no validation starter. */
    private static String validate(String idempotencyKey, AuthorizeRequest r) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return "Idempotency-Key header is required";
        }
        if (r == null) {
            return "request body is required";
        }
        if (isBlank(r.orderId())) {
            return "orderId is required";
        }
        if (r.amount() == null || r.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return "amount must be greater than 0";
        }
        if (isBlank(r.currency())) {
            return "currency is required";
        }
        if (isBlank(r.cardToken())) {
            return "cardToken is required";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
