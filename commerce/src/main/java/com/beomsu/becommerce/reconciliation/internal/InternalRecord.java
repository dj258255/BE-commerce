package com.beomsu.becommerce.reconciliation.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 내부 기록 — 대사에서 "이만큼 들어왔어야 한다"는 결제 기대치.
 *
 * <p>결제 승인 이벤트를 구독해 쌓는다. {@code orderNo} 유니크로 같은 주문이 두 번 쌓이는 것을
 * DB가 차단한다 — 적재 멱등성. 대사 시 이 기록과 PG 정산 파일을 orderNo로 매칭한다.
 */
@Entity
@Table(name = "internal_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_internal_record_order_seq", columnNames = {"orderNo", "seq"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InternalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private long amount;

    /**
     * 거래일(KST). <b>대사를 이 날짜 단위로 자른다.</b>
     *
     * <p>없으면 대사가 내부 기록 전체를 매번 비교하게 되고, 어제치 정산 파일 하나를 올릴 때마다
     * 그제 이전 전건이 "PG에 없음"으로 예외 큐에 쏟아진다. 큐가 무의미해지면 대사 자체가 무의미해진다.
     */
    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private Instant recordedAt;

    /** 대사 기준 타임존. 국내 PG 정산 파일은 KST 영업일로 끊긴다. */
    public static final ZoneId TRADE_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 0 = 승인, 1..N = 취소 순번 (ADR-013).
     *
     * <p>취소를 원 거래일 금액에 덮어쓰지 않고 <b>취소가 일어난 거래일의 별도 행</b>으로 쌓는다.
     * 덮어쓰면 이미 확정된 대사 판정과 스냅샷이 갈라지고(재현으로 확인), PG가 환불을
     * 취소일 파일에 별도 행으로 보내는 실제 형태와도 어긋난다.
     */
    @Column(nullable = false)
    private int seq;

    private InternalRecord(String orderNo, long amount, LocalDate tradeDate, int seq) {
        this.orderNo = orderNo;
        this.amount = amount;
        this.tradeDate = tradeDate;
        this.seq = seq;
        this.recordedAt = Instant.now();
    }

    /** 결제 승인 기대치를 내부 기록으로 만든다. 거래일은 승인 시각의 KST 날짜다. */
    public static InternalRecord of(String orderNo, long amount, Instant approvedAt) {
        return new InternalRecord(orderNo, amount, LocalDate.ofInstant(approvedAt, TRADE_ZONE), 0);
    }

    /**
     * 취소를 반영해 기대치를 낮춘다.
     *
     * <p>취소 후 잔액(절대값)으로 세팅한다. 델타를 빼면 같은 취소가 두 번 배달될 때 두 번 깎이지만,
     * 절대값이면 몇 번을 받아도 결과가 같다(멱등). 정산이 쓰는 방식과 같은 규칙이다.
     *
     * <p>이게 없으면 <b>취소된 모든 건이 영구 불일치로 남는다.</b> 부분취소는 매번 금액 불일치로,
     * 전액취소는 PG 정산 파일에서 빠지므로 "내부에만 있음"으로 잡힌다. 대사가 매일 같은 건을 다시
     * 올리면 예외 큐가 무의미해진다.
     */
    /**
     * 취소를 <b>별도 행</b>으로 만든다 (ADR-013). 금액은 음수다.
     *
     * <p>거래일은 취소가 일어난 시각 기준이다 — 원 승인일이 아니다.
     * PG도 환불을 취소일 파일에 싣기 때문에 양쪽이 같은 날짜에서 만난다.
     *
     * @param cancelSeq 취소 순번. {@code (orderNo, seq)} 유니크와 짝이라 같은 취소가
     *                  두 번 들어와도 한 행만 남는다
     */
    public static InternalRecord canceled(String orderNo, long cancelAmount, int cancelSeq, Instant canceledAt) {
        return new InternalRecord(orderNo, -cancelAmount,
                LocalDate.ofInstant(canceledAt, TRADE_ZONE), cancelSeq);
    }
}
