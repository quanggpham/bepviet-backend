package com.example.demo.exception;

import lombok.Getter;

@Getter
public class TooManyRequestsException extends BaseException {

    /** Seconds until the client may retry, or null when retrying will not help (e.g. a burned reset code). */
    private final Long retryAfterSeconds;

    public TooManyRequestsException(String message, Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
