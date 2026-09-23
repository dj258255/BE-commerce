package com.beomsu.becommerce.payment.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class PaymentRecoverySchedulerTest {

    @Test
    @DisplayName("run()은 PaymentRecoveryService.recoverUnknownPayments()로 위임한다")
    void delegatesToService() {
        PaymentRecoveryService service = mock(PaymentRecoveryService.class);
        when(service.recoverUnknownPayments()).thenReturn(2);

        new PaymentRecoveryScheduler(service).run();

        verify(service).recoverUnknownPayments();
    }
}
