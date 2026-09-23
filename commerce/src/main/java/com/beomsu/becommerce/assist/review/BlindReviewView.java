package com.beomsu.becommerce.assist.review;

import java.util.List;

/**
 * 리뷰 화면에 줄 것. <b>단계에 따라 보여줄 것이 다르다.</b>
 *
 * <p>초안은 1단계를 마치기 전까지 <b>null 로 나간다.</b> 화면이 고를 수 있게 두면
 * 언젠가 누군가 "먼저 참고만" 하고, 그 순간 표본이 오염된다.
 *
 * <p>공개 뒤에는 초안 <b>둘</b>이 {@code draftA} · {@code draftB} 로 나간다.
 * <b>어느 쪽이 모델인지는 안 내보낸다.</b> 알면 "모델이니까 잘 썼겠지"가 편집량에 섞이고,
 * 그 순간 두 편집률의 차이가 방식 차이가 아니게 된다. 순서도 건마다 뒤집어
 * 먼저 본 쪽에 기준이 생기는 것을 막는다. 어느 쪽이 무엇인지는 집계가 서버에서 맞춘다.
 *
 * @param stage      BLIND(사실만 보고 쓰는 중) / REVEALED(초안 공개됨) / DONE(수정까지 끝)
 * @param facts      사람에게 보여줄 사실들. <b>1단계에서 이것만 본다</b>
 * @param blindReply 사람이 사실만 보고 쓴 답
 * @param draftA     먼저 보여줄 초안. 1단계 전에는 null
 * @param draftB     나중에 보여줄 초안. 1단계 전에는 null
 * @param editedA    {@code draftA} 를 고친 결과
 * @param editedB    {@code draftB} 를 고친 결과
 */
public record BlindReviewView(long id, long reconResultId, String orderNo, String stage,
                              List<String> facts, String causeHint,
                              String blindReply,
                              String draftA, String draftB,
                              String editedA, String editedB) {
}
