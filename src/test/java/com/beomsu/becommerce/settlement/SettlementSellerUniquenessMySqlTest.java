package com.beomsu.becommerce.settlement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;

import java.sql.*;

import static org.assertj.core.api.Assertions.*;

/**
 * <b>같은 날짜·통화·판매자 정산이 두 줄 들어가지 않는지</b> 실 MySQL 에 묻는다.
 *
 * <p><b>여기가 한 번 뚫려 있었다.</b> V37 이 유니크 키에 {@code seller_id} 를 더했는데,
 * MySQL 은 유니크 인덱스에서 <b>NULL 을 서로 다른 값으로 본다.</b> 그리고 그때는 NULL 이
 * 곧 플랫폼 직판이라 <b>기본값</b>이었다. 그래서 같은 (날짜, 통화, NULL) 이 몇 줄이든 들어갔다.
 *
 * <p>그때 남아 있던 방어는 집계 전 존재 검사 하나뿐이었는데, 그건 이 프로젝트가
 * 인스턴스 둘을 띄워 <b>실제로 뚫은 바로 그 종류의 검사</b>다. 검사와 삽입 사이가 벌어지면
 * 둘 다 통과한다. V42 가 생성 컬럼으로 NULL 을 0 에 모아 제약을 완성했고,
 * <b>V49 가 플랫폼에 판매자 행을 줘서 NULL 자체를 없앴다.</b>
 *
 * <p>이 테스트는 재는 것이 아니라 <b>고정하는</b> 것이다. 누가 제약이나 nullable 을 다시
 * 손대면 여기서 걸린다.
 */
@Tag("integration")
class SettlementSellerUniquenessMySqlTest {

    /** V49 가 박아 넣는 플랫폼 자신의 판매자 행. */
    private static final long PLATFORM = 1L;
    private static final long MARKETPLACE_SELLER = 7L;

    private static final String INSERT = """
            INSERT INTO settlements
              (settlement_date, currency, seller_id, gross_amount, fee_amount, fee_vat_amount,
               net_amount, item_count, status, payout_date, created_at)
            VALUES (?, 'KRW', ?, 1000, 0, 0, 1000, 1, 'CREATED', ?, NOW())
            """;

    @Test
    @DisplayName("판매자 없는 정산은 아예 못 들어가고, 같은 날짜·판매자는 한 줄만 들어간다")
    void sellerIsRequiredAndUniquePerDate() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("uniq")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            try (Connection c = mysql.createConnection("")) {
                // V49 가 플랫폼 판매자 행을 넣어 뒀다. 이게 없으면 아래 직판 정산이 가리킬 곳이 없다.
                assertThat(sellerExists(c, PLATFORM))
                        .as("V49 가 플랫폼 자신을 판매자로 등록해야 한다")
                        .isTrue();

                // 판매자를 안 적는 정산은 없다. 예전에는 그것이 <플랫폼 직판>을 뜻했다.
                assertThatThrownBy(() -> insert(c, "2099-01-01", null))
                        .as("판매자를 모르는 정산은 만들 수 없다")
                        .isInstanceOf(SQLException.class);

                insert(c, "2099-01-01", PLATFORM);
                assertThatThrownBy(() -> insert(c, "2099-01-01", PLATFORM))
                        .as("플랫폼 직판도 DB 가 마지막에 막아야 한다. 앱 가드는 레이스에 뚫린다")
                        .isInstanceOf(SQLIntegrityConstraintViolationException.class);

                // 판매자가 다르면 같은 날짜에 각각 생긴다. 제약이 과하게 막지 않는 것도 함께 고정한다.
                insert(c, "2099-01-01", MARKETPLACE_SELLER);
                assertThat(count(c, "2099-01-01"))
                        .as("플랫폼 1건 + 입점 판매자 1건 = 2건")
                        .isEqualTo(2);

                assertThatThrownBy(() -> insert(c, "2099-01-01", MARKETPLACE_SELLER))
                        .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            }
        }
    }

    private static void insert(Connection c, String date, Long sellerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, date);
            if (sellerId == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, sellerId);
            ps.setString(3, "2099-01-03");
            ps.executeUpdate();
        }
    }

    private static boolean sellerExists(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sellers WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) == 1; }
        }
    }

    private static int count(Connection c, String date) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM settlements WHERE settlement_date = ?")) {
            ps.setString(1, date);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
