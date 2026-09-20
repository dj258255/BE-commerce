package com.beomsu.becommerce.seller;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 정산으로 돈을 받는 쪽.
 *
 * <p><b>신원을 갖는 것이 범위 확장이 아닌 이유</b>: 돈을 보내려면 누구에게 보내는지
 * 필연적으로 안다. 사업자등록번호와 계좌 없이 정산할 방법이 없다. 구매자 신원과 성격이 다르다.
 *
 * <p><b>대표자 생년월일을 받는 이유</b>: 제재 명단 대조에서 동명이인을 가르는 데 쓴다.
 * 이름만 보면 <b>같은 사람의 다른 로마자 표기와 아예 다른 사람이 같은 점수</b>를 받는다.
 * 모르면 {@code null} 이고, 그때는 이름 점수를 그대로 쓴다 —
 * <b>모르는 것을 "안 맞았다"로 읽으면 제재 대상이 조용히 통과한다.</b>
 */
@Entity
@Getter
@Table(name = "sellers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사업자등록번호. 제재 대조의 기준이자 정산 대상의 식별자다. */
    @Column(nullable = false, unique = true, length = 20)
    private String businessNumber;

    /** 법인명·상호. 명단 대조는 이 이름으로 한다. */
    @Column(nullable = false, length = 200)
    private String legalName;

    /** 대표자명. 개인 제재 명단과 대조한다. */
    @Column(nullable = false, length = 100)
    private String representativeName;

    /** V36 이 CHAR(2) 로 만들어 뒀다. 길이가 늘 2 라 고정 길이가 맞고, 컬럼 정의를 여기 적어 둬야
     *  ddl-auto=validate 가 varchar 로 기대하지 않는다. */
    @Column(nullable = false, length = 2, columnDefinition = "char(2)")
    private String countryCode;

    /** 대표자 생년월일. 동명이인을 가르는 2차 식별자다. 모르면 null. */
    @Column
    private LocalDate representativeBirthDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SellerStatus status;

    /** 정산 계좌. 심사 통과 전에는 비어 있을 수 있다. */
    @Column(length = 64)
    private String payoutAccount;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Seller(String businessNumber, String legalName, String representativeName,
                   String countryCode, LocalDate representativeBirthDate) {
        this.businessNumber = businessNumber;
        this.legalName = legalName;
        this.representativeName = representativeName;
        this.countryCode = countryCode == null ? "KR" : countryCode;
        this.representativeBirthDate = representativeBirthDate;
        // 등록 직후는 <b>통과가 아니라 안 본 것</b>이다. 지급은 안 나간다.
        this.status = SellerStatus.PENDING_SCREENING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Seller register(String businessNumber, String legalName, String representativeName,
                                  String countryCode, LocalDate representativeBirthDate) {
        return new Seller(businessNumber, legalName, representativeName, countryCode, representativeBirthDate);
    }

    /** 스크리닝 결과를 반영한다. <b>BLOCKED 에서는 되돌아 나오지 않는다</b> — 사람이 따로 푼다. */
    public void applyScreening(SellerStatus next) {
        if (this.status == SellerStatus.BLOCKED && next != SellerStatus.BLOCKED) {
            throw new IllegalStateException("차단된 판매자는 스크리닝만으로 못 푼다 id=" + id);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public void assignPayoutAccount(String account) {
        this.payoutAccount = account;
        this.updatedAt = Instant.now();
    }
}
