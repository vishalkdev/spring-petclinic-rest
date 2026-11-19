package org.springframework.samples.petclinic.integration.publish;

public record PublishResult(Outcome outcome, String error) {

    public enum Outcome {
        SUCCESS,
        RETRYABLE,
        PERMANENT
    }

    public static PublishResult success() {
        return new PublishResult(Outcome.SUCCESS, null);
    }

    public static PublishResult retryable(String error) {
        return new PublishResult(Outcome.RETRYABLE, error);
    }

    public static PublishResult permanent(String error) {
        return new PublishResult(Outcome.PERMANENT, error);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
