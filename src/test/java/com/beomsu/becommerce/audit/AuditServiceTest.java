package com.beomsu.becommerce.audit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    @Test
    void recordsAppendOnlyEntry() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        AuditService service = new AuditService(repo);

        service.record("admin", "FORCE_CANCEL", "PAYMENT", "123", "CS 요청");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getActor()).isEqualTo("admin");
        assertThat(log.getAction()).isEqualTo("FORCE_CANCEL");
        assertThat(log.getTargetId()).isEqualTo("123");
    }
}
