package org.springframework.samples.petclinic.integration.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.samples.petclinic.integration.relay.OutboxRelay;
import org.springframework.samples.petclinic.integration.relay.RetentionJob;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs the relay every {@code relay.poll-interval} and the retention job every
 * {@code retention.purge-interval} (fixed delays), only when publishing is enabled.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "petclinic.integration.publisher", name = "enabled", havingValue = "true")
public class RelaySchedulingConfig implements SchedulingConfigurer {

    private final OutboxRelay relay;
    private final RetentionJob retention;
    private final IntegrationProperties properties;

    public RelaySchedulingConfig(OutboxRelay relay, RetentionJob retention, IntegrationProperties properties) {
        this.relay = relay;
        this.retention = retention;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(relay::runOnce, properties.relay().pollInterval());
        registrar.addFixedDelayTask(retention::purge, properties.retention().purgeInterval());
    }
}
