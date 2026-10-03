package com.beomsu.becommerce.personalization.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 커머스 회원 ↔ H&amp;M 고객 매핑 한 줄(V66). 서빙은 {@code userId → hm_customer_id} 방향으로만 읽는다.
 *
 * <p><b>행이 있으면 매핑, 없으면 폴백</b>이라는 표의 규칙을 그대로 옮긴다 — 매핑 없음을 NULL 이 아니라
 * <b>행 없음</b>으로 표현하므로, 조회 실패를 위한 별도 분기를 두지 않는다. 그래서 이 엔티티는
 * {@code created_at} 을 매핑하지 않는다(쓰기 주체는 파이프라인이고 서빙은 읽기만 한다).
 *
 * <p>{@code hm_customer_id} 는 원본이 64자 hex 문자열이라 <b>문자열로 유지한다</b>(V66 주석).
 */
@Entity
@Table(name = "personalization_user_map")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PersonalizationUserMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "hm_customer_id", nullable = false, length = 64)
    private String hmCustomerId;
}
