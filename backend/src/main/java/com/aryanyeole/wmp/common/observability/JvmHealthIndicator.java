package com.aryanyeole.wmp.common.observability;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Phase 7: JVM heap/thread details on /actuator/health, alongside the
 * auto-configured "db" (DataSource) indicator — both feed Phase 8's pool-
 * exhaustion diagnosis, where a leaking connection pool is expected to
 * show up as rising thread count (blocked request threads) well before
 * heap pressure does. Registered as a bean named "jvm" (Spring Boot strips
 * the "HealthIndicator" suffix and lower-cases the rest to derive the
 * health component key), so its details appear under
 * response.components.jvm when management.endpoint.health.show-details
 * is "always" (already set in application.yml).
 *
 * DOWN at 90% heap used is a coarse, deliberately simple threshold — this
 * is visibility for a portfolio-project diagnosis exercise, not a tuned
 * production alerting rule.
 */
@Component("jvm")
public class JvmHealthIndicator implements HealthIndicator {

    private static final double DOWN_THRESHOLD = 0.90;

    @Override
    public Health health() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long usedMemory = totalMemory - runtime.freeMemory();
        double heapUsedRatio = (double) usedMemory / maxMemory;

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        Health.Builder builder = heapUsedRatio < DOWN_THRESHOLD ? Health.up() : Health.down();
        return builder
                .withDetail("heapUsedBytes", usedMemory)
                .withDetail("heapCommittedBytes", totalMemory)
                .withDetail("heapMaxBytes", maxMemory)
                .withDetail("heapUsedRatio", heapUsedRatio)
                .withDetail("liveThreadCount", threadBean.getThreadCount())
                .withDetail("availableProcessors", runtime.availableProcessors())
                .build();
    }
}
