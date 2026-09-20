package com.beomsu.becommerce.seller;

import com.beomsu.becommerce.seller.screening.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 판매자를 등록하고 <b>제재 명단과 대조</b>한다.
 *
 * <h3>세 지점에서 본다</h3>
 * 업계 표준이 온보딩 · 지급 직전 · 주기적 재대조 셋인데, 여기서는 <b>온보딩과 재대조</b>를 맡는다.
 * 지급 직전 판단은 {@link SellerPayoutGate} 가 상태만 보고 답한다 —
 * 돈이 나가는 순간에 외부 명단을 받아 오면 그 지연이 지급 경로에 얹힌다.
 *
 * <h3>재대조가 필요한 이유</h3>
 * <b>명단은 갱신된다.</b> 어제 통과한 판매자가 오늘 지정될 수 있다. 온보딩 때 한 번 보고 마는 것은
 * 안 보는 것과 크게 다르지 않다. 그래서 매일 다시 훑는다 —
 * 차단된 쪽은 뺀다. 이미 막혀 있고 푸는 것은 사람이 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerOnboardingService {

    private final SellerRepository sellers;
    private final SellerScreeningRepository screenings;
    private final SellerScreeningService screening;

    @Transactional
    public Seller register(String businessNumber, String legalName, String representativeName,
                           String countryCode, LocalDate representativeBirthDate) {
        var seller = sellers.save(Seller.register(
                businessNumber, legalName, representativeName, countryCode, representativeBirthDate));
        screen(seller);
        return seller;
    }

    /**
     * 법인명과 대표자명을 <b>따로</b> 대조한다. 걸리는 명단이 다르다 —
     * 단체 제재와 개인 제재는 별개고, 대표자만 걸리는 경우가 실제로 있다.
     *
     * <p>둘 중 <b>더 나쁜 판정</b>을 상태로 삼는다. 하나라도 걸리면 지급을 막아야 한다.
     */
    @Transactional
    public void screen(Seller seller) {
        var legal = screening.screen(seller.getLegalName(),
                SecondaryIdentifiers.of(null, seller.getCountryCode()));
        var rep = screening.screen(seller.getRepresentativeName(), SecondaryIdentifiers.of(
                seller.getRepresentativeBirthDate() == null ? null : seller.getRepresentativeBirthDate().toString(),
                seller.getCountryCode()));

        String version = screening.listVersion();
        screenings.save(SellerScreening.of(seller.getId(), seller.getLegalName(),
                SellerScreening.NameKind.LEGAL, legal, version));
        screenings.save(SellerScreening.of(seller.getId(), seller.getRepresentativeName(),
                SellerScreening.NameKind.REPRESENTATIVE, rep, version));

        seller.applyScreening(worse(legal.verdict(), rep.verdict()));
        if (seller.getStatus() != SellerStatus.ACTIVE) {
            log.info("[screening] 지급 보류 sellerId={} status={} legal={} rep={}",
                    seller.getId(), seller.getStatus(), legal.verdict(), rep.verdict());
        }
    }

    /**
     * 명단 갱신을 따라 기존 판매자를 다시 훑는다.
     *
     * <p>상한을 건다 — 판매자가 늘어도 한 번에 도는 양이 일정해야 한다.
     * 이 저장소가 배치 조회 상한을 전수로 건 것과 같은 이유다.
     */
    @Transactional
    public void rescreenAll() {
        List<Seller> targets = sellers.findByStatusIn(
                List.of(SellerStatus.ACTIVE, SellerStatus.ON_HOLD, SellerStatus.PENDING_SCREENING),
                PageRequest.of(0, 500));
        targets.forEach(this::screen);
        log.info("[screening] 재대조 {}건 version={}", targets.size(), screening.listVersion());
    }

    /** CONFIRMED > POTENTIAL > CLEAR 순으로 나쁘다. */
    private static SellerStatus worse(ScreeningVerdict a, ScreeningVerdict b) {
        if (a == ScreeningVerdict.CONFIRMED || b == ScreeningVerdict.CONFIRMED) return SellerStatus.BLOCKED;
        if (a == ScreeningVerdict.POTENTIAL || b == ScreeningVerdict.POTENTIAL) return SellerStatus.ON_HOLD;
        return SellerStatus.ACTIVE;
    }
}
