package com.beomsu.becommerce.assist.narrative;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * <b>둘 중 어느 쪽이 나은가</b>를 사람이 고른 기록. 어느 쪽이 모델인지 <b>모른 채로</b> 고른다.
 *
 * <p><b>왜 이렇게 재나</b>: 처음에는 길이·기권·출처 없는 숫자로 쟀다. 앞의 둘은 품질이 아니고,
 * 특히 <b>길이를 개선으로 읽은 것은 방향이 틀렸다</b> — 평가자가 긴 답을 선호하는 편향은 이미
 * 알려져 있어서, 짧아진 것을 좋아졌다고 읽을 근거가 없다.
 *
 * <p>둘을 나란히 놓고 고르게 하는 <b>쌍 비교</b>가 절대 점수보다 사람 판단과 더 잘 맞는다는 것이
 * 알려져 있고, 프롬프트·모델을 A/B 로 견줄 때 쓰는 방식이 이것이다.
 *
 * <p><b>편향을 설계로 막는다</b>
 * <ul>
 *   <li><b>순서</b> — 제시 순서를 무작위로 하고 그 순서를 함께 남긴다. 나중에 순서 효과를 뺄 수 있다</li>
 *   <li><b>출처</b> — 고르기 전에는 어느 쪽이 모델인지 보여주지 않는다</li>
 *   <li><b>동점</b> — 억지로 고르게 하지 않는다. "차이 없음"도 답이다</li>
 * </ul>
 */
@Entity
@Table(name = "narrative_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NarrativePreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderNo;

    /** A 자리에 놓인 것이 무엇이었나. 사람에게는 안 보인다. */
    @Column(name = "source_a", nullable = false, length = 100)
    private String sourceA;

    @Column(name = "source_b", nullable = false, length = 100)
    private String sourceB;

    @Column(name = "text_a", columnDefinition = "TEXT", nullable = false)
    private String textA;

    @Column(name = "text_b", columnDefinition = "TEXT", nullable = false)
    private String textB;

    /** {@code A} · {@code B} · {@code TIE}. 아직 안 골랐으면 null. */
    @Column(length = 8)
    private String choice;

    @Column(length = 100)
    private String reviewer;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant chosenAt;

    private NarrativePreference(String orderNo, String sourceA, String textA,
                                String sourceB, String textB) {
        this.orderNo = orderNo;
        this.sourceA = sourceA;
        this.textA = textA;
        this.sourceB = sourceB;
        this.textB = textB;
        this.createdAt = Instant.now();
    }

    static NarrativePreference of(String orderNo, String sourceA, String textA,
                                  String sourceB, String textB) {
        return new NarrativePreference(orderNo, sourceA, textA, sourceB, textB);
    }

    /** 고른다. 한 번 고르면 바꾸지 않는다 — 되돌리면 그건 다른 실험이다. */
    void choose(String choice, String reviewer) {
        if (this.choice != null) {
            throw new IllegalStateException("이미 선택된 비교입니다: " + id);
        }
        if (!"A".equals(choice) && !"B".equals(choice) && !"TIE".equals(choice)) {
            throw new IllegalArgumentException("A · B · TIE 중 하나여야 합니다: " + choice);
        }
        this.choice = choice;
        this.reviewer = reviewer;
        this.chosenAt = Instant.now();
    }

    /** 선택이 가리키는 실제 출처. 집계는 이걸로 한다. */
    public String chosenSource() {
        if (choice == null || "TIE".equals(choice)) {
            return null;
        }
        return "A".equals(choice) ? sourceA : sourceB;
    }
}
