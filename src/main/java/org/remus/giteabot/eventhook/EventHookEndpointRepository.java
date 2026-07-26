package org.remus.giteabot.eventhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventHookEndpointRepository extends JpaRepository<EventHookEndpoint, Long> {

    List<EventHookEndpoint> findByEnabledTrue();
}
