package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.DraftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 같은 사실에 대한 두 서술을 <b>출처를 가린 채</b> 내놓고, 사람이 고른 것을 모은다.
 *
 * <p>이 기능을 켤지 말지는 이 표가 쌓인 다음에 정한다. 그 전에는 기본값을 바꾸지 않는다 —
 * 잔여 후보에서 그 순서를 어겼다가 되돌렸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NarrativeComparisonService {

    private final DraftService draftService;
    private final List<TimelineNarrativePort> ports;
    private final NarrativePreferenceRepository repository;

    /**
     * 비교 한 건을 만든다. <b>제시 순서를 무작위로 섞는다</b> — 사람이 앞쪽을 고르는 경향이
     * 있으므로, 순서를 고정하면 그 경향이 결과에 그대로 실린다.
     *
     * @return 구현이 둘 미만이거나 어느 한쪽이 못 만들면 empty — 비교할 것이 없다
     */
    @Transactional
    public Optional<Comparison> open(String orderNo) {
        if (ports.size() < 2) {
            return Optional.empty();
        }
        FactPack facts = draftService.factsFor(orderNo, null);
        if (facts.facts().isEmpty()) {
            return Optional.empty();
        }

        TimelineNarrativePort first = ports.get(0);
        TimelineNarrativePort second = ports.get(1);
        Optional<String> t1 = first.narrate(facts);
        Optional<String> t2 = second.narrate(facts);
        if (t1.isEmpty() || t2.isEmpty()) {
            return Optional.empty();
        }

        boolean flip = ThreadLocalRandom.current().nextBoolean();
        TimelineNarrativePort a = flip ? second : first;
        TimelineNarrativePort b = flip ? first : second;
        String textA = flip ? t2.get() : t1.get();
        String textB = flip ? t1.get() : t2.get();

        NarrativePreference saved = repository.save(
                NarrativePreference.of(orderNo, a.name(), textA, b.name(), textB));
        // 출처를 내보내지 않는다. 이름이 보이면 블라인드가 아니다.
        return Optional.of(new Comparison(saved.getId(), orderNo, textA, textB));
    }

    /**
     * 미리 만들어 둔 것 중 <b>아직 안 고른</b> 비교 하나를 꺼낸다.
     *
     * <p><b>왜 미리 만드나</b>: {@link #open} 은 부를 때마다 모델을 호출한다. 건당 10초 안팎이라
     * 30건을 고르려면 클릭 사이마다 그만큼 기다린다. 사람이 앉아서 고르는 일에서 그 대기가
     * 표본이 안 모이는 실제 이유였다. 만들어 두는 일과 고르는 일을 갈랐다.
     */
    @Transactional(readOnly = true)
    public Optional<Comparison> nextPending() {
        return repository.findFirstByChoiceIsNullOrderByIdAsc()
                .map(p -> new Comparison(p.getId(), p.getOrderNo(), p.getTextA(), p.getTextB()));
    }

    /** 아직 안 고른 비교가 몇 건 남았나. */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return repository.countByChoiceIsNull();
    }

    /** 고른다. 고르고 나서야 어느 쪽이 무엇이었는지 알려준다. */
    @Transactional
    public Optional<Revealed> choose(long comparisonId, String choice, String reviewer) {
        return repository.findById(comparisonId).map(p -> {
            p.choose(choice, reviewer);
            return new Revealed(p.getId(), p.getSourceA(), p.getSourceB(), p.chosenSource());
        });
    }

    /**
     * 심판 이름이 이 접두사로 시작하면 사람이 아니다. 승격 기준에서 뺀다.
     *
     * <p>모델 심판도 이 표에 쓴다 — 나중에 사람이 고른 것과 <b>일치도를 재려면</b> 같은 표에
     * 있어야 한다. 그런데 섞어서 세면 "사람이 고른 25건"을 셀 수 없다. 그래서 이름으로 가른다.
     */
    static final String MACHINE_PREFIX = "judge:";

    /** 집계 — 무엇이 몇 번 선택됐나. 동점은 따로 센다. <b>사람이 고른 것만</b> 승격 기준에 든다. */
    @Transactional(readOnly = true)
    public Stats stats() {
        List<NarrativePreference> chosen = repository.findByChoiceIsNotNull();
        List<NarrativePreference> human = chosen.stream().filter(NarrativeComparisonService::byHuman).toList();
        long ties = human.stream().filter(p -> "TIE".equals(p.getChoice())).count();
        long template = human.stream().filter(p -> "template".equals(p.chosenSource())).count();
        long model = human.size() - ties - template;
        return new Stats(human.size(), template, model, ties, chosen.size() - human.size());
    }

    private static boolean byHuman(NarrativePreference p) {
        String r = p.getReviewer();
        return r == null || !r.startsWith(MACHINE_PREFIX);
    }

    /**
     * 고른 것을 CSV 로 내보낸다. <b>판정은 컨테이너보다 오래 살아야 한다.</b>
     *
     * <p>표본과 정답 키는 저장소에 있는데 <b>판정만 로컬 MySQL 에 있었다.</b>
     * {@code docker compose down} 한 번에 사라지고, 사라진 것을 알아차릴 방법도 없다.
     * 사람이 30건을 고르는 데 드는 시간을 그렇게 잃으면 안 된다.
     */
    @Transactional(readOnly = true)
    public String exportCsv() {
        StringBuilder sb = new StringBuilder("orderNo,sourceA,sourceB,choice,chosenSource,reviewer,chosenAt\n");
        for (NarrativePreference p : repository.findByChoiceIsNotNullOrderByIdAsc()) {
            sb.append(p.getOrderNo()).append(',')
              .append(p.getSourceA()).append(',')
              .append(p.getSourceB()).append(',')
              .append(p.getChoice()).append(',')
              .append(p.chosenSource() == null ? "" : p.chosenSource()).append(',')
              .append(p.getReviewer() == null ? "" : p.getReviewer()).append(',')
              .append(p.getChosenAt()).append('\n');
        }
        return sb.toString();
    }

    /** 사람에게 보이는 것 — <b>출처가 없다</b>. */
    public record Comparison(Long id, String orderNo, String textA, String textB) {
    }

    /** 고른 뒤에 공개되는 것. */
    public record Revealed(Long id, String sourceA, String sourceB, String chosenSource) {
    }

    /**
     * @param total    <b>사람이</b> 고른 건수. 승격 기준(최소 25건)은 이 수를 본다
     * @param template 모델 없는 쪽이 선택된 수
     * @param model    모델 쪽이 선택된 수
     * @param ties     차이 없음
     * @param byMachine 모델 심판이 고른 건수. 승격에는 안 세고, 사람 표본과 일치도를 잴 때 쓴다
     */
    public record Stats(long total, long template, long model, long ties, long byMachine) {
    }
}
