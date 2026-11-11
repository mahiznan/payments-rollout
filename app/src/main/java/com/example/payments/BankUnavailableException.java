package com.example.payments;

/** Thrown when the (simulated) downstream bank gateway is down. */
public class BankUnavailableException extends RuntimeException {

    public BankUnavailableException() {
        super("bank gateway unavailable");
    }
}
