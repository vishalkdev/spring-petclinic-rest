package org.springframework.samples.petclinic.integration.relay;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.samples.petclinic.integration.config.IntegrationProperties;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTests {

    private static final IntegrationProperties.Retry DEFAULTS =
        new IntegrationProperties.Retry(Duration.ofSeconds(2), 2.0, Duration.ofMinutes(10), 15);

    @Test
    void doublesTheCeilingAfterEachFailure() {
        BackoffPolicy backoff = new BackoffPolicy(DEFAULTS, () -> 1.0);

        assertThat(backoff.delayAfter(1, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.delayAfter(2, null)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.delayAfter(3, null)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void neverExceedsTheMaximumDelay() {
        BackoffPolicy backoff = new BackoffPolicy(DEFAULTS, () -> 1.0);

        assertThat(backoff.delayAfter(14, null)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void appliesFullJitterBelowTheCeiling() {
        BackoffPolicy backoff = new BackoffPolicy(DEFAULTS, () -> 0.25);

        assertThat(backoff.delayAfter(3, null)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void waitsAtLeastAsLongAsRetryAfterButNoLongerThanTheMaximum() {
        BackoffPolicy backoff = new BackoffPolicy(DEFAULTS, () -> 0.0);

        assertThat(backoff.delayAfter(1, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.delayAfter(1, Duration.ofHours(2))).isEqualTo(Duration.ofMinutes(10));
    }
}
