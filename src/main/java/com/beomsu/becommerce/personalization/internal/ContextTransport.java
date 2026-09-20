package com.beomsu.becommerce.personalization.internal;

/**
 * 컨텍스트를 <b>누가 언제</b> 갱신하는가. 적용 로직은 셋이 공유하고 이것만 바꾼다.
 *
 * <p>이 축이 있는 이유는 "왜 Kafka인가"를 재기 위해서다. 셋 다 컨텍스트를 갱신하지만
 * 얻는 것과 내주는 것이 다르다.
 *
 * <ul>
 *   <li>{@link #KAFKA} — 활동 저장 → Outbox → <b>브로커</b> → 인앱 컨슈머. 프로세스 밖 소비자가
 *       가능하지만 브로커가 지연을 더하고 운영 대상이 하나 늘며, {@code kafka} 프로파일이 아니면
 *       <b>아무것도 받지 못한다</b>. 기본값이다(문서화된 설계가 Kafka이므로)</li>
 *   <li>{@link #IN_PROCESS} — 커밋 후 {@code @ApplicationModuleListener}. 근사선의 성질(비동기·커밋
 *       이후)은 그대로면서 브로커가 필요 없다. 대신 프로세스 밖 소비자는 불가능하다</li>
 *   <li>{@link #IN_REQUEST} — 같은 트랜잭션에서 갱신. 신선도는 정의상 최상이지만 <b>쓰기 경로가
 *       결합된다</b> — 컨텍스트 저장소가 실패하면 활동 기록까지 롤백된다</li>
 * </ul>
 */
public enum ContextTransport {
    KAFKA,
    IN_PROCESS,
    IN_REQUEST
}
