package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 화면에 후보를 내준 순간을 기록해 두고, 나중에 사람의 확정과 맞춰 본다.
 *
 * <p><b>왜 필요한가.</b> "모델이 맞았나"와 "사람이 그걸 썼나"는 다른 질문이다. 앞은 분류
 * 정확도이고 뒤는 <b>채택률</b>인데, 업무가 줄었는지를 말하려면 뒤가 필요하다.
 * 그런데 채택률만 재면 이 프로젝트가 이미 지적한 실수를 반복한다 — 사람이 제안을 <b>보고</b>
 * 확정하므로 앵커링 편향이 섞인다. 그래서 셋을 같이 남긴다.
 *
 * <ul>
 *   <li><b>제안을 봤나</b>(blind 여부). 일부는 일부러 감춰서 비교군을 만든다</li>
 *   <li><b>제안한 원인이 사람이 고른 원인과 같나</b></li>
 *   <li><b>제안을 받은 뒤 확정까지 걸린 시간</b>. 제안이 일을 줄였는지 늘렸는지는 여기서 보인다</li>
 * </ul>
 *
 * <p><b>저장소를 두지 않는다.</b> 표본이 쌓이기 전에 스키마를 정하면 무엇을 재야 하는지
 * 모르는 채로 모양부터 굳는다. 상담 초안 섀도 기록도 같은 이유로 로그와 지표만 남긴다.
 * 재기동하면 진행 중이던 기록은 사라지는데, 그 건은 집계에서 빠질 뿐 업무에는 영향이 없다.
 *
 * <p>오래된 기록은 조회할 때 함께 지운다. 확정 없이 화면만 열고 닫는 경우가 많아
 * 그냥 두면 계속 쌓인다.
 */
@Component
public class ResidualSuggestionLog {

    /** 이 시간이 지나면 그 확정은 제안과 무관하다고 본다. */
    private static final Duration TTL = Duration.ofHours(6);

    private final Map<Long, Entry> byReconResult = new ConcurrentHashMap<>();

    /**
     * @param cause    모델이 고른 원인. 기권했으면 null
     * @param shown    화면에 내줬는지. false면 blind 표본이다
     * @param at       내준 시각
     */
    public record Entry(ResolveCause cause, boolean shown, Instant at) {
    }

    public void record(long reconResultId, ResolveCause cause, boolean shown) {
        sweep();
        byReconResult.put(reconResultId, new Entry(cause, shown, Instant.now()));
    }

    public Optional<Entry> take(long reconResultId) {
        sweep();
        return Optional.ofNullable(byReconResult.remove(reconResultId));
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(TTL);
        byReconResult.entrySet().removeIf(e -> e.getValue().at().isBefore(cutoff));
    }

    public int size() {
        return byReconResult.size();
    }
}
