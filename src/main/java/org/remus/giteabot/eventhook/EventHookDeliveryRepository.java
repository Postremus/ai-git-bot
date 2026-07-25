package org.remus.giteabot.eventhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventHookDeliveryRepository extends JpaRepository<EventHookDelivery, Long> {

    @Query("SELECT d FROM EventHookDelivery d WHERE d.status = org.remus.giteabot.eventhook.DeliveryStatus.PENDING "
            + "OR (d.status = org.remus.giteabot.eventhook.DeliveryStatus.RETRYING AND d.nextAttemptAt <= :now) "
            + "ORDER BY d.id ASC")
    List<EventHookDelivery> findDueDeliveries(@Param("now") Instant now, Pageable pageable);

    List<EventHookDelivery> findTop50ByEndpointIdOrderByIdDesc(Long endpointId);
}
