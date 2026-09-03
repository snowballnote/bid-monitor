package com.comhu.bidmonitor.companydb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 명시적으로 opt-in한 경우에만 회사 DB의 JDBC metadata를 읽는다.
 * URL·계정·비밀번호와 실제 업무 데이터는 조회하거나 출력하지 않는다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:company-metadata-live;DB_CLOSE_DELAY=-1",
        "company-db.enabled=true",
        "logging.level.com.zaxxer.hikari=OFF",
        "logging.level.org.postgresql=OFF"
})
@EnabledIfEnvironmentVariable(named = "COMPANY_DB_METADATA_LIVE_TEST", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "COMPANY_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "COMPANY_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "COMPANY_DB_PASSWORD", matches = ".+")
class CompanyDatabaseMetadataLiveTests {

    private static final List<String> RELEVANCE_KEYWORDS = List.of(
            "project", "business", "사업", "contract", "계약", "performance", "실적",
            "client", "customer", "organization", "기관", "고객", "employee", "member", "인력", "직원",
            "career", "experience", "경력", "certificate", "license", "자격", "교육", "education",
            "kosa", "document", "문서", "서류", "file", "attachment", "첨부", "path", "storage", "nas",
            "certification", "인증"
    );
    private static final Set<String> WRITE_PRIVILEGES = Set.of(
            "INSERT", "UPDATE", "DELETE", "TRUNCATE", "TRIGGER", "REFERENCES"
    );

    private final DataSource companyDataSource;
    private final JdbcTemplate companyJdbcTemplate;

    CompanyDatabaseMetadataLiveTests(
            @Qualifier("companyReadOnlyDataSource") DataSource companyDataSource,
            @Qualifier("companyJdbcTemplate") JdbcTemplate companyJdbcTemplate
    ) {
        this.companyDataSource = companyDataSource;
        this.companyJdbcTemplate = companyJdbcTemplate;
    }

    @Test
    void inventoriesRelevantPostgresqlSchemaWithoutReadingBusinessRows() {
        try (Connection connection = companyDataSource.getConnection()) {
            assertTrue(connection.isReadOnly(), "회사 DB connection은 read-only여야 합니다.");

            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("PostgreSQL", metadata.getDatabaseProductName());

            print("DB_PRODUCT", metadata.getDatabaseProductName());
            print("DB_VERSION", metadata.getDatabaseProductVersion());
            print("JDBC_DRIVER", metadata.getDriverName() + " " + metadata.getDriverVersion());
            print("CATALOG", safe(connection.getCatalog()));
            print("SCHEMA", safe(connection.getSchema()));
            print("CONNECTION_READ_ONLY", connection.isReadOnly());
            reportPrivileges();

            List<TableMetadata> relevantTables = findRelevantTables(metadata, connection.getCatalog());
            print("RELEVANT_TABLE_COUNT", relevantTables.size());
            relevantTables.forEach(this::printTable);
        } catch (SQLException | RuntimeException exception) {
            // 연결 예외에 endpoint가 포함될 수 있으므로 원인 예외를 테스트 출력으로 전달하지 않는다.
            fail("회사 DB metadata 조사에 실패했습니다. 환경변수·네트워크·조회 권한을 로컬에서 확인하세요.");
        }
    }

    private void reportPrivileges() {
        List<String> privileges = companyJdbcTemplate.queryForList(
                """
                        SELECT DISTINCT privilege_type
                        FROM information_schema.role_table_grants
                        WHERE grantee = current_user
                        ORDER BY privilege_type
                        """,
                String.class
        );
        boolean writePrivilegeDetected = privileges.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(WRITE_PRIVILEGES::contains);
        print("ACCOUNT_PRIVILEGES", String.join(",", privileges));
        print("WRITE_PRIVILEGE_DETECTED", writePrivilegeDetected);
    }

