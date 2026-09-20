package com.beomsu.becommerce.fraud.model;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 라벨 붙은 카드별 결제 흐름을 만든다. <b>부정 패턴 여섯은 전부 규칙 여섯을 피해 가게 짰다.</b>
 *
 * <p><b>여기서 만든 라벨이 실제 부정거래라는 근거는 없다.</b> 우리가 정의한 패턴이다. 그래서
 * 이 코퍼스로 낼 수 있는 결론은 딱 하나다. <b>"규칙이 건 하나만 보기 때문에 건들 사이의 관계로
 * 만든 패턴을 못 잡는다"</b> 까지다. 실 트래픽에서 이런 패턴이 얼마나 되는지는 모른다.
 *
 * <p><b>고액 임계를 넘기는 패턴은 안 만들었다.</b> 만들면 규칙이 잡고, 그러면 모델이 규칙을
 * 다시 배우는 것을 재게 된다. 상황 3.1 에서 앱 로그에 원인을 이름으로 찍어 두고 모델이
 * 맞혔다고 좋아했던 것과 같은 함정이다.
 *
 * <p><b>seed 를 고정한다.</b> 회차마다 코퍼스가 바뀌면 모델이 좋아진 것인지 표본이 쉬워진
 * 것인지 못 가른다.
 */
final class FraudCorpus {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 고액 규칙의 임계. 부정 패턴은 이 아래에서만 논다. */
    static final long AMOUNT_THRESHOLD = 1_000_000L;

    /** 카드 하나의 결제 흐름과 그 흐름이 부정인지. */
    record Case(String cardKey, List<TxnRecord> window, TxnRecord current, boolean fraud, String axis) {}

    private final Random random;
    private int seq;

    FraudCorpus(long seed) {
        this.random = new Random(seed);
    }

    /**
     * 정상 {@code normals} 개와 부정 여섯 축 각 {@code perAxis} 개.
     *
     * <p>부정을 소수로 둔다. 반반으로 만들면 아무 모델이나 정확도가 높게 나온다.
     */
    List<Case> build(int normals, int perAxis) {
        List<Case> out = new ArrayList<>();
        for (int i = 0; i < normals; i++) {
            out.add(normal());
        }
        for (int i = 0; i < perAxis; i++) {
            out.add(structuring());
            out.add(cardTesting());
            out.add(escalation());
            out.add(deviceRotation());
            out.add(ipRotation());
            out.add(nightBurst());
        }
        // 규칙이 잡는 부정과 규칙이 잘못 잡는 정상. <b>이게 없으면 심사 큐가 빈다.</b>
        // 큐가 비면 큐 정렬을 잴 수가 없는데, 처음에 이걸 안 넣고 전체 표본에서 잰
        // P@K 로 큐 정렬을 정당화했다. 평가 대상이 다른 값이었다.
        for (int i = 0; i < perAxis; i++) {
            out.add(blatantHighAmount());
            out.add(velocityBurst());
        }
        for (int i = 0; i < normals / 5; i++) {
            out.add(legitimateBigTicket());
        }
        java.util.Collections.shuffle(out, random);
        return out;
    }

    // ── 규칙이 잡는 자리 ────────────────────────────────────────────────────

    /**
     * <b>대놓고 고액</b> — 임계를 넘긴다. {@code HIGH_AMOUNT} 가 잡는다.
     *
     * <p>이 축을 넣는 이유는 모델 성적을 올리려는 것이 아니라 <b>심사 큐를 채우려는</b> 것이다.
     * 큐가 있어야 그 안에서 순서를 비교할 수 있다.
     */
    private Case blatantHighAmount() {
        String card = "card-h" + (seq++);
        String device = "dev-" + card;
        String ip = "10.7." + random.nextInt(255) + ".7";
        // 새벽. 훔친 카드로 한도를 긁는 쪽이다.
        Instant base = day().plus(Duration.ofHours(random.nextInt(5)));

        // 그 카드에 소액 몇 건뿐이다. 갑자기 수백만원이 나간다.
        List<TxnRecord> w = new ArrayList<>();
        int prior = random.nextInt(3);
        for (int i = 0; i < prior; i++) {
            w.add(new TxnRecord(10_000L + random.nextInt(40_000),
                    base.plus(Duration.ofMinutes(10L * i)), device, ip));
        }
        // 고액 + 장기할부 = 30 + 20 = 50점. CHALLENGE 임계를 넘어 큐에 들어간다.
        var main = new TxnRecord(AMOUNT_THRESHOLD + 500_000L + random.nextInt(3_000_000),
                base.plus(Duration.ofMinutes(10L * prior)), device, ip, 12);
        w.add(main);
        return new Case(card, w, main, true, "blatant_high_amount");
    }

