/**
 * 감사 로그(audit) 모듈.
 *
 * <p>강제취소·언마스킹 같은 민감/위험 행위를 append-only로 기록한다. 전자금융거래법 제22조상
 * 거래기록 5년 보존, 전자금융감독규정상 원장 접근 기록 보존의 기반이 된다. 누가(actor)·무엇을(action)·
 * 어떤 대상에(target)·언제(createdAt) 했는지를 남긴다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.audit;
