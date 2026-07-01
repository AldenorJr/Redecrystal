package com.redecrystal.rankup.shared.web;

/** Thrown when a conditional debit/transfer lacks funds; mapped to HTTP 422. */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