    /** <b>1분 안에 몰아치기</b> — {@code VELOCITY_EXCEEDED} 가 잡는다. */
    private Case velocityBurst() {
        String card = "card-v" + (seq++);
        String device = "dev-" + card;
        String ip = "10.8." + random.nextInt(255) + ".7";
        Instant base = day().plus(Duration.ofHours(random.nextInt(24)));

        List<TxnRecord> w = new ArrayList<>();
        int n = 7 + random.nextInt(4);
        for (int i = 0; i < n; i++) {
            // 5초 간격. 1분 창 안에 다 들어간다.
            w.add(new TxnRecord(80_000L + random.nextInt(200_000),
                    base.plusSeconds(5L * i), device, ip));
        }
        return new Case(card, w, w.getLast(), true, "velocity_burst");
    }

    /**
     * <b>정상인데 고액</b> — 규칙이 잘못 잡는다. 큐에 오탐이 섞여야 정렬을 잴 수 있다.
     *
     * <p>혼수·가전·여행처럼 백만원을 넘기는 정상 결제가 있다. 규칙은 금액만 보므로 이걸 건다.
     * 심사자가 실제로 겪는 큐가 이런 모양이고, <b>모델이 이것을 아래로 내려보내는지</b>가
     * 정렬을 켤 근거다.
     */
    private Case legitimateBigTicket() {
        String card = "card-g" + (seq++);
        String device = "dev-" + card;
        String ip = "10.9." + random.nextInt(255) + ".7";
        Instant base = day().plus(Duration.ofHours(11 + random.nextInt(9)));   // 낮 시간

        List<TxnRecord> w = new ArrayList<>();
        // 평소 씀씀이도 제법 큰 사람이다. amountToMedian 이 안 튀어야 정상답다.
        int prior = 2 + random.nextInt(4);
        for (int i = 0; i < prior; i++) {
            w.add(new TxnRecord(400_000L + random.nextInt(600_000),
                    base.plus(Duration.ofMinutes(30L * i)), device, ip));
        }
        // 혼수·가전을 장기할부로 사는 거래. 위 blatant 와 <b>규칙에는 똑같이 보인다.</b>
        // 같은 50점으로 같은 큐에 들어간다. 가르는 것은 흐름뿐이다.
        var main = new TxnRecord(AMOUNT_THRESHOLD + 100_000L + random.nextInt(2_000_000),
                base.plus(Duration.ofMinutes(30L * prior)), device, ip, 12);
        w.add(main);
        return new Case(card, w, main, false, "legit_big_ticket");
    }

    // ── 정상 ────────────────────────────────────────────────────────────────

