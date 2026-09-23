package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.MetricsTestConfig;
import com.beomsu.becommerce.ratelimit.RateLimiter;
import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.assist.residual.ResidualCauseService;
import com.beomsu.becommerce.assist.residual.ResidualSuggestion;
import com.beomsu.becommerce.member.MemberRepository;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면이 실제로 후보를 받는 경로를 고정한다.
 *
 * <p>이 엔드포인트가 assist 쪽에 있는 이유가 모듈 경계다. 대사 어드민이 assist 를
 * 부르면 순환이라, 창구를 여기 두고 화면이 두 번 부른다. 그 구조가 유지되는지는
 * {@code ModularityTests}가 본다.
 */
@WebMvcTest(ResidualCauseAdminController.class)
@Import({SecurityConfig.class, MetricsTestConfig.class})
// 블라인드 표집을 끈다. 기본 20%면 다섯 번에 한 번꼴로 제안이 감춰져 suggested=false 가 되고,
// 그때마다 이 테스트가 무작위로 깨진다. 표집 자체는 별도 테스트에서 확률로 검증한다.
@TestPropertySource(properties = "app.assist.residual.blind-percent=0")
class ResidualCauseAdminControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ResidualCauseService service;

    @MockitoBean
    com.beomsu.becommerce.assist.residual.ResidualSuggestionLog suggestionLog;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoBean
    MemberRepository memberRepository;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("인증 없이 부르면 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("후보가 있으면 원인과 근거를 함께 준다")
    void returnsSuggestion() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.of(
                new ResidualSuggestion(ResolveCause.INTERNAL_RECORD_LOST,
                        "내부에 이 주문의 기록이 없습니다.", 85, Set.of())));

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggested").value(true))
                .andExpect(jsonPath("$.cause").value("INTERNAL_RECORD_LOST"))
                .andExpect(jsonPath("$.confidence").value(85))
                // 어디서 온 값인지 항상 실어 준다. 규칙 제안과 화면에서 같아 보이면
                // 사람이 둘을 같은 무게로 읽는다.
                .andExpect(jsonPath("$.source").value("model"));
    }

    @Test
    @DisplayName("기권했으면 404가 아니라 200에 suggested=false")
    void abstentionIsNotAnError() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggested").value(false))
                .andExpect(jsonPath("$.cause").doesNotExist());
    }

    @Test
    @DisplayName("후보를 냈든 감췄든 기록은 항상 남는다 — 나중에 확정과 맞춰 본다")
    void alwaysRecordsForLaterComparison() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.of(
                new ResidualSuggestion(ResolveCause.INTERNAL_RECORD_LOST, "내부 기록이 없습니다.", 85, Set.of())));

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=7").with(admin()))
                .andExpect(status().isOk());

        // 화면에 줬는지와 무관하게 기록은 남는다. 감춘 건이 비교군이 되기 때문이다.
        verify(suggestionLog).record(eq(7L), eq(ResolveCause.INTERNAL_RECORD_LOST), anyBoolean());
    }

    @Test
    @DisplayName("규칙이 이미 후보를 냈다고 알려주면 가드 1이 서게 넘긴다")
    void passesRulesDecidedFlag() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause"
                        + "?reconResultId=1&rulesDecided=true").with(admin()))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CauseSuggestion>> rules = ArgumentCaptor.forClass(List.class);
        verify(service).suggest(anyString(), anyLong(), rules.capture());
        assertThat(rules.getValue()).isNotEmpty();     // 비어 있지 않으면 가드 1이 선다
    }

    @Test
    @DisplayName("기본값은 규칙이 못 냈다고 본다")
    void defaultsToRulesUndecided() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=1").with(admin()))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CauseSuggestion>> rules = ArgumentCaptor.forClass(List.class);
        verify(service).suggest(anyString(), anyLong(), rules.capture());
        assertThat(rules.getValue()).isEmpty();
    }
}
