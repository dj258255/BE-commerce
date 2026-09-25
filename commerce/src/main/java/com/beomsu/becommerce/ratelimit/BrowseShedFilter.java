package com.beomsu.becommerce.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 조회를 먼저 돌려보낸다(#250). 진행 중인 조회가 문턱에 닿으면 새 조회를 바로 503 으로 돌려보낸다.
 *
 * <p><b>왜 조회인가</b>: 웹훅·결제와 조회는 같은 DB 커넥션 풀(20)을 쓴다. 조회가 몰리면 웹훅도 그 줄에 서서 토스 10초 규약을
 * 넘기고(재전송), 결제는 미확정이 된다. 조회는 늦으면 사용자가 다시 누르면 되고 서버에 남는 부채가 없다. 늦을 때의 대가가
 * 가장 싼 쪽을 먼저 버린다.
 *
 * <p><b>왜 진행 중 조회 수인가</b>: 막히는 자원은 커넥션이다. 전체 요청 수로 세면 PG 를 기다리는 결제(커넥션을 안 쥔다)까지
 * 섞여 문턱이 흐려진다.
 *
 * <p><b>문턱이 하는 일</b>: 커넥션 몫을 남기는 것이 아니라 커넥션 줄의 길이를 묶는 것이다(#329). 풀 20 에서 문턱 20(남는 몫 0)도
 * 웹훅을 지켰고, 풀을 넘는 28 부터 무너졌다. 기본값 12 는 8·12·16·20 중 조회를 가장 적게 돌려보낸 값이다.
 *
 * <p>웹훅·결제·주문은 이 필터가 돌려보내지 않는다. 결제는 PG 동시 호출 상한(ADR-022)이 따로 있다.
 */
public class BrowseShedFilter extends OncePerRequestFilter {

    /** 조회로 보는 경로(GET 만). 앞부분 일치다. */
    static final List<String> BROWSE_PREFIXES = List.of("/api/v1/products", "/api/v1/home", "/api/v1/categories");

    private static final String BODY =
            "{\"code\":\"BUSY\",\"message\":\"요청이 몰려 조회를 잠시 받을 수 없습니다. 잠시 후 다시 시도해 주세요.\"}";

    private final int maxInFlight;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Counter shed;

    public BrowseShedFilter(int maxInFlight, MeterRegistry registry) {
        this.maxInFlight = maxInFlight;
        this.shed = Counter.builder("web.browse.shed").description("문턱에 닿아 돌려보낸 조회").register(registry);
        Gauge.builder("web.browse.in.flight", inFlight, AtomicInteger::get).register(registry);
        Gauge.builder("web.browse.max.in.flight", this, f -> f.maxInFlight).register(registry);
    }

    static boolean isBrowse(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return BROWSE_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isBrowse(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet();
            shed.increment();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(BODY);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    int inFlight() {
        return inFlight.get();
    }
}
