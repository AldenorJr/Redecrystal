package com.redecrystal.shared.web;

/** Thrown when credentials are missing or invalid; mapped to HTTP 401. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
