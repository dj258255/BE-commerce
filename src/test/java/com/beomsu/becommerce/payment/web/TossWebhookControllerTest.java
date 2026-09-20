package com.beomsu.becommerce.payment.web;

import com.beomsu.becommerce.MetricsTestConfig;
import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.member.MemberRepository;
import com.beomsu.becommerce.payment.webhook.TossWebhookNormalizer;
import com.beomsu.becommerce.payment.webhook.WebhookService;
import com.beomsu.becommerce.ratelimit.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토스 웹훅 수신구의 두 가지를 고정한다 — <b>누구를 들이느냐</b>와 <b>무엇을 돌려주느냐</b>.
 *
 * <p>토스는 서명을 주지 않으므로 요청만 봐서는 보낸 쪽을 확인할 수 없다. 그래서 발신 IP 허용
 * 목록을 열어 뒀는데, 이건 <b>기본 off</b> 다. 앱이 프록시 뒤에 있으면 보이는 IP가 프록시라서
 * 켜면 정상 웹훅까지 막히기 때문이다. 두 상태가 다 의도대로인지 여기서 본다.
 */
class TossWebhookControllerTest {

    private static final String TOSS_BODY = """
            {"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"tviva1","status":"DONE"}}""";

    @WebMvcTest(controllers = TossWebhookController.class)
    @Import({SecurityConfig.class, MetricsTestConfig.class, TossWebhookNormalizer.class})
    abstract static class Base {
        @Autowired MockMvc mvc;
        @MockitoBean WebhookService webhookService;
        @MockitoBean JwtDecoder jwtDecoder;
        @MockitoBean RateLimiter rateLimiter;
        @MockitoBean MemberRepository memberRepository;
    }

    @Nested
    @DisplayName("허용 목록이 비어 있으면(기본) 검사하지 않는다")
    @TestPropertySource(properties = "payment.webhook.toss-allowed-ips=")
    class AllowlistOff extends Base {

        @Test
        @DisplayName("어느 발신지에서 와도 200을 주고 해석으로 넘긴다")
        void acceptsAnySourceWhenAllowlistEmpty() throws Exception {
            mvc.perform(post("/api/v1/webhooks/toss")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; })
                            .content(TOSS_BODY))
                    .andExpect(status().isOk());

            verify(webhookService).handleUnsigned(anyString());
        }
    }

    @Nested
    @DisplayName("허용 목록을 켜면 목록 밖 발신지를 막는다")
    @TestPropertySource(properties = "payment.webhook.toss-allowed-ips=13.124.18.147, 3.36.173.151")
    class AllowlistOn extends Base {

        @Test
        @DisplayName("목록 안의 IP는 통과한다")
        void allowsListedIp() throws Exception {
            mvc.perform(post("/api/v1/webhooks/toss")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(r -> { r.setRemoteAddr("3.36.173.151"); return r; })
                            .content(TOSS_BODY))
                    .andExpect(status().isOk());

            verify(webhookService).handleUnsigned(anyString());
        }

        @Test
        @DisplayName("목록 밖 IP는 403이고, 해석까지 가지 않는다")
        void blocksUnlistedIp() throws Exception {
            mvc.perform(post("/api/v1/webhooks/toss")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(r -> { r.setRemoteAddr("203.0.113.9"); return r; })
                            .content(TOSS_BODY))
                    .andExpect(status().isForbidden());

            // 막힌 요청은 행도 만들지 않고 조회도 부르지 않는다 — 위조가 만드는 부하를 여기서 끊는다.
            verify(webhookService, never()).handleUnsigned(anyString());
        }
    }
}
