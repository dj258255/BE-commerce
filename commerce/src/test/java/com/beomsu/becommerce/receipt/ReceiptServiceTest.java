package com.beomsu.becommerce.receipt;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReceiptServiceTest {

    private final CashReceiptRepository repository = mock(CashReceiptRepository.class);
    private final ReceiptService service = new ReceiptService(repository);

    @Test
    void issueCashReceiptSavesIssued() {
        when(repository.save(any(CashReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

        service.issueCashReceipt("order-1", 10_000, "DEDUCTION");

        ArgumentCaptor<CashReceipt> captor = ArgumentCaptor.forClass(CashReceipt.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
    }

    @Test
    void cancelByOrderCancelsAllFound() {
        CashReceipt r = CashReceipt.request("order-1", 10_000, "DEDUCTION");
        r.markIssued("cr-1");
        when(repository.findByOrderNo("order-1")).thenReturn(List.of(r));

        int count = service.cancelByOrder("order-1");

        assertThat(count).isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(CashReceiptStatus.CANCELED);
        verify(repository).saveAll(any());
    }
}
