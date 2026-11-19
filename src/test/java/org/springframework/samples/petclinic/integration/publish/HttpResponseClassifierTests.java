package org.springframework.samples.petclinic.integration.publish;

import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpResponseClassifierTests {

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    void treats2xxAsSuccess(int status) {
        assertThat(HttpResponseClassifier.classify(status, null, null).outcome())
            .isEqualTo(PublishResult.Outcome.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 425, 429, 500, 502, 503, 504})
    void retriesTemporaryFailures(int status) {
        assertThat(HttpResponseClassifier.classify(status, null, null).outcome())
            .isEqualTo(PublishResult.Outcome.RETRYABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 400, 401, 403, 404, 409, 422})
    void parksErrorsThatRetryingCannotFix(int status) {
        assertThat(HttpResponseClassifier.classify(status, null, null).outcome())
            .isEqualTo(PublishResult.Outcome.PERMANENT);
    }

    @Test
    void readsRetryAfterInSeconds() {
        assertThat(HttpResponseClassifier.classify(503, "120", null).retryAfter()).isEqualTo(Duration.ofSeconds(120));
        assertThat(HttpResponseClassifier.classify(503, "Wed, 21 Oct 2026 07:28:00 GMT", null).retryAfter()).isNull();
    }

    @Test
    void keepsTheResponseBodyInTheError() {
        assertThat(HttpResponseClassifier.classify(400, null, "missing field 'type'").error())
            .isEqualTo("HTTP 400: missing field 'type'");
    }
}
