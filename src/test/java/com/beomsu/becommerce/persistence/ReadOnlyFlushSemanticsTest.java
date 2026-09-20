package com.beomsu.becommerce.persistence;

import probe.flush.Thing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>"readOnly 조회가 끼면 이후 변경이 flush되지 않는다"가 사실인지</b> 직접 재현한다.
 *
 * <p>이 프로젝트는 승인 응답은 나갔는데 DB에는 안 남던 버그를 겪고,
 * 상태 확정을 전부 {@code saveAndFlush} 로 통일했다. 그때 적어둔 원인 설명이
 * <b>"readOnly 조회가 세션 FlushMode를 MANUAL로 바꿔서"</b> 였다.
 *
 * <p>그런데 Spring 문서와 동작을 다시 보니 <b>중첩된 {@code readOnly=true} 가
 * REQUIRED로 바깥 read-write 트랜잭션에 참여하면 그 속성은 무시된다.</b>
 * 그렇다면 위 설명은 최소한 그 경로에서는 틀렸다.
 *
 * <p>추측으로 남기지 않으려고 세 경우를 실제로 찍는다.
 */
@DataJpaTest(showSql = false, properties = {
        // MySQL 마이그레이션은 H2에서 안 돈다. 이 테스트는 스키마가 아니라 flush 의미론만 본다.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = ReadOnlyFlushSemanticsTest.TestApp.class)
// 테스트가 트랜잭션을 잡으면 각 시나리오가 그 안에 참여해 <커밋>을 못 본다.
// 우리가 보려는 건 "커밋 때 flush가 되는가"라 테스트는 트랜잭션 밖에 둔다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReadOnlyFlushSemanticsTest {

    @Autowired
    Probe probe;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("① 평범한 read-write 트랜잭션: managed 엔티티 변경은 커밋 때 flush된다")
    void readWriteFlushesOnCommit() {
        long id = probe.insert("before");

        probe.mutateInReadWrite(id, "after");

        assertThat(nameInDb(id)).isEqualTo("after");
    }

    @Test
    @DisplayName("② 안에서 readOnly=true 조회를 껴도 마찬가지다 — 참여하면 readOnly는 무시된다")
    void innerReadOnlyDoesNotDisableFlush() {
        long id = probe.insert("before");

        FlushMode observed = probe.mutateAfterInnerReadOnly(id, "after");

        assertThat(observed)
                .as("바깥이 read-write면 참여한 안쪽 readOnly는 물리 트랜잭션에 적용되지 않는다")
                .isEqualTo(FlushMode.AUTO);
        assertThat(nameInDb(id))
                .as("따라서 '조회가 끼면 flush가 막힌다'는 설명은 이 경로에서 틀렸다")
                .isEqualTo("after");
    }

    @Test
    @DisplayName("③ 독립된 readOnly 트랜잭션은 FlushMode가 MANUAL이고, 거기서 바꾸면 안 남는다")
    void standaloneReadOnlyIsManualAndDropsChanges() {
        long id = probe.insert("before");

        FlushMode observed = probe.mutateInsideStandaloneReadOnly(id, "after");

        assertThat(observed).isEqualTo(FlushMode.MANUAL);
        assertThat(nameInDb(id))
                .as("독립 readOnly 트랜잭션 안의 변경은 flush되지 않는다")
                .isEqualTo("before");
    }

    @Test
    @DisplayName("④ 트랜잭션 밖에서 얻은 detached 엔티티는 바꿔도 안 남는다 — dirty check 대상이 아니다")
    void detachedEntityChangesAreLost() {
        long id = probe.insert("before");

        Thing detached = probe.loadInSeparateTransaction(id);
        detached.setName("after");   // 트랜잭션이 이미 끝났다 — 영속성 컨텍스트 밖

        assertThat(nameInDb(id)).isEqualTo("before");
    }

    /** 영속성 컨텍스트를 거치지 않고 <b>테이블을 직접</b> 읽는다. 응답이 아니라 저장을 본다. */
    private String nameInDb(long id) {
        return jdbc.queryForObject("select name from thing where id = ?", String.class, id);
    }


    @Service
    static class Probe {

        @PersistenceContext
        private EntityManager em;

        /** ②가 프록시를 타고 부르는 대상. 같은 빈에 두면 self-invocation 이 된다. */
        private final Lookup lookup;

        Probe(Lookup lookup) {
            this.lookup = lookup;
        }

        @Transactional
        long insert(String name) {
            Thing t = new Thing();
            t.name = name;
            em.persist(t);
            em.flush();
            return t.id;
        }

        /** ① 평범한 read-write. */
        @Transactional
        void mutateInReadWrite(long id, String name) {
            em.find(Thing.class, id).setName(name);
        }

        /**
         * ② 바깥은 read-write, 안에서 readOnly 조회를 한 번 부른다.
         *
         * <p>조회를 <b>다른 빈</b>({@link Lookup})에 두고 부른다. 같은 빈 안에서 부르면
         * self-invocation 이라 프록시를 안 타고, 그러면 {@code @Transactional(readOnly = true)}
         * 자체가 적용되지 않는다. 결과는 같아 보이지만 <b>이유가 달라</b> 이 테스트가
         * 증명하려는 것("참여한 readOnly 는 무시된다")을 증명하지 못한다.
         */
        @Transactional
        FlushMode mutateAfterInnerReadOnly(long id, String name) {
            lookup.readOnlyLookup(id);                 // 프록시를 타고 들어간다
            Thing t = em.find(Thing.class, id);
            t.setName(name);
            return em.unwrap(Session.class).getHibernateFlushMode();
        }


        /** ③ 독립된 readOnly 트랜잭션. 여기서는 진짜로 MANUAL이다. */
        @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
        FlushMode mutateInsideStandaloneReadOnly(long id, String name) {
            FlushMode mode = em.unwrap(Session.class).getHibernateFlushMode();
            em.find(Thing.class, id).setName(name);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return mode;
        }

        /** ④ 트랜잭션이 끝나면 반환된 엔티티는 detached다. */
        @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
        Thing loadInSeparateTransaction(long id) {
            return em.find(Thing.class, id);
        }
    }

    /**
     * readOnly 조회만 하는 <b>별도 빈</b>.
     *
     * <p>Probe 안에 두면 self-invocation 이라 프록시를 안 타고, 그러면 애너테이션이
     * 아예 적용되지 않은 상태를 관찰하게 된다. 갈라 둬야 <b>"참여한 readOnly 는 무시된다"</b>를
     * 실제로 재는 것이 된다.
     */
    @Service
    static class Lookup {

        @PersistenceContext
        private EntityManager em;

        /**
         * REQUIRED(기본)라 바깥 트랜잭션에 참여한다.
         *
         * <p>참여하는 순간 {@code readOnly} 는 <b>물리 트랜잭션에 새로 적용되지 않는다.</b>
         * Spring 이 새 물리 트랜잭션을 열 때만 그 힌트를 Hibernate 세션에 건다.
         */
        @Transactional(readOnly = true)
        void readOnlyLookup(long id) {
            em.find(Thing.class, id);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = probe.flush.Thing.class)
    static class TestApp {
        @org.springframework.context.annotation.Bean
        Lookup lookup() {
            return new Lookup();
        }

        @org.springframework.context.annotation.Bean
        Probe probe(Lookup lookup) {
            return new Probe(lookup);
        }
    }
}
