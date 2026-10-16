package com.smartparking.infrastructure;

/** Durable storage could not complete a local transaction. */
public class StoreException extends RuntimeException {
    public StoreException(String message, Throwable cause) { super(message, cause); }
}
