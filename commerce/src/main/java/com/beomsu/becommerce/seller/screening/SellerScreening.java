package com.beomsu.becommerce.seller.screening;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 스크리닝 판정 기록. <b>판정마다 한 행이고 지우지 않는다.</b>
 *
 * <p><b>왜 행으로 남기나</b>: 제재 명단은 갱신된다. 어제 통과한 판매자가 오늘 걸릴 수 있고,
 * 그때 <b>"언제 어느 판과 대조해 통과였는지"</b>를 못 대면 규제 대응이 안 된다.
 * 그래서 대조한 명단의 판({@code listVersion})까지 함께 남긴다.
 *
 * <p>그리고 오탐률을 재려면 <b>사람이 어떻게 판정했는지</b>가 쌓여야 한다.
 * {@code humanVerdict} 가 그 칸이고, 그게 없으면 임계를 고칠 근거가 영영 안 생긴다.
 */
@Entity
@Getter
@Table(name = "seller_screenings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerScreening {

    /** 무엇을 대조했나. 법인명과 대표자명은 걸리는 명단이 다르다. */
    public enum NameKind { LEGAL, REPRESENTATIVE }

    /** 사람의 판정. 오탐률의 분자·분모가 여기서 나온다. */
    public enum HumanVerdict { FALSE_POSITIVE, TRUE_POSITIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 200)
    private String screenedName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NameKind nameKind;

    @Column(length = 200)
    private String matchedEntry;

    @Column(nullable = false)
    private int matchScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScreeningVerdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private HumanVerdict humanVerdict;

    @Column(length = 100)
    private String reviewedBy;

    @Column(nullable = false, length = 40)
    private String listVersion;

    @Column(nullable = false)
    private Instant screenedAt;

    @Column
    private Instant reviewedAt;

    private SellerScreening(Long sellerId, String screenedName, NameKind nameKind,
                            String matchedEntry, int matchScore, ScreeningVerdict verdict,
                            String listVersion) {
        this.sellerId = sellerId;
        this.screenedName = screenedName;
        this.nameKind = nameKind;
        this.matchedEntry = matchedEntry;
        this.matchScore = matchScore;
        this.verdict = verdict;
        this.listVersion = listVersion;
        this.screenedAt = Instant.now();
    }

    public static SellerScreening of(Long sellerId, String name, NameKind kind,
                                     SellerScreeningService.Result r, String listVersion) {
        return new SellerScreening(sellerId, name, kind, r.matchedEntry(), r.score(), r.verdict(), listVersion);
    }

    /** 사람이 확인했다. <b>한 번 적은 판정은 안 덮는다</b> — 덮으면 오탐률이 조용히 바뀐다. */
    public void review(HumanVerdict decision, String by) {
        if (this.humanVerdict != null) {
            throw new IllegalStateException("이미 사람이 판정한 건이다 id=" + id);
        }
        this.humanVerdict = decision;
        this.reviewedBy = by;
        this.reviewedAt = Instant.now();
    }
}
