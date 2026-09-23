package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.DraftService;
import com.beomsu.becommerce.assist.draft.FactPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 사람이 <b>어느 쪽이 모델인지 모른 채</b> 고르게 하는 장치. 여기서 고정하는 것은
 * 편향을 막는 세 가지다 — 출처를 안 보여준다, 순서를 섞는다, 동점을 허용한다.
 */
@DisplayName("서술 쌍 비교 — 블라인드")
class NarrativeComparisonServiceTest {

    private static final FactPack FACTS = new FactPack("ORD-1",
            List.of("2026-08-30 · PAYMENT · 결제 승인 100,000원"),
            Set.of(100_000L), Set.of(LocalDate.of(2026, 8, 30)), null, true);

    private NarrativePreferenceRepository repository;
    private final List<NarrativePreference> saved = new ArrayList<>();

    private NarrativeComparisonService serviceWith(TimelineNarrativePort... ports) {
        DraftService draft = mock(DraftService.class);
        when(draft.factsFor(anyString(), any())).thenReturn(FACTS);
        return new NarrativeComparisonService(draft, List.of(ports), repository);
    }

    private TimelineNarrativePort port(String name, String text) {
        TimelineNarrativePort p = mock(TimelineNarrativePort.class);
        when(p.name()).thenReturn(name);
        when(p.narrate(any())).thenReturn(text == null ? Optional.empty() : Optional.of(text));
        return p;
    }

    @BeforeEach
    void setUp() {
        saved.clear();
        repository = mock(NarrativePreferenceRepository.class);
        when(repository.save(any())).thenAnswer(inv -> {
            NarrativePreference p = inv.getArgument(0);
            saved.add(p);
            return p;
        });
    }

    @Test
    @DisplayName("사람에게 나가는 것에는 출처가 없다 — 이름이 보이면 블라인드가 아니다")
    void doesNotLeakSourceBeforeChoosing() {
        var service = serviceWith(port("template", "템플릿 문장"), port("ollama:x", "모델 문장"));

        var out = service.open("ORD-1").orElseThrow();

        assertThat(out.textA()).isNotBlank();
        assertThat(out.textB()).isNotBlank();
        // Comparison 레코드에 출처 필드가 아예 없다. 문자열에도 안 섞인다.
        assertThat(out.toString()).doesNotContain("template").doesNotContain("ollama");
    }

    @Test
    @DisplayName("제시 순서를 섞는다 — 순서를 고정하면 앞쪽을 고르는 경향이 결과에 실린다")
    void randomizesPresentationOrder() {
        var service = serviceWith(port("template", "템플릿 문장"), port("ollama:x", "모델 문장"));

        Set<String> firstSlot = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            service.open("ORD-1");
        }
        saved.forEach(p -> firstSlot.add(p.getSourceA()));

        assertThat(firstSlot).containsExactlyInAnyOrder("template", "ollama:x");
    }

    @Test
    @DisplayName("어느 한쪽이 못 만들면 비교하지 않는다")
    void skipsWhenEitherSideAbstains() {
        var service = serviceWith(port("template", "템플릿 문장"), port("ollama:x", null));

        assertThat(service.open("ORD-1")).isEmpty();
    }

    @Test
    @DisplayName("구현이 하나뿐이면 비교할 것이 없다")
    void skipsWhenOnlyOneImplementation() {
        assertThat(serviceWith(port("template", "템플릿 문장")).open("ORD-1")).isEmpty();
    }

    @Test
    @DisplayName("고른 뒤에야 어느 쪽이 무엇이었는지 공개된다")
    void revealsOnlyAfterChoosing() {
        NarrativePreference p = NarrativePreference.of("ORD-1", "template", "가", "ollama:x", "나");
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        var service = serviceWith(port("template", "가"), port("ollama:x", "나"));

        var revealed = service.choose(1L, "B", "admin-1").orElseThrow();

        assertThat(revealed.chosenSource()).isEqualTo("ollama:x");
        assertThat(revealed.sourceA()).isEqualTo("template");
    }

    @Test
    @DisplayName("동점을 허용한다 — 억지로 고르게 하면 그 선택이 신호가 아니다")
    void allowsTie() {
        NarrativePreference p = NarrativePreference.of("ORD-1", "template", "가", "ollama:x", "나");
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        var service = serviceWith(port("template", "가"), port("ollama:x", "나"));

        var revealed = service.choose(1L, "TIE", "admin-1").orElseThrow();

        assertThat(revealed.chosenSource()).isNull();
    }

    @Test
    @DisplayName("한 번 고르면 바꾸지 않는다 — 되돌리면 그건 다른 실험이다")
    void choiceIsFinal() {
        NarrativePreference p = NarrativePreference.of("ORD-1", "template", "가", "ollama:x", "나");
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        var service = serviceWith(port("template", "가"), port("ollama:x", "나"));
        service.choose(1L, "A", "admin-1");

        assertThatThrownBy(() -> service.choose(1L, "B", "admin-1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
