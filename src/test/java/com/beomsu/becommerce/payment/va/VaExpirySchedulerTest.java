package com.beomsu.becommerce.payment.va;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VaExpirySchedulerTest {

    @Test
    @DisplayName("run()은 VirtualAccountService.expireOverdue(now)로 위임한다")
    void delegatesToService() {
        VirtualAccountService service = mock(VirtualAccountService.class);
        when(service.expireOverdue(any(Instant.class))).thenReturn(1);

        new VaExpiryScheduler(service).run();

        verify(service).expireOverdue(any(Instant.class));
    }
}
