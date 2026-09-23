package com.beomsu.becommerce.reconciliation.internal;

import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 대사 서비스 — 내부 기록 적재 + 결정적 매칭 엔진. "결제의 최종 방어선".
 *
 * <p>적재: 결제 승인 이벤트를 내부 기대치(InternalRecord)로 쌓는다(orderNo 유니크로 멱등).
 * 매칭: 내부 기록과 PG 정산 파일(외부)을 orderNo로 대조해 4분류(일치/내부만/외부만/금액불일치)한다.
 *
 * <p>매칭 엔진은 <b>결정적(deterministic)</b>이다 — 같은 입력(내부·외부 집합)이면 항상 같은 결과.
 * orderNo 정렬 순서로 결과를 만들어 재현·감사 가능하게 한다.
 */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final InternalRecordRepository internalRecords;
    private final ReconciliationResultRepository results;

    /**
     * 직전 실행에서 버린 중복 행 수. 실행 요약이 읽어 화면에 올린다.
     *
     * <p><b>왜 필드인가</b>: 반환형을 바꾸면 이 메서드를 쓰는 곳이 전부 바뀐다.
     * 대사 실행은 어드민 한 곳에서 <b>동기적으로</b> 부르므로 이 한 값을 남겨 읽는다.
     * 동시에 여러 번 돌리는 경로가 생기면 그때 반환형으로 옮겨야 한다 —
     * 지금은 없고, 없는 문제를 위해 시그니처를 흔들지 않는다.
     */
    private volatile int lastRunDuplicateRows;

    public int lastRunDuplicateRows() {
        return lastRunDuplicateRows;
    }

    /**
     * 결제 승인 이벤트를 내부 기록으로 적재한다. 멱등: 같은 orderNo가 이미 있으면 건너뛴다.
     */
    @Transactional
    public void recordInternal(PaymentConfirmedEvent event) {
        if (internalRecords.existsByOrderNo(event.orderNo())) {
            return; // 멱등: 이미 적재함
        }
        internalRecords.save(
                InternalRecord.of(event.orderNo(), event.amount(), event.approvedAt()));
    }

    /**
     * 결제 취소를 내부 기록에 <b>별도 행</b>으로 쌓는다 (ADR-013).
     *
     * <p><b>왜 덮어쓰지 않나</b>: 예전에는 원 거래일 행의 금액을 취소 후 잔액으로 갈아끼웠다.
     * 그러면 <b>이미 확정된 대사 판정과 스냅샷이 갈라진다</b> — 실제로 재현했다.
     * 10,000원 승인 → 대사 MATCHED 확정 → 3,000원 취소 후, 스냅샷은 7,000인데 판정은 10,000이었다.
     * 취소가 며칠 뒤에 오면 그 날짜를 다시 대사할 계기가 없어 아무도 모른 채 남는다.
     *
     * <p>그리고 PG는 환불을 <b>취소가 일어난 날의 파일에 별도 행</b>으로 싣는다. 덮어쓰기 방식은
     * 그날 대사에서 그 환불 행을 {@code EXTERNAL_ONLY}로 잡아 예외 큐를 오염시킨다.
     * 별도 행으로 쌓으면 양쪽이 같은 날짜에서 만난다.
     *
     * <p><b>승인 스냅샷이 없으면 버리지 않고 예외를 던진다.</b> 취소는 승인된 결제에만 발행되므로
     * 스냅샷이 없다는 건 "대사 대상이 아니다"가 아니라 <b>순서 역전</b>이다. 두 리스너가 같은
     * 이벤트 레지스트리 위에 있어, 재발행이나 재시도가 끼면 취소가 승인보다 먼저 처리될 수 있다.
     * 그때 조용히 넘어가면 <b>그 취소가 영영 사라진다</b>. 예외를 던져 미완료로 남기면
     * 아웃박스가 다시 배달하고, 그때는 스냅샷이 있다.
     *
     * <p>중복 소비는 {@code (orderNo, seq)} 유니크가 막는다.
     */
    @Transactional
    public void reflectCancellation(PaymentCanceledEvent event) {
        if (internalRecords.findByOrderNo(event.orderNo()).isEmpty()) {
            throw new ReconciliationException("RECON_SNAPSHOT_NOT_READY",
                    "승인 스냅샷이 아직 없어 취소를 반영할 수 없습니다. 재배달 대기: " + event.orderNo());
        }
        if (internalRecords.existsByOrderNoAndSeq(event.orderNo(), event.cancelSeq())) {
            return;   // 멱등: 같은 취소가 이미 쌓였다
        }
        internalRecords.save(InternalRecord.canceled(
                event.orderNo(), event.cancelAmount(), event.cancelSeq(), event.canceledAt()));
    }

    /**
     * 대사 매칭 엔진 — 내부 기록 전체와 외부(PG 정산 파일) 목록을 orderNo로 대조해 4분류한다.
     *
     * <ul>
     *   <li>양쪽에 있고 금액 같음 → MATCHED (자동 종결)</li>
     *   <li>양쪽에 있고 금액 다름 → AMOUNT_MISMATCH (사람 확인)</li>
     *   <li>내부에만 → INTERNAL_ONLY (PG 누락 의심)</li>
     *   <li>외부에만 → EXTERNAL_ONLY (내부 유실 의심)</li>
     * </ul>
     *
     * <p><b>범위는 거래일 하나다.</b> 내부 기록 전체와 비교하면 어제치 파일을 올릴 때마다 그제 이전
     * 전건이 "PG에 없음"으로 잡혀 예외 큐가 첫 주에 수만 건이 된다. 대사는 날짜 단위로만 성립한다.
     *
     * <p>결정적: 모든 orderNo를 정렬한 순서로 판정하므로, 같은 입력이면 결과의 내용·순서가 항상 같다.
     * <b>재실행도 멱등이다</b> — 그 거래일의 이전 판정을 지우고 다시 쓰므로, 운영자가 같은 파일을
     * 두 번 올려도 예외 큐가 두 배가 되지 않는다.
     */
    @Transactional
    public List<ReconciliationResult> reconcile(LocalDate tradeDate, List<ExternalRecord> external) {
        // 내부도 <합산>한다 (ADR-013). 취소가 별도 행으로 쌓이므로 한 주문이 여러 행일 수 있다.
        //   8/28  ord-1  +10,000  seq=0   (승인)
        //   8/30  ord-1   -3,000  seq=1   (취소)
        // 다만 같은 거래일 안에서만 합산된다 — 8/30 대사는 -3,000만 본다. 그게 맞다.
        // PG도 그날 파일에 환불 -3,000을 실으므로 양쪽이 같은 날짜에서 만난다.
        Map<String, Long> internalMap = new LinkedHashMap<>();
        for (InternalRecord record : internalRecords.findByTradeDate(tradeDate, chunk())) {
            internalMap.merge(record.getOrderNo(), record.getAmount(), Math::addExact);
        }
        // 같은 orderNo가 여러 행으로 올 수 있다. 다만 두 종류가 있고 <b>대응이 정반대</b>다.
        //
        //   정상 — 승인·환불·챠지백이 각각 별도 행. 원거래와 같은 참조번호를 공유하므로 합산해야 맞다
        //          (Adyen은 Refunded/Chargeback을 별도 journal type으로 낸다)
        //   오류 — 같은 거래가 중복 기록됨. 합산하면 금액이 부풀어 <b>불일치를 오히려 감춘다</b>
        //          (Uber 사례: "$100 거래가 두 번 기록되면 $200으로 집계")
        //
        // 이 둘은 PG의 <b>행 단위 거래 식별자</b>로만 갈린다. 그래서 식별자가 있으면 먼저 중복을
        // 걸러내고, 그다음 합산한다. 식별자가 없는 파일은 걸러낼 수 없으므로 합산만 한다 —
        // 그 경우 중복이 있으면 못 잡는다는 것을 알고 쓰는 것이다.
        Map<String, Long> externalMap = new LinkedHashMap<>();
        Set<String> seenTransactionIds = new HashSet<>();
        int duplicateRows = 0;
        for (ExternalRecord record : external) {
            if (record.transactionId() != null && !seenTransactionIds.add(record.transactionId())) {
                // 같은 거래 식별자가 두 번 왔다. 합산하면 금액이 부풀므로 버린다.
                duplicateRows++;
                log.warn("정산 파일 중복 행 무시 tradeDate={} orderNo={} transactionId={}",
                        tradeDate, record.orderNo(), record.transactionId());
                continue;
            }
            externalMap.merge(record.orderNo(), record.amount(), Math::addExact);
        }
        if (duplicateRows > 0) {
            // 조용히 넘기지 않는다 — 중복이 나온다는 것 자체가 PG 파일 생성에 문제가 있다는 신호다.
            log.warn("정산 파일에 중복 행 {}건 tradeDate={}", duplicateRows, tradeDate);
        }
        // 로그만으로는 <확정하는 사람>에게 닿지 않는다. 실행 요약에 실어 화면까지 보낸다.
        lastRunDuplicateRows = duplicateRows;

        // 양쪽 키의 합집합을 정렬 → 결정적 순서
        TreeSet<String> orderNos = new TreeSet<>();
        orderNos.addAll(internalMap.keySet());
        orderNos.addAll(externalMap.keySet());

        List<ReconciliationResult> reconciled = new ArrayList<>();
        for (String orderNo : orderNos) {
            Long internalAmount = internalMap.get(orderNo);
            Long externalAmount = externalMap.get(orderNo);

            ReconciliationResult result;
            if (internalAmount != null && externalAmount != null) {
                result = internalAmount.longValue() == externalAmount.longValue()
                        ? ReconciliationResult.matched(tradeDate, orderNo, internalAmount)
                        : ReconciliationResult.amountMismatch(tradeDate, orderNo, internalAmount, externalAmount);
            } else if (internalAmount != null) {
                result = ReconciliationResult.internalOnly(tradeDate, orderNo, internalAmount);
            } else {
                result = ReconciliationResult.externalOnly(tradeDate, orderNo, externalAmount);
            }
            reconciled.add(result);
        }

        // 재실행 멱등: 그 거래일 판정을 통째로 갈아끼운다. (trade_date, order_no) 유니크와 짝이다.
        results.deleteByTradeDate(tradeDate);
        results.flush();
        results.saveAll(reconciled);
        return reconciled;
    }

    /**
     * 배치 한 번이 읽는 상한. 남은 것은 다음 주기가 가져간다.
     *
     * <p><b>필드에 기본값을 둔다.</b> {@code @Value} 는 스프링이 만들어 줄 때만 채워지는데,
     * 단위 테스트는 이 서비스를 직접 생성한다. 초기값이 없으면 0 이 되어 페이지 크기가
     * 0 이라고 터진다 — 실제로 그렇게 깨졌다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.batch.read-chunk-size:500}")
    private int readChunkSize = 500;

    /**
     * <b>설정이 0 이나 음수여도 배치를 죽이지 않는다.</b> 잘못된 설정 하나로 돈을 다루는
     * 배치가 멈추는 것보다, 기본값으로 도는 편이 낫다.
     */
    private org.springframework.data.domain.Pageable chunk() {
        return org.springframework.data.domain.PageRequest.of(0, readChunkSize > 0 ? readChunkSize : 500);
    }
}
