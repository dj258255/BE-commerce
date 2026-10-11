package com.beomsu.becommerce.shorts;

import java.util.List;

/**
 * 숏폼 피드 한 쪽 조립(R26) — DB를 모르는 순수 로직.
 *
 * <p>{@code ShortsFeedService}가 커서 다음부터 id 내림차순으로 {@code pageSize + 1}개를 미리
 * 가져와 넘긴다(다음 쪽이 있는지 보려고 1개를 더 받는 흔한 커서 페이지네이션 기법 — 전체 개수를
 * 세는 COUNT 쿼리 없이 {@code hasNext}를 안다). 여기서는 그 {@code +1}개를 잘라내고 다음 커서
 * (이 쪽 마지막 항목의 id)를 계산한다.
 *
 * <p>READY가 아닌 항목은 한 번 더 걸러낸다 — 쿼리가 이미 상태로 거르므로 운영에서는 그냥 통과하는
 * 항등 연산이지만, <b>이 규칙이 깨지면 바로 걸리게</b> 순수 함수 하나로 고정해 둔다(실제 DB 없이
 * 단위 테스트로 검증할 수 있는 지점이기도 하다).
 */
final class ShortsFeedPage {

    private ShortsFeedPage() {
    }

    /** 한 쪽 조립 결과 — {@code nextCursor}가 null이면 마지막 쪽이다. */
    record Result(List<ShortVideo> items, Long nextCursor, boolean hasNext) {
    }

    static Result assemble(List<ShortVideo> fetchedPageSizePlusOne, int pageSize) {
        List<ShortVideo> ready = fetchedPageSizePlusOne.stream()
                .filter(v -> v.getStatus() == ShortVideoStatus.READY)
                .toList();
        boolean hasNext = ready.size() > pageSize;
        List<ShortVideo> items = hasNext ? ready.subList(0, pageSize) : ready;
        Long nextCursor = hasNext ? items.get(items.size() - 1).getId() : null;
        return new Result(items, nextCursor, hasNext);
    }
}
