package com.beomsu.becommerce.fraud.internal;

import com.beomsu.becommerce.fraud.velocity.VelocityCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * FDS 룰 엔진 — 결제 요청의 위험 점수를 매겨 판정한다.
 *
 * <p>여러 룰(velocity, 금액 이상치, 블랙리스트)의 가중치를 합산해 점수를 내고, 구간별로
 * ALLOW/CHALLENGE/REVIEW/BLOCK을 결정한다. <b>임계값·가중치는 프로퍼티로 주입</b>돼 코드 배포 없이
 * 조정할 수 있다(무배포 룰 변경). 블랙리스트도 런타임에 추가/제거된다.
 *
 * <p>이 서비스는 <b>동기 인라인 판정</b>(결제 경로에서 빠르게)에 쓰도록 경량 룰만 담는다.
 * 무거운 분석(그래프·ML)은 비동기 사후 탐지로 분리하는 것이 정석이다 — 이 판단이 이 기능의 핵심.
 */
@Service
@RequiredArgsConstructor
public class FraudService {

    // --- 무배포 조정 가능한 룰 파라미터 (프로퍼티 주입) ---
    @Value("${fds.velocity.threshold:5}")
    private int velocityThreshold;          // 1분 내 시도 횟수 임계
    @Value("${fds.velocity.weight:40}")
    private int velocityWeight;
    @Value("${fds.amount.threshold:1000000}")
    private long amountThreshold;           // 고액 임계
    @Value("${fds.amount.weight:30}")
    private int amountWeight;
    @Value("${fds.blacklist.weight:100}")
    private int blacklistWeight;
    @Value("${fds.velocity.device.threshold:8}")
    private int deviceThreshold;            // 기기는 재시도가 정상이라 카드보다 느슨하게
    @Value("${fds.velocity.device.weight:25}")
    private int deviceWeight;
    @Value("${fds.velocity.ip.threshold:15}")
    private int ipThreshold;                // 공용망·NAT 뒤에서 남남이 공유하므로 가장 느슨하게
    @Value("${fds.velocity.ip.weight:20}")
    private int ipWeight;
    @Value("${fds.installment.months:6}")
    private int longInstallmentMonths;       // 이 개월 수 이상을 <장기>로 본다
    @Value("${fds.installment.amount:1000000}")
    private long installmentAmountThreshold; // 장기 할부가 이 금액 이상일 때만 신호로 본다
    @Value("${fds.installment.weight:20}")
    private int installmentWeight;
    // 점수 구간 임계 (BLOCK >= 100, REVIEW >= 60, CHALLENGE >= 40)
    @Value("${fds.decision.block:100}")
    private int blockThreshold;
    @Value("${fds.decision.review:60}")
    private int reviewThreshold;
    @Value("${fds.decision.challenge:40}")
    private int challengeThreshold;

    private final VelocityCounter velocityCounter;

    /**
     * 차단 목록. 진실 원천은 DB의 REJECTED 심사이고, 이건 인스턴스들이 <b>같은 답을 보게</b> 하는 층이다.
     *
     * <p>예전에는 인스턴스마다 인메모리 {@code Set}이었다. 기동 시 재적재하니 재시작은 견뎠지만,
     * <b>런타임에 A에서 차단한 카드를 B·C가 모르는</b> 문제가 남아 있었다.
     */
    private final CardBlocklist cardBlocklist;

    /** 블랙리스트에 카드 추가(런타임, 무배포). 공유 목록이라 모든 인스턴스에 즉시 반영된다. */
    public void blacklistCard(String cardKey) {
        cardBlocklist.block(cardKey);
    }

    public FraudResult evaluate(FraudCheckRequest req) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        // 룰 1: 블랙리스트 (즉시 BLOCK 수준 가중치)
        if (cardBlocklist.contains(req.cardKey())) {
            score += blacklistWeight;
            reasons.add("BLACKLISTED_CARD");
        }

        // 룰 2: velocity — 카드 기준 1분 내 시도 횟수
        int attempts = velocityCounter.recordAndCount("card:" + req.cardKey());
        if (attempts > velocityThreshold) {
            score += velocityWeight;
            reasons.add("VELOCITY_EXCEEDED(" + attempts + ")");
        }

        // 룰 2-1·2-2: 같은 기기·같은 IP 로 카드를 <바꿔 가며> 두드리는 것은 카드 기준으로는 안 보인다.
        // 임계를 셋 다 다르게 두는 이유: 카드는 한 사람이 반복해서 쓸 일이 드물지만, 기기는 재시도가
        // 정상이고, IP 는 공용망·NAT 뒤에서 남남이 공유한다. 같은 숫자를 쓰면 IP 규칙이 정상 사용자를
        // 계속 건드린다. 가중치도 낮게 둔다 — 이건 차단이 아니라 <사람이 들여다볼 이유>를 만드는 층이다.
        score += velocityOf("device:", req.deviceId(), deviceThreshold, deviceWeight, "DEVICE", reasons);
        score += velocityOf("ip:", req.ip(), ipThreshold, ipWeight, "IP", reasons);

        // 룰 3: 금액 이상치
        if (req.amount() > amountThreshold) {
            score += amountWeight;
            reasons.add("HIGH_AMOUNT");
        }

        // 룰 4: 고액 + 장기 할부.
        //
        // 할부 자체는 정상이다. 안마의자 400만원을 12개월로 긁는 것은 흔하다. 그런데 도난 카드도
        // 같은 모양을 쓴다 — <한도 안에서 결제 금액을 키우는 가장 쉬운 수단>이 할부이기 때문이다.
        // 상품명이 올라와도 정상인지 사람이 바로 못 가른다. 그래서 <차단하지 않고> 낮은 점수만
        // 얹어 사람이 들여다볼 이유를 만든다. 이것만으로는 어떤 임계도 넘지 않는다.
        if (req.installmentMonths() >= longInstallmentMonths
                && req.amount() >= installmentAmountThreshold) {
            score += installmentWeight;
            reasons.add("LONG_INSTALLMENT_HIGH_AMOUNT(" + req.installmentMonths() + "개월)");
        }

        return new FraudResult(score, decide(score), reasons);
    }

    /**
     * 키가 있을 때만 센다. {@code null} 이면 <b>세지 않는다</b> — 없는 값을 한 덩어리로 묶으면
     * 서로 무관한 요청이 같은 카운터를 올려 엉뚱한 사람이 걸린다.
     */
    private int velocityOf(String prefix, String key, int threshold, int weight,
                           String reason, List<String> reasons) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        int attempts = velocityCounter.recordAndCount(prefix + key);
        if (attempts <= threshold) {
            return 0;
        }
        reasons.add(reason + "_VELOCITY_EXCEEDED(" + attempts + ")");
        return weight;
    }

    private FdsDecision decide(int score) {
        if (score >= blockThreshold) return FdsDecision.BLOCK;
        if (score >= reviewThreshold) return FdsDecision.REVIEW;
        if (score >= challengeThreshold) return FdsDecision.CHALLENGE;
        return FdsDecision.ALLOW;
    }
}
