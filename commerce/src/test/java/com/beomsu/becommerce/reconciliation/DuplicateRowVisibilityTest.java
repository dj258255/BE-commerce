package com.beomsu.becommerce.reconciliation;

import com.beomsu.becommerce.reconciliation.internal.ReconRunSummary;
import com.beomsu.becommerce.shared.Money;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중복 행이 <b>확정하는 사람에게 닿는지</b>.
 *
 * <p><b>왜 생겼나</b>: 업계에서 반복되는 대사 예외 여섯 유형(타이밍·금액·FX·은행 입금 누락·
 * PSP 기록 누락·<b>중복</b>)과 우리 원인 목록을 대조했더니 <b>중복이 빠져 있었다.</b>
 *
 * <p>엔진은 이미 거래 식별자로 중복을 걸러내고 경고 로그를 남긴다. 그런데 로그는
 * <b>예외 큐를 확정하는 사람이 보는 곳이 아니다.</b> 그 사람에게는 고를 어휘도 없어서
 * {@code OTHER} 로 갈 수밖에 없었고, 그러면 반복돼도 집계에 안 잡힌다.
 */
class DuplicateRowVisibilityTest {

    @Test
    @DisplayName("중복을 원인으로 고를 수 있다 — 없으면 OTHER 로 뭉개져 집계가 안 된다")
    void duplicateIsSelectableCause() {
        assertThat(ResolveCause.values())
                .as("업계 6유형 중 하나인데 목록에 없었다")
                .contains(ResolveCause.DUPLICATE_RECORD);
    }

    @Test
    @DisplayName("실행 요약이 중복 행 수를 싣는다 — 로그는 확정하는 사람이 보는 곳이 아니다")
    void summaryCarriesDuplicateCount() {
        var summary = new ReconRunSummary(10, 2, 1, 3, 5, 2, 1, 2, 5);
        assertThat(summary.duplicateRows())
                .as("0이 아니면 PG 파일 생성 자체에 문제가 있다는 신호다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("우리 원인 목록이 업계 6유형을 덮는지 — FX 만 비어 있고 그건 KRW 전용이라 맞다")
    void coversIndustryTaxonomy() {
        // 업계 6유형 → 우리 원인 매핑. 비는 칸이 있으면 <왜 비었는지> 설명할 수 있어야 한다.
        record Mapping(String industry, ResolveCause[] ours, String note) {}
        var table = Arrays.asList(
                new Mapping("timing gaps", new ResolveCause[]{
                        ResolveCause.PG_FILE_DELAY, ResolveCause.TIMEZONE_BOUNDARY,
                        ResolveCause.NET_CANCEL_TIMING}, "3종으로 세분"),
                new Mapping("amount mismatches", new ResolveCause[]{
                        ResolveCause.FEE_CALCULATION_DIFF,
                        ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED}, ""),
                new Mapping("missing PSP records", new ResolveCause[]{
                        ResolveCause.PG_FILE_DELAY}, "INTERNAL_ONLY 결과와 함께"),
                new Mapping("missing internal records", new ResolveCause[]{
                        ResolveCause.INTERNAL_RECORD_LOST}, ""),
                new Mapping("duplicates", new ResolveCause[]{
                        ResolveCause.DUPLICATE_RECORD}, "이번에 추가"),
                new Mapping("FX variance", new ResolveCause[]{},
                        "해당 없음 — Money 가 KRW 원 단위 long 이다"));

        for (Mapping m : table) {
            if (m.ours().length == 0) {
                assertThat(m.note()).as(m.industry() + " 는 비었는데 설명이 없다").isNotBlank();
            } else {
                assertThat(m.ours()).as(m.industry()).isNotEmpty();
            }
        }
        assertThat(table).hasSize(6);
    }
}
