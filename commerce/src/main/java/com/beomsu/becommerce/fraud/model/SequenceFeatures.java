package com.beomsu.becommerce.fraud.model;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 한 카드의 최근 창에서 뽑은 피처. <b>여덟 개 전부 규칙 여섯이 못 보는 축이다.</b>
 *
 * <p>규칙은 <b>건 하나</b>만 본다. 금액이 임계를 넘었나, 1분 안에 몇 번 왔나. 그래서
 * 임계 바로 밑에 붙여 천천히 밀면 아무것도 안 걸린다. 여기 피처는 <b>건들 사이의 관계</b>를 본다.
 *
 * <table>
 *   <caption>피처와 그것이 겨냥한 규칙의 빈틈</caption>
 *   <tr><th>피처</th><th>규칙이 못 보는 것</th></tr>
 *   <tr><td>{@code nearThresholdRatio}</td><td>고액 임계 바로 밑에 붙여 쪼개는 것</td></tr>
 *   <tr><td>{@code microCount}</td><td>카드가 살아 있는지 떠보는 소액 시도</td></tr>
 *   <tr><td>{@code escalation}</td><td>승인될 때마다 금액을 올리는 한도 탐색</td></tr>
 *   <tr><td>{@code deviceChurn}</td><td>매번 기기를 바꿔 기기 속도 규칙을 피하는 것</td></tr>
 *   <tr><td>{@code ipChurn}</td><td>같은 이유로 IP 를 바꾸는 것</td></tr>
 *   <tr><td>{@code nightRatio}</td><td>규칙에 시간 축이 아예 없다</td></tr>
 *   <tr><td>{@code windowCount}</td><td>1분 창을 넘겨 천천히 미는 것</td></tr>
 *   <tr><td>{@code amountToMedian}</td><td>그 카드의 평소 씀씀이라는 기준이 없다</td></tr>
 * </table>
 *
 * <p><b>전부 0~1 로 눌러 담는다.</b> 로지스틱 회귀에 넣을 것이라 단위가 섞이면 가중치가
 * 크기 차이를 흡수해 버려 어느 피처가 일하는지 못 읽는다.
 *
 * @param windowCount        창 안의 건수 (0~1 로 누름)
 * @param amountToMedian     이번 금액 ÷ 그 카드의 과거 중앙값
 * @param nearThresholdRatio 고액 임계의 90~100% 구간에 든 건의 비율
 * @param microCount         소액 시도 비율
 * @param escalation         금액이 단조 증가한 정도
 * @param deviceChurn        건수 대비 서로 다른 기기 수
 * @param ipChurn            건수 대비 서로 다른 IP 수
 * @param nightRatio         00~06 시 결제 비율
 */
public record SequenceFeatures(
        double windowCount,
        double amountToMedian,
        double nearThresholdRatio,
        double microCount,
        double escalation,
        double deviceChurn,
        double ipChurn,
        double nightRatio) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 이 금액 이하를 <b>떠보는 시도</b>로 본다. 실제 상품 결제로 보기 어려운 크기다. */
    public static final long MICRO_AMOUNT = 1_000L;

    /** 건수를 0~1 로 누를 때의 포화점. 이보다 많이 와도 1 이다. */
    private static final double COUNT_SATURATION = 20.0;

    /** 금액 배수를 0~1 로 누를 때의 포화점. 평소의 10배면 1 이다. */
    private static final double RATIO_SATURATION = 10.0;

    /**
     * 창과 이번 건으로 피처를 만든다.
     *
     * @param window         이번 건을 <b>포함한</b> 그 카드의 최근 결제들. 순서는 상관없다
     * @param current        이번 건
     * @param amountThreshold 고액 규칙의 임계. 임계 바로 밑을 재려면 그 값이 있어야 한다
     */
    public static SequenceFeatures of(List<TxnRecord> window, TxnRecord current, long amountThreshold) {
        if (window == null || window.isEmpty()) {
            // 창이 비면 이번 건 하나만 있는 것으로 본다. 0 벡터를 주면 첫 거래가 전부
            // "정상 아님" 쪽으로 쏠린다.
            window = List.of(current);
        }
        List<TxnRecord> sorted = new ArrayList<>(window);
        sorted.sort(Comparator.comparing(TxnRecord::at));
        int n = sorted.size();

        long near = 0, micro = 0, night = 0;
        Set<String> devices = new HashSet<>();
        Set<String> ips = new HashSet<>();
        List<Long> amounts = new ArrayList<>(n);

        for (TxnRecord t : sorted) {
            amounts.add(t.amount());
            // 임계의 90% 이상이면서 안 넘긴 구간. 넘긴 것은 규칙이 이미 잡는다.
            if (t.amount() >= amountThreshold * 9 / 10 && t.amount() < amountThreshold) {
                near++;
            }
            if (t.amount() <= MICRO_AMOUNT) {
                micro++;
            }
            int hour = t.at().atZone(KST).getHour();
            if (hour < 6) {
                night++;
            }
            if (t.deviceId() != null) {
                devices.add(t.deviceId());
            }
            if (t.ip() != null) {
                ips.add(t.ip());
            }
        }

        // 이번 건을 뺀 과거의 중앙값. 과거가 없으면 이번 금액을 기준으로 둬 배수 1 이 된다.
        List<Long> past = new ArrayList<>(amounts);
        past.remove(Long.valueOf(current.amount()));
        double median = past.isEmpty() ? current.amount() : median(past);
        double ratio = median <= 0 ? 1.0 : current.amount() / median;

        return new SequenceFeatures(
                squash(n, COUNT_SATURATION),
                squash(ratio, RATIO_SATURATION),
                (double) near / n,
                (double) micro / n,
                escalationOf(amounts),
                (double) devices.size() / n,
                (double) ips.size() / n,
                (double) night / n);
    }

    /**
     * 금액이 계단처럼 오르고 있는가. <b>0 = 안 오름, 1 = 매번 오름.</b>
     *
     * <p>한도를 떠보는 쪽은 승인될 때마다 금액을 올린다. 각 건은 임계 밑이라 규칙에 안 걸리는데,
     * 이어 놓고 보면 한 방향이다.
     */
    private static double escalationOf(List<Long> amounts) {
        if (amounts.size() < 2) {
            return 0;
        }
        long up = 0;
        for (int i = 1; i < amounts.size(); i++) {
            if (amounts.get(i) > amounts.get(i - 1)) {
                up++;
            }
        }
        return (double) up / (amounts.size() - 1);
    }

    private static double median(List<Long> values) {
        List<Long> v = new ArrayList<>(values);
        v.sort(Long::compare);
        int m = v.size() / 2;
        return v.size() % 2 == 1 ? v.get(m) : (v.get(m - 1) + v.get(m)) / 2.0;
    }

    /** {@code 0..saturation} 을 {@code 0..1} 로 누른다. 넘으면 1 이다. */
    private static double squash(double value, double saturation) {
        return Math.min(1.0, Math.max(0.0, value / saturation));
    }

    /** 가중치를 곱할 순서. 이 배열의 순서가 곧 모델 가중치의 순서다. */
    public double[] toArray() {
        return new double[]{windowCount, amountToMedian, nearThresholdRatio, microCount,
                escalation, deviceChurn, ipChurn, nightRatio};
    }

    /** 화면과 로그에서 어느 피처가 얼마였는지 읽으려면 이름이 있어야 한다. */
    public static final List<String> NAMES = List.of(
            "windowCount", "amountToMedian", "nearThresholdRatio", "microCount",
            "escalation", "deviceChurn", "ipChurn", "nightRatio");
}
