package org.remus.giteabot.eventhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Retry driver and crash recovery for webhook deliveries: picks up
 * {@code PENDING} rows (e.g. stranded by a restart) and due {@code RETRYING}
 * rows ({@code nextAttemptAt <= now}) in batches of 100 and hands them to the
 * {@link EventHookDeliveryWorker}. Leftover rows are picked up on the next
 * tick.
 *
 * <p>A {@code PENDING} row whose async attempt is still in flight can be
 * dispatched a second time (double-dispatch window); the worker's status
 * re-check makes the duplicate a no-op in practice. Delivery is at-least-once,
 * not exactly-once — that is the documented contract.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventHookRetrySweeper {

    private static final int SWEEP_BATCH_SIZE = 100;

    private final EventHookProperties properties;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookDeliveryWorker worker;

    @Scheduled(fixedDelayString = "#{@eventHookProperties.sweeperInterval.toMillis()}",
            initialDelayString = "15000")
    public void sweep() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<EventHookDelivery> due = deliveryRepository.findDueDeliveries(
                    Instant.now(), PageRequest.of(0, SWEEP_BATCH_SIZE));
            for (EventHookDelivery d : due) {
                worker.deliverAsync(d.getId());
            }
        } catch (Exception e) {
            log.error("Event-hook sweep failed: {}", e.getMessage(), e);
        }
    }
}
