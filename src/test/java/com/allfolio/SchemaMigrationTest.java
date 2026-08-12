package com.allfolio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/ROADMAP.md Task 002 — V1__init.sql / V2__indexes.sql 검증.
 * Testcontainers PG18에서 실제 마이그레이션을 실행하고 스키마를 정합성을 확인한다.
 */
class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("TRUNCATE TABLE users, assets, holdings, transactions RESTART IDENTITY CASCADE");
    }

    @Test
    void flywayHistoryHasBothMigrationsApplied() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IN ('1', '2') ORDER BY version");

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat((Boolean) row.get("success")).isTrue());
    }

    @Test
    void numericColumnsUsePrecision28Scale8() {
        List<Map<String, Object>> targets = List.of(
                Map.of("table", "holdings", "column", "quantity"),
                Map.of("table", "holdings", "column", "avg_price"),
                Map.of("table", "transactions", "column", "price"),
                Map.of("table", "transactions", "column", "quantity"));

        for (Map<String, Object> target : targets) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    """
                    SELECT numeric_precision, numeric_scale
                    FROM information_schema.columns
                    WHERE table_name = ? AND column_name = ?
                    """,
                    target.get("table"), target.get("column"));

            assertThat(row.get("numeric_precision")).isEqualTo(28);
            assertThat(row.get("numeric_scale")).isEqualTo(8);
        }
    }

    @Test
    void allTimestampColumnsAreTimestampWithTimeZone() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT table_name, column_name, data_type
                FROM information_schema.columns
                WHERE table_name IN ('users', 'assets', 'holdings', 'transactions')
                  AND column_name LIKE '%\\_at' ESCAPE '\\'
                """);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row ->
                assertThat(row.get("data_type")).isEqualTo("timestamp with time zone"));
    }

    @Test
    void noFloatingPointColumnsExist() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name IN ('users', 'assets', 'holdings', 'transactions')
                  AND data_type IN ('double precision', 'real')
                """, Integer.class);

        assertThat(count).isZero();
    }

    @Test
    void generatedIdsAreUuidV7() {
        UUID id = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class, "uuidv7-check@example.com", "hash");

        char versionNibble = id.toString().charAt(14);
        assertThat(versionNibble).isEqualTo('7');
    }

    @Test
    void holdingsAssetIdUniqueViolationThrows() {
        UUID userId = insertUser();
        UUID assetId = insertAsset(userId);
        insertHolding(assetId, new BigDecimal("1"), new BigDecimal("100"));

        assertThatThrownBy(() -> insertHolding(assetId, new BigDecimal("2"), new BigDecimal("200")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assetTypeCheckConstraintRejectsInvalidValue() {
        UUID userId = insertUser();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO assets (user_id, ticker, name, asset_type) VALUES (?, ?, ?, ?)",
                userId, "005930", "삼성전자", "INVALID"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void txTypeCheckConstraintRejectsInvalidValue() {
        UUID userId = insertUser();
        UUID assetId = insertAsset(userId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO transactions (asset_id, tx_type, price, quantity, traded_at)
                VALUES (?, ?, ?, ?, NOW())
                """,
                assetId, "INVALID", new BigDecimal("100"), new BigDecimal("1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void holdingsQuantityCheckConstraintRejectsNegative() {
        UUID userId = insertUser();
        UUID assetId = insertAsset(userId);

        assertThatThrownBy(() -> insertHolding(assetId, new BigDecimal("-1"), new BigDecimal("100")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void holdingsAvgPriceCheckConstraintRejectsZero() {
        UUID userId = insertUser();
        UUID assetId = insertAsset(userId);

        assertThatThrownBy(() -> insertHolding(assetId, new BigDecimal("1"), BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserCascadesToAssetsHoldingsTransactions() {
        UUID userId = insertUser();
        UUID assetId = insertAsset(userId);
        insertHolding(assetId, new BigDecimal("1"), new BigDecimal("100"));
        jdbcTemplate.update(
                "INSERT INTO transactions (asset_id, tx_type, price, quantity, traded_at) VALUES (?, 'BUY', ?, ?, NOW())",
                assetId, new BigDecimal("100"), new BigDecimal("1"));

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(countRows("assets", "user_id", userId)).isZero();
        assertThat(countRows("holdings", "asset_id", assetId)).isZero();
        assertThat(countRows("transactions", "asset_id", assetId)).isZero();
    }

    @Test
    void expectedIndexesExist() {
        List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename IN ('assets', 'transactions')",
                String.class);

        assertThat(indexNames).contains("idx_assets_user_id", "idx_transactions_asset_traded");
    }

    private UUID insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class, "user-" + UUID.randomUUID() + "@example.com", "hash");
    }

    private UUID insertAsset(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO assets (user_id, ticker, name, asset_type) VALUES (?, ?, ?, 'STOCK') RETURNING id",
                UUID.class, userId, "005930", "삼성전자");
    }

    private void insertHolding(UUID assetId, BigDecimal quantity, BigDecimal avgPrice) {
        jdbcTemplate.update(
                "INSERT INTO holdings (asset_id, quantity, avg_price) VALUES (?, ?, ?)",
                assetId, quantity, avgPrice);
    }

    private int countRows(String table, String column, UUID value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }
}
