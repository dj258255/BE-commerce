package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.catalog.Stock;
import com.beomsu.becommerce.order.catalog.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * E4 실험의 <b>재고 변화 주입기</b> — "다른 사용자가 동시에 사고 입고된다"를 <b>실제 재고 행</b>으로 흉내 낸다.
 *
 * <p><b>왜 여기(order)에 있나</b>: 재고를 바꾸는 코드는 재고의 소유자(order) 안에 있어야 한다. 추천
 * 모듈이 재고 테이블을 직접 고치면 모듈 경계가 무너진다. 그래서 <b>바꾸는 것</b>은 order 가 하고,
 * <b>무엇을 바꿀지(실험 풀)</b>는 부르는 쪽이 id 목록으로 넘긴다 — 풀의 단일 출처는 추천 모듈의
 * {@code ItemPool} 이다.
 *
 * <p><b>무엇이 문제였나(①의 전말)</b>: 첫 구현은 "품절 하나 입고 + 팔리는 하나 소진"이었는데,
 * <b>같은 항목이 소진·해제를 왕복</b>해 품절 집합의 <b>구성</b>이 사실상 고정됐다. 소진이 "첫 번째
 * 팔리는 것"(=풀 앞쪽)을 집으면 그 항목이 곧 "첫 번째 품절된 것"이 되어, 입고가 방금 소진한 그것을
 * 도로 집었다. 크기는 6으로 고정됐지만 <b>바뀌는 것이 없었고</b>, 그래서 {@code NONE} 의 위반율이
 * 응답마다 정확히 같은 값(6.00)으로 나왔다 — 위반율이 정책 효과가 아니라 <b>고정된 정렬</b>을 재고 있었다.
 * 지금은 <b>입고한 항목보다 풀 순서에서 뒤에 있는</b> 팔리는 것을 소진한다(끝이면 앞으로 감는다).
 * 그래야 품절 창이 실제로 <b>돈다</b>.
 *
 * <p><b>동시 실행에 안전해야 하는 이유</b>: 변화 주입은 VU 3개가 동시에 돈다. 입고/소진을 조건부
 * UPDATE 로 만들어(실제로 상태를 바꾼 쪽만 성공) 크기가 흔들리지 않게 하고, 그래도 남는 드리프트는
 * 매 호출 끝에 <b>목표 K 로 자기 교정</b>한다 — ①의 목적이 "통제된 변수만 결론으로 쓴다"이므로
 * 통제 자체를 코드가 지킨다.
 *
 * <p><b>정직하게 적어 둘 것</b>: ① 이 id 들은 실험용으로 심은 합성 재고다(products 행이 없어 상점에는
 * 안 보인다). ② {@code version()} 은 앱 안의 카운터라 재기동하면 0으로 돌아간다 — 실험 한 런 안에서는
 * 맞다. ③ 변화율은 여전히 <b>우리가 정한다</b>.
 */
@Service
@ConditionalOnProperty(name = "app.recommendation.experiment.enabled", havingValue = "true")
public class ExperimentStockChurn {

    private static final Logger log = LoggerFactory.getLogger(ExperimentStockChurn.class);

    /** 입고 수량 — 0보다 크면 "팔 수 있다". 값 자체는 실험이 쓰지 않는다(품절 여부만 본다). */
    static final int RESTOCK_QUANTITY = 1000;

    private final StockRepository stockRepository;

    /** 가용성 변경 횟수 — {@code changesInWindow} 의 원천이다. 앱 안 카운터(재기동 시 0). */
    private final AtomicLong version = new AtomicLong();

    public ExperimentStockChurn(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
        log.info("E4 재고 변화 주입기가 켜졌다 — 실제 stock 행을 고친다(실험 풀은 부르는 쪽이 넘긴다)");
    }

    /** 한 번의 교체 결과 — 무엇을 입고했고 무엇을 소진했으며 몇 건이 실제로 바뀌었는가. */
    public record Swap(long released, long consumed, int changed) {
    }

    /**
     * <b>품절 집합을 목표 크기로 유지한 채 구성을 한 칸 돌린다</b>(E4 의 기본 변화 주입).
     *
     * <p>순서가 핵심이다: <b>먼저 입고하고, 그 뒤 풀 순서에서 뒤에 있는 팔리는 것을 소진한다.</b>
     * 소진을 먼저 하면 방금 소진한 항목이 "첫 번째 품절"이 되어 입고가 도로 그것을 집는다(위 javadoc).
     * 풀 끝에 도달하면 앞으로 감아 계속 돈다 — 어떤 것이 품절인가가 <b>계속 바뀐다</b>.
     *
     * <p>입고·소진 모두 <b>조건부</b>라 동시 실행에서 실제로 바꾼 쪽만 성공한다. 그래도 드리프트가
     * 남으면 {@link #enforceTarget} 이 목표로 되돌린다.
     */
    @Transactional
    public Swap swapOne(List<Long> pool, int target) {
        Set<Long> unavailable = currentUnavailable(pool);
        long released = -1;
        long consumed = -1;
        int changes = 0;

        Long toRelease = firstUnavailable(pool, unavailable);
        Long toConsume = toRelease == null ? null : firstAvailableAfter(pool, unavailable, toRelease);

        if (toRelease != null && toConsume != null) {
            if (stockRepository.setQuantityIfSoldOut(toRelease, RESTOCK_QUANTITY) > 0) {
                released = toRelease;
                version.incrementAndGet();
                changes++;
            }
            if (stockRepository.sellOut(toConsume) > 0) {
                consumed = toConsume;
                version.incrementAndGet();
                changes++;
            }
        }
        changes += enforceTarget(pool, target);
        return new Swap(released, consumed, changes);
    }

