package org.mat.exception;

public class NoResponseException extends RuntimeException {

    public NoResponseException(String reason) {
        super(reason);
    }

}
