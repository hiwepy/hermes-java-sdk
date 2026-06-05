package io.github.hiwepy.hermes.exception;

public class HermesException extends RuntimeException {
    public HermesException(String message) {
        super(message);
    }
    public HermesException(String message, Throwable cause) {
        super(message, cause);
    }
}
