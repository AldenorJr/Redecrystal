package com.redecrystal.shared.web;

/** Thrown on an optimistic-locking version mismatch; mapped to HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
