package com.beomsu.becommerce.subscription.dunning;

import com.beomsu.becommerce.subscription.internal.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DunningSchedulerTest {

    @Test
    @DisplayName("run()은 SubscriptionService.runBillingCycle(today)로 위임한다")
    void delegatesToService() {
        SubscriptionService service = mock(SubscriptionService.class);
        when(service.runBillingCycle(any(LocalDate.class))).thenReturn(5);

        new DunningScheduler(service).run();

        verify(service).runBillingCycle(any(LocalDate.class));
    }
}
