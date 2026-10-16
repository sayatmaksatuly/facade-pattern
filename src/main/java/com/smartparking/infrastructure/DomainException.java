package com.smartparking.infrastructure;

/** A business rule rejected an operation; the message is safe to show in the CLI. */
public class DomainException extends RuntimeException {
    public DomainException(String message) { super(message); }
}