    /**
     * <b>목표 K까지 품절을 만든다 — 결정적으로</b>(풀 앞에서 K개).
     *
     * <p>먼저 전부 입고한 뒤 앞에서 K개를 소진한다. "항상 24개 중 6개만 품절"이 이 호출로 세워지고,
     * 이후 {@link #swapOne}이 그 크기를 유지한 채 구성을 바꾼다. 무작위가 아니라 순서 고정이라
     * 런마다 같은 초기 상태에서 시작한다(비교가 성립하는 조건).
     */
    @Transactional
    public int primeTo(List<Long> pool, int target) {
        restockAll(pool);
        int k = Math.min(Math.max(target, 0), pool.size());
        for (int i = 0; i < k; i++) {
            if (stockRepository.sellOut(pool.get(i)) > 0) {
                version.incrementAndGet();
            }
        }
        return k;
    }

    /** 전부 입고 — 런 사이 초기화. 재고 행이 없으면 만든다(시드가 안 돌아간 환경 방어). */
    @Transactional
    public void restockAll(List<Long> pool) {
        Set<Long> existing = stockRepository.findByProductIdIn(pool).stream()
                .map(Stock::getProductId).collect(Collectors.toSet());
        List<Stock> missing = pool.stream()
                .filter(id -> !existing.contains(id))
                .map(id -> Stock.of(id, RESTOCK_QUANTITY))
                .toList();
        if (!missing.isEmpty()) {
            stockRepository.saveAllAndFlush(missing);
        }
        for (Long id : pool) {
            if (existing.contains(id)) {
                stockRepository.setQuantity(id, RESTOCK_QUANTITY);
            }
        }
        version.incrementAndGet();
    }

    /** 지금 품절된 개수 — 하네스가 <b>통제를 확인</b>하는 값이다(이게 K로 유지돼야 런이 유효하다). */
    @Transactional(readOnly = true)
    public int unavailableCount(List<Long> pool) {
        return currentUnavailable(pool).size();
    }

    /** 가용성이 바뀐 횟수(단조 증가). 창 안 변경량을 세는 데 쓴다. */
    public long version() {
        return version.get();
    }

    /**
     * 동시 실행으로 목표에서 벗어난 크기를 K 로 되돌린다.
     *
     * <p>조건부 UPDATE 로 대부분의 드리프트를 막지만, 두 스레드가 서로 다른 항목을 집는 사이에
     * 한 칸이 남을 수 있다. 그 잔여를 매 호출 끝에 지운다 — <b>크기가 흔들리면 이 런은 무효</b>이므로
     * (①의 전제) 여기서 지키지 않으면 나중에 아무도 못 지킨다.
     */
    private int enforceTarget(List<Long> pool, int target) {
        int changes = 0;
        int size = currentUnavailable(pool).size();
        while (size > target) {
            Long release = firstUnavailable(pool, currentUnavailable(pool));
            if (release == null || stockRepository.setQuantityIfSoldOut(release, RESTOCK_QUANTITY) == 0) {
                break;
            }
            version.incrementAndGet();
            changes++;
            size--;
        }
        while (size < target) {
            Long consume = firstAvailable(pool, currentUnavailable(pool));
            if (consume == null || stockRepository.sellOut(consume) == 0) {
                break;
            }
            version.incrementAndGet();
            changes++;
            size++;
        }
        return changes;
    }

    private Set<Long> currentUnavailable(List<Long> pool) {
        Map<Long, Integer> quantities = stockRepository.findByProductIdIn(pool).stream()
                .collect(Collectors.toMap(Stock::getProductId, Stock::getQuantity, (a, b) -> a));
        Set<Long> unavailable = new HashSet<>();
        for (Long id : pool) {
            Integer quantity = quantities.get(id);
            if (quantity == null || quantity <= 0) {
                unavailable.add(id);
            }
        }
        return unavailable;
    }

    private static Long firstUnavailable(List<Long> pool, Set<Long> unavailable) {
        for (Long id : pool) {
            if (unavailable.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private static Long firstAvailable(List<Long> pool, Set<Long> unavailable) {
        for (Long id : pool) {
            if (!unavailable.contains(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * {@code after} <b>보다 풀 순서에서 뒤에 있는</b> 팔리는 것. 끝이면 앞으로 감는다.
     *
     * <p>감을 때 {@code after} 자신은 건너뛴다 — 그것은 방금 입고한 항목이라 소진하면 제자리로
     * 돌아간다(그 왕복이 ①의 실패였다).
     */
    private static Long firstAvailableAfter(List<Long> pool, Set<Long> unavailable, Long after) {
        int from = pool.indexOf(after);
        for (int i = from + 1; i < pool.size(); i++) {
            if (!unavailable.contains(pool.get(i))) {
                return pool.get(i);
            }
        }
        for (int i = 0; i < from; i++) {
            if (!unavailable.contains(pool.get(i))) {
                return pool.get(i);
            }
        }
        return null;
    }
}
