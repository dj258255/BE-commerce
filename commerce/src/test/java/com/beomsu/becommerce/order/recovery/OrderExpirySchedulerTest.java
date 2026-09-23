package com.beomsu.becommerce.order.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderExpirySchedulerTest {

    @Test
    @DisplayName("run()은 OrderExpiryService.expireOverdue(now)로 위임한다")
    void delegatesToService() {
        OrderExpiryService service = mock(OrderExpiryService.class);
        when(service.expireOverdue(any(Instant.class))).thenReturn(3);

        new OrderExpiryScheduler(service).run();

        verify(service).expireOverdue(any(Instant.class));
    }
}
