package it.portfolio.eaa.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Database di test: PostgreSQL + pgvector REALI (non esiste un sostituto in memoria del tipo vector).
 *
 * Lo schema NON è duplicato nei test: si esegue db/init/01-init.sql, la stessa unica fonte di verità usata da
 * Docker e dal backend Laravel (decisione D1). Se qualcuno cambia lo schema, i test lo vedono subito.
 *
 * PROTEZIONE (decisione D19): si rifiuta di partire se il nome del database non finisce con "_test", perché
 * prima di ogni esecuzione lo schema viene ricreato da zero e tutti i dati vanno persi.
 */
public final class TestDatabase {

    private static final String HOST = env("DB_HOST", "localhost");
    private static final String PORT = env("DB_PORT", "5432");
    private static final String USER = env("DB_USERNAME", "eaa");
    private static final String PASSWORD = env("DB_PASSWORD", "eaa");

    /** Database dedicato ai test di Spring (Laravel usa eaa_test: così i due non si pestano i piedi). */
    public static final String NAME = env("TEST_DB_DATABASE", "eaa_spring_test");

    private static boolean ready;

    private TestDatabase() {}

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + NAME;
    }

    public static String username() {
        return USER;
    }

    public static String password() {
        return PASSWORD;
    }

    /** Crea il database se manca e ricrea lo schema da db/init/01-init.sql. Una sola volta per JVM. */
    public static synchronized void ensureSchema() {
        if (ready) {
            return;
        }
        if (!NAME.endsWith("_test")) {
            throw new IllegalStateException("Rifiuto di eseguire i test sul database \"" + NAME
                    + "\": deve terminare con \"_test\" (i test cancellano tutte le tabelle).");
        }

        try {
            createDatabaseIfMissing();
            try (Connection connection = DriverManager.getConnection(jdbcUrl(), USER, PASSWORD)) {
                try (Statement st = connection.createStatement()) {
                    st.execute("DROP SCHEMA public CASCADE");
                    st.execute("CREATE SCHEMA public");
                }
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(initScript()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Impossibile preparare il database di test. PostgreSQL è acceso? "
                    + "(docker compose up -d postgres)", e);
        }
        ready = true;
    }

    private static void createDatabaseIfMissing() throws SQLException {
        try (Connection admin = DriverManager.getConnection("jdbc:postgresql://" + HOST + ":" + PORT + "/postgres", USER, PASSWORD);
             Statement st = admin.createStatement()) {
            boolean exists = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + NAME + "'").next();
            if (!exists) {
                st.execute("CREATE DATABASE " + NAME);
            }
        }
    }

    /** Il test gira con la cartella del modulo come directory corrente: lo script sta nella root del monorepo. */
    private static Path initScript() {
        for (String candidate : new String[] {"../db/init/01-init.sql", "db/init/01-init.sql"}) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("db/init/01-init.sql non trovato (cwd: " + Path.of("").toAbsolutePath() + ")");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
