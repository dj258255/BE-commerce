package com.beomsu.becommerce.assist.narrative;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NarrativePreferenceRepository extends JpaRepository<NarrativePreference, Long> {

    List<NarrativePreference> findByChoiceIsNotNull();

    /** 내보내기용 — 고른 순서대로. 판정은 컨테이너보다 오래 살아야 한다. */
    List<NarrativePreference> findByChoiceIsNotNullOrderByIdAsc();

    /** 아직 안 고른 것 하나. 미리 만들어 둔 비교를 순서대로 내보낸다. */
    Optional<NarrativePreference> findFirstByChoiceIsNullOrderByIdAsc();

    /** 아직 안 고른 것이 몇 건 남았나. 목표까지 얼마나 남았는지 화면에 보여준다. */
    long countByChoiceIsNull();
}
