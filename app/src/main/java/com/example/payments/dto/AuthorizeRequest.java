package com.example.payments.dto;

import java.math.BigDecimal;

/**
 * Body of POST /api/v1/payments/authorize.
 * cardToken is a fake opaque string; no real card data is ever handled.
 */
public record AuthorizeRequest(String orderId, BigDecimal amount, String currency, String cardToken) {
}
