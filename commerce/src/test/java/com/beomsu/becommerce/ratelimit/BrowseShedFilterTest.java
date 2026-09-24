package com.beomsu.becommerce.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 조회 셰딩(#250)의 계약: 진행 중 조회가 문턱에 닿으면 조회만 503 이고, 웹훅·결제는 그대로 지나간다.
 * 끝난 조회는 자리를 돌려준다(예외로 끝나도).
 */
class BrowseShedFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BrowseShedFilter filter = new BrowseShedFilter(2, registry);

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        r.setRequestURI(path);
        return r;
    }

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setRequestURI(path);
        return r;
    }

    @Test
    @DisplayName("진행 중 조회가 문턱이면 새 조회는 503, 웹훅은 통과, 끝나면 자리를 돌려준다")
    void shedsBrowseOnlyAtThreshold() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain blocking = (req, res) -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread a = new Thread(() -> run(get("/api/v1/products"), blocking));
        Thread b = new Thread(() -> run(get("/api/v1/home"), blocking));
        a.start();
        b.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse browse = new MockHttpServletResponse();
        filter.doFilter(get("/api/v1/products/facets"), browse, (req, res) -> { });
        assertThat(browse.getStatus()).isEqualTo(503);
        assertThat(browse.getHeader("Retry-After")).isEqualTo("1");

        MockHttpServletResponse hook = new MockHttpServletResponse();
        boolean[] passed = {false};
        filter.doFilter(post("/api/v1/webhooks/toss"), hook, (req, res) -> passed[0] = true);
        assertThat(passed[0]).isTrue();
        assertThat(hook.getStatus()).isEqualTo(200);

        release.countDown();
        a.join(5_000);
        b.join(5_000);
        assertThat(filter.inFlight()).isZero();
        assertThat(registry.counter("web.browse.shed").count()).isEqualTo(1.0);

        MockHttpServletResponse after = new MockHttpServletResponse();
        filter.doFilter(get("/api/v1/products"), after, (req, res) -> { });
        assertThat(after.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("조회가 예외로 끝나도 자리를 돌려준다")
    void releasesOnException() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("DB 커넥션 타임아웃");
        };
        for (int i = 0; i < 5; i++) {
            try {
                filter.doFilter(get("/api/v1/products"), new MockHttpServletResponse(), failing);
            } catch (Exception ignored) {
                // 예외는 그대로 올라간다
            }
        }
        assertThat(filter.inFlight()).isZero();
    }

    private void run(MockHttpServletRequest req, FilterChain chain) {
        try {
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
