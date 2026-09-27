package com.beomsu.becommerce.reconciliation.integrity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 결제 한 건이 주문 · 원장 · 대사 · 재고를 건너며 맞는지 세는 점검(#389, ADR-069).
 *
 * <p>Airbnb 는 결제 시스템의 정합성을 사건이 난 뒤 찾지 않고 모든 거래를 불변식으로 계속 재서 수치로 본다. 여기는 세기만 한다.
 * 주기적으로 세 게이지로 내는 것은 {@link IntegrityCheckScheduler}(다른 배치와 같이 프로퍼티로 켠다), 바로 세는 것은 관리자 API 다.
 *
 * <p><b>유예</b>가 이 점검의 정답 없는 값이다. 짧으면 아직 진행 중인 결제(확정 뒤 분개 · 대사 기록이 이벤트로 따라오는 사이)를
 * 위반으로 세고, 길면 진짜 위반을 늦게 잡는다. 부하에서 뒤따르는 기록은 승인 뒤 0.14초 안에 모두 생겼고 기본값은 0초다.
 */
@Service
public class IntegrityCheckService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration grace;

    @Autowired
    public IntegrityCheckService(JdbcTemplate jdbc, @Value("${app.integrity.grace-seconds:0}") long graceSeconds) {
        this(jdbc, Duration.ofSeconds(graceSeconds), Clock.systemUTC());
    }

    IntegrityCheckService(JdbcTemplate jdbc, Duration grace, Clock clock) {
        this.jdbc = jdbc;
        this.grace = grace;
        this.clock = clock;
    }

    /** 불변식별 위반 건수. 유예보다 최근에 움직인 건은 세지 않는다. */
    public Map<Invariant, Long> count(Duration grace) {
        Map<Invariant, Long> out = new LinkedHashMap<>();
        for (Invariant inv : Invariant.values()) {
            String sql = "SELECT COUNT(*) FROM (" + inv.sql() + ") v";
            Long n = inv.takesCutoff()
                    ? jdbc.queryForObject(sql, Long.class, cutoff(inv, grace))
                    : jdbc.queryForObject(sql, Long.class);
            out.put(inv, n == null ? 0L : n);
        }
        return out;
    }

    /** 위반한 건의 식별자 몇 개. 운영자가 어디부터 볼지 정하는 용도다. */
    public List<String> samples(Invariant inv, Duration grace, int limit) {
        String sql = inv.sql() + " LIMIT " + Math.max(1, Math.min(limit, 100));
        return inv.takesCutoff()
                ? jdbc.queryForList(sql, String.class, cutoff(inv, grace))
                : jdbc.queryForList(sql, String.class);
    }

    public Duration defaultGrace() {
        return grace;
    }

    private LocalDateTime cutoff(Invariant inv, Duration grace) {
        Duration age = inv.fixedAge() != null ? inv.fixedAge() : grace;
        // 엔티티의 Instant 는 UTC 벽시계로 저장된다(hibernate.jdbc.time_zone=UTC). 드라이버가 바꾸지 않게 LocalDateTime 으로 넘긴다
        return LocalDateTime.ofInstant(Instant.now(clock).minus(age), ZoneOffset.UTC);
    }
}
