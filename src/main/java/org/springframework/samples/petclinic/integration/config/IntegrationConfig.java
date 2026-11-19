package org.springframework.samples.petclinic.integration.config;

import java.time.Clock;
import java.util.Random;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.samples.petclinic.integration.relay.BackoffPolicy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BackoffPolicy backoffPolicy(IntegrationProperties properties) {
        Random random = new Random();
        return new BackoffPolicy(properties.retry(), random::nextDouble);
    }
}
