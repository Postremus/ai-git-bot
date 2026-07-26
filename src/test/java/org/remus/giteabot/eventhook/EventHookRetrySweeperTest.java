package org.remus.giteabot.eventhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventHookRetrySweeperTest {

    @Mock
    private EventHookDeliveryRepository deliveryRepository;
    @Mock
    private EventHookDeliveryWorker worker;

    private EventHookProperties properties;
    private EventHookRetrySweeper sweeper;

    @BeforeEach
    void setUp() {
        properties = new EventHookProperties();
        sweeper = new EventHookRetrySweeper(properties, deliveryRepository, worker);
    }

    private EventHookDelivery delivery(long id) {
        EventHookDelivery delivery = new EventHookDelivery();
        delivery.setId(id);
        return delivery;
    }

    @Test
    void sweep_dueDeliveries_areDispatched() {
        when(deliveryRepository.findDueDeliveries(any(), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(delivery(1L), delivery(2L)));

        sweeper.sweep();

        verify(worker).deliverAsync(1L);
        verify(worker).deliverAsync(2L);
    }

    @Test
    void sweep_noDueDeliveries_dispatchesNothing() {
        when(deliveryRepository.findDueDeliveries(any(), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of());

        sweeper.sweep();

        verify(worker, never()).deliverAsync(any());
    }

    @Test
    void sweep_disabled_doesNothing() {
        properties.setEnabled(false);

        sweeper.sweep();

        verifyNoInteractions(deliveryRepository, worker);
    }

    @Test
    void sweep_repositoryThrows_doesNotPropagate() {
        when(deliveryRepository.findDueDeliveries(any(), any(PageRequest.class)))
                .thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> sweeper.sweep());
    }
}