    private List<TableMetadata> findRelevantTables(DatabaseMetaData metadata, String catalog) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        try (ResultSet rows = metadata.getTables(
                catalog,
                null,
                "%",
                new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW", "FOREIGN TABLE"}
        )) {
            while (rows.next()) {
                String schema = rows.getString("TABLE_SCHEM");
                String table = rows.getString("TABLE_NAME");
                if (isSystemSchema(schema) || isPgVectorStructure(table)) {
                    continue;
                }
                List<ColumnMetadata> columns = readColumns(metadata, catalog, schema, table);
                if (isRelevant(table) || columns.stream().map(ColumnMetadata::name).anyMatch(this::isRelevant)) {
                    tables.add(new TableMetadata(
                            schema,
                            table,
                            rows.getString("TABLE_TYPE"),
                            columns,
                            readPrimaryKeys(metadata, catalog, schema, table),
                            readForeignKeys(metadata, catalog, schema, table),
                            readIndexes(metadata, catalog, schema, table)
                    ));
                }
            }
        }
        return tables.stream()
                .sorted((left, right) -> (safe(left.schema()) + "." + left.name())
                        .compareToIgnoreCase(safe(right.schema()) + "." + right.name()))
                .toList();
    }

    private List<ColumnMetadata> readColumns(
            DatabaseMetaData metadata,
            String catalog,
            String schema,
            String table
    ) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(catalog, schema, table, "%")) {
            while (rows.next()) {
                String typeName = rows.getString("TYPE_NAME");
                if ("vector".equalsIgnoreCase(typeName)) {
                    continue;
                }
                columns.add(new ColumnMetadata(
                        rows.getString("COLUMN_NAME"),
                        typeName,
                        "YES".equalsIgnoreCase(rows.getString("IS_NULLABLE"))
                ));
            }
        }
        return columns;
    }

    private Set<String> readPrimaryKeys(
            DatabaseMetaData metadata,
            String catalog,
            String schema,
            String table
    ) throws SQLException {
        Set<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (rows.next()) {
                primaryKeys.add(rows.getString("COLUMN_NAME"));
            }
        }
        return primaryKeys;
    }

    private List<ForeignKeyMetadata> readForeignKeys(
            DatabaseMetaData metadata,
            String catalog,
            String schema,
            String table
    ) throws SQLException {
        List<ForeignKeyMetadata> foreignKeys = new ArrayList<>();
        try (ResultSet rows = metadata.getImportedKeys(catalog, schema, table)) {
            while (rows.next()) {
                foreignKeys.add(new ForeignKeyMetadata(
                        rows.getString("FKCOLUMN_NAME"),
                        rows.getString("PKTABLE_SCHEM"),
                        rows.getString("PKTABLE_NAME"),
                        rows.getString("PKCOLUMN_NAME")
                ));
            }
        }
        return foreignKeys;
    }

    private List<IndexMetadata> readIndexes(
            DatabaseMetaData metadata,
            String catalog,
            String schema,
            String table
    ) throws SQLException {
        Map<String, MutableIndex> indexes = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(catalog, schema, table, false, true)) {
            while (rows.next()) {
                String indexName = rows.getString("INDEX_NAME");
                String columnName = rows.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                MutableIndex index = indexes.computeIfAbsent(
                        indexName,
                        ignored -> new MutableIndex(indexName, !rowsBoolean(rows, "NON_UNIQUE"))
                );
                index.columns().add(columnName);
            }
        }
        return indexes.values().stream()
                .map(index -> new IndexMetadata(index.name(), index.unique(), List.copyOf(index.columns())))
                .toList();
    }

    private boolean rowsBoolean(ResultSet rows, String column) {
        try {
            return rows.getBoolean(column);
        } catch (SQLException exception) {
            throw new IllegalStateException("index metadata를 읽을 수 없습니다.");
        }
    }

    private void printTable(TableMetadata table) {
        String qualifiedName = safe(table.schema()) + "." + table.name();
        print("TABLE", qualifiedName + "|TYPE=" + table.type());
        for (ColumnMetadata column : table.columns()) {
            print("COLUMN", qualifiedName + "." + column.name()
                    + "|TYPE=" + column.type()
                    + "|NULLABLE=" + column.nullable()
                    + "|PK=" + table.primaryKeys().contains(column.name()));
        }
        for (ForeignKeyMetadata foreignKey : table.foreignKeys()) {
            print("FOREIGN_KEY", qualifiedName + "." + foreignKey.column()
                    + "->" + safe(foreignKey.targetSchema()) + "."
                    + foreignKey.targetTable() + "." + foreignKey.targetColumn());
        }
        for (IndexMetadata index : table.indexes()) {
            print("INDEX", qualifiedName + "." + index.name()
                    + "|UNIQUE=" + index.unique()
                    + "|COLUMNS=" + String.join(",", index.columns()));
        }
    }

    private boolean isRelevant(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        return RELEVANCE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isSystemSchema(String schema) {
        String normalized = safe(schema).toLowerCase(Locale.ROOT);
        return normalized.equals("information_schema") || normalized.startsWith("pg_");
    }

    private boolean isPgVectorStructure(String table) {
        String normalized = safe(table).toLowerCase(Locale.ROOT);
        return normalized.contains("pgvector") || normalized.contains("embedding")
                || normalized.equals("vector") || normalized.startsWith("vector_");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void print(String key, Object value) {
        System.out.println("COMPANY_DB_" + key + "=" + value);
    }

    private record ColumnMetadata(String name, String type, boolean nullable) {
    }

    private record ForeignKeyMetadata(
            String column,
            String targetSchema,
            String targetTable,
            String targetColumn
    ) {
    }

    private record IndexMetadata(String name, boolean unique, List<String> columns) {
    }

    private record MutableIndex(String name, boolean unique, Collection<String> columns) {
        private MutableIndex(String name, boolean unique) {
            this(name, unique, new LinkedHashSet<>());
        }
    }

    private record TableMetadata(
            String schema,
            String name,
            String type,
            List<ColumnMetadata> columns,
            Set<String> primaryKeys,
            List<ForeignKeyMetadata> foreignKeys,
            List<IndexMetadata> indexes
    ) {
    }
}
