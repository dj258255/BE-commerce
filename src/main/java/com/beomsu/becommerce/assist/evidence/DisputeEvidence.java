package com.beomsu.becommerce.assist.evidence;

import java.util.List;

/**
 * 카드사에 낼 증빙 한 벌.
 *
 * <p><b>구조를 먼저 낸다.</b> 자유 텍스트 한 덩어리로 내면 사람이 읽고 무엇이 빠졌는지 스스로
 * 판단해야 한다. 항목을 갈라 두면 <b>비어 있는 칸이 보인다</b> — 그게 증빙의 약점이고,
 * 약점을 알고 내는 것과 모르고 내는 것은 다르다.
 *
 * @param orderNo    대상 주문
 * @param sections   항목별 사실. 값이 없으면 그 항목을 <b>비운 채로 남긴다</b>
 * @param narrative  사람이 읽는 요약. 못 만들면 {@code null} 이고, 그때도 sections 는 나간다
 * @param gaps       채우지 못한 항목 이름. <b>이게 비어야 좋은 증빙이다</b>
 */
public record DisputeEvidence(String orderNo,
                              List<Section> sections,
                              String narrative,
                              List<String> gaps) {

    /**
     * @param name  항목 이름
     * @param lines 그 항목에 해당하는 사실. 타임라인에서 그대로 가져온 줄이다
     */
    public record Section(String name, List<String> lines) {
        public boolean isEmpty() {
            return lines == null || lines.isEmpty();
        }
    }
}
