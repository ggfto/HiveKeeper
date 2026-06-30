package io.hivekeeper.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduler so {@link FleetPoller}'s {@code @Scheduled} fleet scan runs. Gated to the
 * {@code postgres} profile (the poller needs persistence + cross-tenant enumeration), so the no-database
 * dev/demo stack starts no background scanner.
 */
@Configuration
@Profile("postgres")
@EnableScheduling
class SchedulingConfig {
}
