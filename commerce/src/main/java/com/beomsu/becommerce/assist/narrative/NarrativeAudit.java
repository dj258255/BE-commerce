package com.beomsu.becommerce.assist.narrative;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 서술을 만든 기록. <b>모델이 무엇을 썼는지 남긴다.</b>
 *
 * <p><b>왜 남기나</b>: 운영자가 이 문장을 읽고 대사를 확정한다. 나중에 그 확정을 되짚을 때
 * "그때 화면에 뭐가 떠 있었나"를 답할 수 있어야 한다. 남기지 않으면 사람이 무엇을 보고
 * 판단했는지 영영 모른다.
 *
 * <p><b>무엇을 남기고 무엇을 안 남기나</b>
 * <ul>
 *   <li><b>남긴다</b> — 모델 이름·출력·판정·시각·주문번호. 모델 출력은 <b>재현되지 않는다</b></li>
 *   <li><b>안 남긴다</b> — 프롬프트 본문. 사실 묶음에서 <b>결정적으로 재구성</b>된다.
 *       대신 사실 개수와 완전성 플래그를 남겨 그때와 지금이 같은 입력인지 대조한다</li>
 * </ul>
 * 재구성되는 것을 또 저장하면 두 곳이 언젠가 갈라지고, 갈라지면 어느 쪽이 맞는지 알 수 없다 —
 * 이 프로젝트가 허용 금액 목록에서 이미 겪은 실패다.
 */
@Entity
@Table(name = "narrative_audits",
        indexes = @Index(name = "idx_narrative_audit_order", columnList = "orderNo, createdAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NarrativeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderNo;

    /** 무엇이 썼는가 — {@code template} 또는 {@code ollama:모델명}. 모델 버전이 여기 실린다. */
    @Column(nullable = false, length = 100)
    private String source;

    /** {@code narrated} · {@code abstained} · {@code unsourced_figures} · {@code no_facts} */
    @Column(nullable = false, length = 40)
    private String outcome;

    /** 실제로 나간 문장. 기권·폐기면 null 이다. */
    @Column(columnDefinition = "TEXT")
    private String output;

    /** 그때 몇 개의 사실을 보고 썼는가. 입력이 같았는지 대조하는 값이다. */
    @Column(nullable = false)
    private int factCount;

    @Column(nullable = false)
    private boolean factsComplete;

    @Column(nullable = false)
    private Instant createdAt;

    private NarrativeAudit(String orderNo, String source, String outcome, String output,
                           int factCount, boolean factsComplete) {
        this.orderNo = orderNo;
        this.source = source;
        this.outcome = outcome;
        this.output = output;
        this.factCount = factCount;
        this.factsComplete = factsComplete;
        this.createdAt = Instant.now();
    }

    static NarrativeAudit of(String orderNo, String source, String outcome, String output,
                             int factCount, boolean factsComplete) {
        return new NarrativeAudit(orderNo, source, outcome, output, factCount, factsComplete);
    }
}
