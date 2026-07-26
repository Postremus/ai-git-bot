package org.remus.giteabot.eventhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Single entry point for outgoing-webhook publication, called from
 * workflow/agent code. Filters enabled endpoints by event-type subscription
 * and scope, persists one {@link EventHookDelivery} row per matching endpoint
 * (durable, cheap, inside the caller's transaction), then hands the row id to
 * the {@link EventHookDeliveryWorker} for asynchronous dispatch.
 *
 * <p>Never throws into the caller — a broken webhook subsystem must never
 * break the workflow that emits the event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventHookPublisher {

    private final EventHookProperties properties;
    private final EventHookEndpointRepository endpointRepository;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookDeliveryWorker deliveryWorker;
    private final ObjectMapper objectMapper;

    public void publish(EventHookEventType type, Bot bot, String repoOwner, String repoName,
                        Long prNumber, Long issueNumber, Map<String, Object> data) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            for (EventHookEndpoint endpoint : endpointRepository.findByEnabledTrue()) {
                if (!endpoint.isSubscribedTo(type)
                        || !endpoint.matchesScope(bot.getId(), repoOwner, repoName)) {
                    continue;
                }
                String uuid = UUID.randomUUID().toString();
                EventHookPayload payload = EventHookPayload.of(uuid, type, bot, repoOwner,
                        repoName, prNumber, issueNumber, data);
                String json = objectMapper.writeValueAsString(payload);
                if (json.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPayloadBytes()) {
                    log.warn("Skipping oversized webhook payload ({} bytes) for {}", json.length(), type);
                    continue;
                }
                EventHookDelivery delivery = new EventHookDelivery();
                delivery.setDeliveryUuid(uuid);
                delivery.setEndpointId(endpoint.getId());
                delivery.setEventType(type.wireValue());
                delivery.setPayloadJson(json);
                delivery.setStatus(DeliveryStatus.PENDING);
                delivery = deliveryRepository.save(delivery);
                // @Async on a separate bean — self-invocation would not be proxied.
                deliveryWorker.deliverAsync(delivery.getId());
            }
        } catch (Exception e) {
            log.error("Failed to publish event-hook event {}: {}", type, e.getMessage(), e);
        }
    }
}