    /**
     * 평범한 사용자.
     *
     * <p><b>처음에는 정상을 너무 얌전하게 만들었다.</b> 1~6 건에 낮 시간, 같은 기기로 두니
     * 모델이 축별 재현율 100% 를 냈다. 상위 기여 피처를 보고 알았다. {@code windowCount}
     * 하나가 12 를 밀고 있었고, <b>모델이 패턴이 아니라 "건수가 많으면 부정" 을 배운 것</b>이다.
     * 부정 패턴이 전부 5~13 건이었으니 건수만으로 갈렸다.
     *
     * <p>상황 3.1 에서 앱 로그에 원인을 이름으로 찍어 두고 모델이 맞혔다고 좋아했던 것과
     * 같은 종류다. <b>내가 답을 흘리고 있었다.</b> 그래서 정상도 부정과 같은 구간에서 놀게 했다.
     *
     * <ul>
     *   <li>건수 1~14 — 부정의 5~13 을 덮는다. 건수만으로는 못 가른다</li>
     *   <li>다섯에 하나는 야간 — {@code nightRatio} 하나로 안 갈리게</li>
     *   <li>넷에 하나는 기기·IP 를 여러 개 씀 — 여행·와이파이 전환</li>
     *   <li>여섯에 하나는 금액이 계단으로 오름 — 우연히 그럴 수 있다</li>
     *   <li>다섯에 하나는 임계 바로 밑 고액 — 큰 물건을 사는 정상 거래</li>
     * </ul>
     */
    private Case normal() {
        String card = "card-n" + (seq++);
        String ip = "10.0." + random.nextInt(255) + "." + random.nextInt(255);

        boolean nightOwl = random.nextInt(5) == 0;
        boolean roaming = random.nextInt(4) == 0;
        boolean climbing = random.nextInt(6) == 0;
        boolean bigTicket = random.nextInt(5) == 0;

        Instant base = nightOwl
                ? day().plus(Duration.ofHours(random.nextInt(6)))
                : day().plus(Duration.ofHours(9 + random.nextInt(12)));

        int n = 1 + random.nextInt(14);
        List<TxnRecord> w = new ArrayList<>();
        long climb = 20_000L;
        for (int i = 0; i < n; i++) {
            long amount;
            if (climbing) {
                amount = climb;
                climb = Math.min(AMOUNT_THRESHOLD - 60_000, (long) (climb * (1.5 + random.nextDouble())));
            } else if (bigTicket && random.nextInt(3) == 0) {
                amount = AMOUNT_THRESHOLD * (91 + random.nextInt(9)) / 100;
            } else {
                amount = 5_000L + (long) (random.nextDouble() * 400_000);
            }
            Instant at = base.plus(Duration.ofMinutes(random.nextInt(600)));
            String d = roaming ? "dev-" + card + "-" + random.nextInt(4) : "dev-" + card;
            String p = roaming ? "10.1." + random.nextInt(255) + "." + random.nextInt(255) : ip;
            w.add(new TxnRecord(amount, at, d, p));
        }
        return new Case(card, w, w.getLast(), false, "normal");
    }

    // ── 부정 여섯 축 ────────────────────────────────────────────────────────

    /**
     * <b>구조화</b> — 고액 임계 바로 밑에 붙여 여러 번 민다.
     * 겨냥한 빈틈: {@code HIGH_AMOUNT} 는 임계를 <b>넘어야</b> 걸린다.
     */
    private Case structuring() {
        String card = "card-s" + (seq++);
        String device = "dev-" + card;
        String ip = "10.2." + random.nextInt(255) + ".7";
        Instant base = day().plus(Duration.ofHours(random.nextInt(24)));

        List<TxnRecord> w = new ArrayList<>();
        int n = 6 + random.nextInt(6);
        for (int i = 0; i < n; i++) {
            // 임계의 92~99%. 넘기지 않는다.
            long amount = AMOUNT_THRESHOLD * (92 + random.nextInt(8)) / 100;
            // 5~20분 간격. 카드 속도 규칙의 1분 창을 넘긴다.
            w.add(new TxnRecord(amount, base.plus(Duration.ofMinutes(5L * i + random.nextInt(15))),
                    device, ip));
        }
        return new Case(card, w, w.getLast(), true, "structuring");
    }

    /**
     * <b>카드 테스팅</b> — 소액으로 살아 있는지 떠본 뒤 본 거래를 한다.
     * 겨냥한 빈틈: 소액은 어떤 금액 규칙에도 안 걸리고, 간격을 벌리면 속도에도 안 걸린다.
     */
    private Case cardTesting() {
        String card = "card-t" + (seq++);
        String device = "dev-" + card;
        String ip = "10.3." + random.nextInt(255) + ".7";
        Instant base = day().plus(Duration.ofHours(random.nextInt(24)));

        List<TxnRecord> w = new ArrayList<>();
        int probes = 5 + random.nextInt(6);
        for (int i = 0; i < probes; i++) {
            long amount = 100L + random.nextInt(800);
            w.add(new TxnRecord(amount, base.plus(Duration.ofMinutes(3L * i)), device, ip));
        }
        // 본 거래. 임계 밑이라 규칙에는 안 걸린다.
        var main = new TxnRecord(700_000L + random.nextInt(250_000),
                base.plus(Duration.ofMinutes(3L * probes + 10)), device, ip);
        w.add(main);
        return new Case(card, w, main, true, "card_testing");
    }

