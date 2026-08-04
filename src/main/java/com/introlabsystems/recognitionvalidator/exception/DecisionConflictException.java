package com.introlabsystems.recognitionvalidator.exception;

public class DecisionConflictException extends RuntimeException {

    public DecisionConflictException(String imageId) {
        super("Review task is no longer assigned to this operator: " + imageId);
    }
}
