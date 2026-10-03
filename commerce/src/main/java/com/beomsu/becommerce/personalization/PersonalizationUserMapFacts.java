package com.beomsu.becommerce.personalization;

import com.beomsu.becommerce.personalization.internal.PersonalizationUserMapRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이 커머스 회원이 이어져 있는 <b>H&amp;M 고객 키</b>를 다른 모듈에 내준다(S2, #455).
 *
 * <p><b>왜 이 클래스가 있나</b>: 매핑 표를 읽는 코드({@code PersonalizationUserMapRepository})는
 * {@code personalization.internal} 이라 밖에서 import할 수 없다. 그래서 이 모듈이 <b>무엇을 남에게
 * 보여줄지 스스로 정해</b> 좁게 내준다 — {@code RecentActivityFacts} 와 같은 이유, 같은 방식이다(ADR-018).
 *
 * <p><b>왜 personalization 모듈인가</b>: 표 이름({@code personalization_user_map})과 적재 주체(개인화
 * 파이프라인), 문서({@code personalization/docs/04-storage.md} §4)가 모두 이 도메인을 가리킨다. 추천은
 * 이미 {@code RecentActivityFacts} 로 이 모듈에 기대므로 새 경계를 만들지 않는다.
 *
 * <p><b>매핑 없음은 빈 값이다</b>(표의 규칙: "행이 있으면 매핑, 없으면 폴백"). 예외가 아니므로
 * 호출자가 콜드스타트와 같은 경로로 물러선다.
 */
@Service
public class PersonalizationUserMapFacts {

    private final PersonalizationUserMapRepository userMap;

    PersonalizationUserMapFacts(PersonalizationUserMapRepository userMap) {
        this.userMap = userMap;
    }

    /** 이 회원의 H&amp;M 고객 키. 매핑이 없으면 빈 값이다. */
    @Transactional(readOnly = true)
    public Optional<String> hmCustomerId(long userId) {
        return userMap.findHmCustomerIdByUserId(userId);
    }
}