    /**
     * <b>한도 탐색</b> — 승인될 때마다 금액을 올린다.
     * 겨냥한 빈틈: 각 건은 임계 밑이고, 규칙에는 <b>직전 건과 비교</b>라는 개념이 없다.
     */
    private Case escalation() {
        String card = "card-e" + (seq++);
        String device = "dev-" + card;
        String ip = "10.4." + random.nextInt(255) + ".7";
        Instant base = day().plus(Duration.ofHours(random.nextInt(24)));

        List<TxnRecord> w = new ArrayList<>();
        long amount = 20_000L;
        int n = 7 + random.nextInt(4);
        for (int i = 0; i < n; i++) {
            w.add(new TxnRecord(amount, base.plus(Duration.ofMinutes(8L * i)), device, ip));
            amount = Math.min(AMOUNT_THRESHOLD - 50_000, (long) (amount * (1.6 + random.nextDouble())));
        }
        return new Case(card, w, w.getLast(), true, "escalation");
    }

    /**
     * <b>기기 회전</b> — 매번 기기를 바꾼다.
     * 겨냥한 빈틈: 기기 속도는 <b>기기 키마다</b> 센다. 키를 바꾸면 카운터가 늘 1 이다.
     */
    private Case deviceRotation() {
        String card = "card-d" + (seq++);
        String ip = "10.5." + random.nextInt(255) + ".7";
        Instant base = day().plus(Duration.ofHours(random.nextInt(24)));

        List<TxnRecord> w = new ArrayList<>();
        int n = 8 + random.nextInt(5);
        for (int i = 0; i < n; i++) {
            w.add(new TxnRecord(150_000L + random.nextInt(400_000),
                    base.plus(Duration.ofMinutes(4L * i)), "dev-" + card + "-" + i, ip));
        }
        return new Case(card, w, w.getLast(), true, "device_rotation");
    }

    /**
     * <b>IP 회전</b> — 같은 이유로 IP 를 바꾼다.
     * 겨냥한 빈틈: IP 속도도 <b>IP 키마다</b> 센다.
     */
    private Case ipRotation() {
        String card = "card-i" + (seq++);
        String device = "dev-" + card;
        Instant base = day().plus(Duration.ofHours(random.nextInt(24)));

        List<TxnRecord> w = new ArrayList<>();
        int n = 8 + random.nextInt(5);
        for (int i = 0; i < n; i++) {
            w.add(new TxnRecord(120_000L + random.nextInt(400_000),
                    base.plus(Duration.ofMinutes(4L * i)),
                    device, "203.0." + i + "." + random.nextInt(255)));
        }
        return new Case(card, w, w.getLast(), true, "ip_rotation");
    }

    /**
     * <b>심야 몰림</b> — 새벽에 몰아서 한다.
     * 겨냥한 빈틈: 규칙 여섯에 <b>시간 축이 아예 없다.</b>
     */
    private Case nightBurst() {
        String card = "card-b" + (seq++);
        String device = "dev-" + card;
        String ip = "10.6." + random.nextInt(255) + ".7";
        Instant base = day().plus(Duration.ofHours(2));   // 새벽 2 시대

        List<TxnRecord> w = new ArrayList<>();
        int n = 5 + random.nextInt(5);
        for (int i = 0; i < n; i++) {
            w.add(new TxnRecord(200_000L + random.nextInt(500_000),
                    base.plus(Duration.ofMinutes(6L * i)), device, ip));
        }
        return new Case(card, w, w.getLast(), true, "night_burst");
    }

    /** 자정(KST) 기준 아무 날. 시각만 쓰므로 날짜는 고정해도 된다. */
    private Instant day() {
        return ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, KST).toInstant();
    }
}
