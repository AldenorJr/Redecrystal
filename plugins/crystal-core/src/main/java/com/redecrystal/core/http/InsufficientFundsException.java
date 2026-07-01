package com.redecrystal.core.http;

/**
 * A conditional debit/transfer was rejected for lack of funds (HTTP 422). Extends
 * {@link BackendHttpClient.BackendException} so generic backend-error handling
 * still catches it, while callers that care can catch this specifically.
 */
public final class InsufficientFundsException extends BackendHttpClient.BackendException {
    public InsufficientFundsException(String message) {
        super(422, message);
    }
}
