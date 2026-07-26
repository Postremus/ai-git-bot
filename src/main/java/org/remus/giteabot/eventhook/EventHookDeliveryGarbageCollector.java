package org.remus.giteabot.eventhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Retention garbage collector for webhook deliveries, mirroring
 * {@code AuditLogGarbageCollector}'s cron + package-private test-seam pattern.
 *
 * <p>Policy: keep the newest {@code eventhook.retention.keep-last} deliveries
 * per endpoint regardless of status; terminal rows ({@code SUCCESS}/{@code
 * FAILED}) older than that window are deleted in one bounded statement per
 * endpoint. In-flight rows ({@code PENDING}/{@code RETRYING}) are never
 * deleted — they still count toward the newest-N window (a retrying delivery
 * is recent history an admin wants to see), and they are self-limiting because
 * the worker marks them {@code FAILED} once retry attempts are exhausted,
 * after which the GC prunes them like any other terminal row.
 *
 * <p>The cutoff is id-based, which avoids timestamp-tie ambiguity and keeps
 * the delete a single statement.
 */
@Slf4j
@Component
public class EventHookDeliveryGarbageCollector {

    private final EventHookEndpointRepository endpointRepository;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookProperties properties;

    public EventHookDeliveryGarbageCollector(EventHookEndpointRepository endpointRepository,
                                             EventHookDeliveryRepository deliveryRepository,
                                             EventHookProperties properties) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
    }

    /** Cron-fired entry point; default 04:41 server time (staggered from the other GC jobs), tunable via eventhook.retention.gc-cron. */
    @Scheduled(cron = "${eventhook.retention.gc-cron:0 41 4 * * *}")
    public void runGarbageCollection() {
        int deleted = collectOnce();
        if (deleted > 0) {
            log.info("EventHookDeliveryGarbageCollector: pruned {} webhook deliver(ies), keepLast={}",
                    deleted, properties.getRetention().getKeepLast());
        }
    }

    /** Single GC pass over all endpoints; returns rows deleted. Package-private for deterministic tests. */
    @Transactional
    int collectOnce() {
        int keep = Math.max(0, properties.getRetention().getKeepLast());
        int deleted = 0;
        for (EventHookEndpoint endpoint : endpointRepository.findAll()) {
            List<EventHookDelivery> newest = deliveryRepository.findByEndpointIdOrderByIdDesc(
                    endpoint.getId(), PageRequest.of(0, Math.max(keep, 1)));
            if (newest.isEmpty()) {
                continue;
            }
            if (keep == 0) {
                deleted += deliveryRepository.deleteTerminalBefore(
                        endpoint.getId(), newest.getLast().getId() + 1);
            } else if (newest.size() == keep) {
                // Page full: there may be older rows beyond it — delete terminal rows
                // older than the Nth-newest. A short page means <= keep exist; skip.
                deleted += deliveryRepository.deleteTerminalBefore(
                        endpoint.getId(), newest.getLast().getId());
            }
        }
        return deleted;
    }
}
