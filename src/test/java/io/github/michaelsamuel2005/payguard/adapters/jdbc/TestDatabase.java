package io.github.michaelsamuel2005.payguard.adapters.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * A fresh PostgreSQL schema per test class, with the application's own migration files applied in version order.
 * Needs PAYGUARD_TEST_DB_URL (and _USER, _PASSWORD). Skipped locally without it; on CI a missing database fails.
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    public static DataSource freshSchema() {
        String url = System.getenv("PAYGUARD_TEST_DB_URL");
        if (url == null || url.isBlank()) {
            if (System.getenv("CI") != null) {
                throw new IllegalStateException("PAYGUARD_TEST_DB_URL must be set on CI");
            }
            Assumptions.assumeTrue(false, "PAYGUARD_TEST_DB_URL is not set: skipping database tests");
        }
        String user = env("PAYGUARD_TEST_DB_USER", "payguard");
        String password = env("PAYGUARD_TEST_DB_PASSWORD", "payguard");
        String schema = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            s.execute("create schema " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test schema", e);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(url);
        ds.setUser(user);
        ds.setPassword(password);
        ds.setCurrentSchema(schema);
        migrate(ds);
        return ds;
    }

    private static void migrate(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (Path file : migrations()) {
                s.execute(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("migration failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Path> migrations() {
        URL dir = TestDatabase.class.getClassLoader().getResource("db/migration");
        if (dir == null) {
            throw new IllegalStateException("db/migration not on the classpath");
        }
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            return files.filter(p -> p.getFileName().toString().matches("V\\d+__.+\\.sql"))
                    .sorted((a, b) -> Integer.compare(version(a), version(b))).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int version(Path p) {
        String name = p.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
