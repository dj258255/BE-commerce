package com.beomsu.becommerce.seller.screening;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Optional;

/**
 * 판매자를 제재·PEP 명단과 대조한다. <b>차단은 사람이 확정한다.</b>
 *
 * <p><b>왜 자동 차단을 안 하나</b>: 오탐이 이 워크플로에서 가장 많이 나오는 단계다. 자동으로
 * 막으면 멀쩡한 판매자의 정산이 묶이고, 그 손해는 되돌리기 어렵다. 반대로 자동으로 통과시키면
 * 제재 대상에게 돈이 간다. 그래서 <b>지급을 막되 판정은 사람이 한다.</b>
 *
 * <p><b>매칭의 한계를 알고 쓴다</b>: 편집 거리로는 <b>같은 사람의 다른 로마자 표기와 다른
 * 사람이 같은 점수를 받는다</b>(실측으로 확인했다). 임계를 어디에 둬도 그 둘은 안 갈린다.
 * 그러니 이 점수는 <b>사람에게 보낼지 정하는 값</b>이지 맞고 틀림을 정하는 값이 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerScreeningService {

    private final NameMatcher matcher;
    private final Optional<SanctionsList> list;
    private final MeterRegistry registry;

    /** 이 값 이상이면 사람에게 보낸다. 낮추면 오탐이, 올리면 미탐이 는다. */
    @Value("${app.screening.potential-threshold:80}")
    private int potentialThreshold;

    /** @param name 대조할 이름. 법인명과 대표자명을 각각 따로 부른다 */
    public Result screen(String name) {
        return screen(name, SecondaryIdentifiers.unknown());
    }

    /**
     * 이름에 <b>2차 식별자</b>를 함께 대조한다.
     *
     * <p>이름만 보면 <b>같은 사람의 다른 로마자 표기와 아예 다른 사람이 같은 점수</b>를 받는다.
     * 실제 제재 스크리닝이 생년월일·국적으로 후보를 좁히는 층을 따로 두는 이유다.
     * 우리가 모르거나 명단이 안 주면 <b>이름 점수를 그대로 둔다</b> —
     * 모르는 것을 "안 맞았다"로 읽으면 제재 대상이 조용히 통과한다.
     *
     * @param name 대조할 이름
     * @param ours 우리가 아는 대상의 생년월일·국적. 모르면 {@link SecondaryIdentifiers#unknown()}
     */
    public Result screen(String name, SecondaryIdentifiers ours) {
        if (list.isEmpty() || list.get().entries().isEmpty()) {
            // 명단이 안 꽂혔다. <b>통과로 처리하지 않는다</b> — 안 본 것과 통과는 다르다.
            count("no_list");
            return new Result(ScreeningVerdict.POTENTIAL, null, 0, "명단이 없어 대조하지 못했다");
        }

        var best = list.get().entries().stream()
                .map(e -> new Scored(e, SecondaryIdentifiers.adjust(score(name, e.name()), ours, e.identifiers())))
                .max(Comparator.comparingInt(Scored::score))
                .orElseThrow();

        ScreeningVerdict verdict;
        if (best.score() >= 100) {
            verdict = ScreeningVerdict.CONFIRMED;
        } else if (best.score() >= potentialThreshold) {
            verdict = ScreeningVerdict.POTENTIAL;
        } else {
            verdict = ScreeningVerdict.CLEAR;
        }

        count(verdict.name().toLowerCase());
        if (verdict != ScreeningVerdict.CLEAR) {
            log.info("[screening] 잠재 일치 name={} matched={} score={} verdict={}",
                    name, best.entry().name(), best.score(), verdict);
        }
        return new Result(verdict,
                verdict == ScreeningVerdict.CLEAR ? null : best.entry().name(),
                best.score(),
                verdict == ScreeningVerdict.CLEAR ? "임계 아래" : "사람이 확인해야 한다");
    }

    /**
     * 이름 점수. <b>한글이면 로마자 후보로 펼쳐 정확 일치를 먼저 본다.</b>
     *
     * <p>제재 명단에 한글이 한 건도 없다(UN 통합 명단 실측 0건). 한국 관련 79건은 전부
     * {@code RI JE-SON}·{@code PAK CHANG HO} 같은 로마자다. 한글을 그대로 맞대면
     * <b>어느 것에도 안 걸린다</b> — 우리 판매자는 한글로 들어오는데 대조할 표기가 없다.
     *
     * <p>펼친 뒤 <b>점수를 올리는 게 아니라 정확 일치만</b> 본다. 점수로 재 봤더니
     * 김철수가 67 → 100점이 되는 동시에 <b>김철순도 92점</b>이 됐다 —
     * 후보를 늘린 만큼 남에게도 잘 걸린다. 딱 맞을 때만 100 을 주고, 아니면 원래 점수를 쓴다.
     */
    private int score(String ours, String listed) {
        int edit = matcher.score(ours, listed);
        if (!KoreanRomanizer.hasHangul(ours)) {
            return edit;
        }
        // ① 후보 중 하나가 <b>정확히</b> 맞으면 우연이 아니다.
        if (KoreanRomanizer.exactMatch(ours, listed) != null) {
            return 100;
        }
        // ② 소리가 같으면 후보에 없는 표기일 수 있다. 같은 박씨가 Park·Bak·Pak·Bahk 를 쓰는데
        //    <b>이건 오타가 아니라 다 맞는 표기</b>라, 후보를 아무리 늘려도 다 못 담는다.
        //    다만 <b>확정은 안 한다</b> — 소리를 접을수록 남남도 같은 키를 받고,
        //    한국인의 절반이 성씨 다섯 개를 공유한다. 사람에게 보낼 만큼만 올린다.
        for (String cand : KoreanRomanizer.romanize(ours)) {
            if (PhoneticKey.sounds(cand, listed)) {
                return Math.max(edit, potentialThreshold);
            }
        }
        return edit;
    }

    /** 대조에 쓴 명단의 판. 판정 기록에 남겨 "그때는 통과였다"를 댈 수 있게 한다. */
    public String listVersion() {
        return list.map(SanctionsList::version).orElse("no-list");
    }

    private record Scored(SanctionsList.Entry entry, int score) {}

    /**
     * @param verdict      기계 판정
     * @param matchedEntry 걸린 명단 항목. {@code CLEAR} 면 null
     * @param score        0~100
     * @param note         왜 이 판정인지
     */
    public record Result(ScreeningVerdict verdict, String matchedEntry, int score, String note) {}

    private void count(String outcome) {
        registry.counter("screening.outcome", "outcome", outcome).increment();
    }
}
