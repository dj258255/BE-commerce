package com.beomsu.becommerce.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 기록 서비스. 위험/민감 행위를 append-only로 남긴다.
 * 어드민 강제취소·수기 대사·개인정보 언마스킹 등이 이 서비스를 호출한다.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;

    @Transactional
    public void record(String actor, String action, String targetType, String targetId, String detail) {
        repository.save(AuditLog.of(actor, action, targetType, targetId, detail));
    }
}
