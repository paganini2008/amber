package com.github.amber.it;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.jdbc.DataSourceSpec;
import com.github.amber.jdbc.MetadataReader;
import com.github.amber.jdbc.OnlineMetadata;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional check of online mode against real servers. Connections come from the test configuration
 * {@code src/test/resources/online-it.properties} (git-ignored; see the {@code .example} next to it:
 * {@code sources=pg,oracle} and {@code <id>.dialect / url (or host, port, database) / user / password /
 * tables / driver}); run-time environment variables {@code AMBER_IT_SOURCES} / {@code AMBER_IT_<ID>_<KEY>} override
 * them. Never read from backend/.env. Skipped when no source is configured. Only catalog metadata is read.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class OnlineMetadataIT {

    private static String key(String id, String name) {
        return "AMBER_IT_" + id.toUpperCase(Locale.ROOT) + "_" + name;
    }

    private static final Properties CONFIG = load();

    private static Properties load() {
        Properties p = new Properties();
        try (var in = OnlineMetadataIT.class.getResourceAsStream("/online-it.properties")) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /**
     * {@code AMBER_IT_SOURCES} / {@code AMBER_IT_<ID>_<KEY>} from the run's environment, else
     * {@code sources} / {@code <id>.<key>} from the test configuration.
     */
    private static String value(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            String prop = key.equals("AMBER_IT_SOURCES") ? "sources"
                    : key.substring("AMBER_IT_".length()).replaceFirst("_(?=[A-Z]+$)", ".").toLowerCase(Locale.ROOT);
            v = CONFIG.getProperty(prop);
        }
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static DataSourceSpec spec(String id) {
        String port = value(key(id, "PORT"));
        return new DataSourceSpec(id, value(key(id, "DIALECT")), value(key(id, "URL")),
                value(key(id, "HOST")), port == null ? null : Integer.valueOf(port), value(key(id, "DATABASE")),
                value(key(id, "USER")), value(key(id, "PASSWORD")), Map.of(),
                value(key(id, "DRIVER")));      // optional driver jar; Db2 falls back to the test-scope jcc
    }

    @TestFactory
    Stream<DynamicTest> readsMetadataOfListedTables() {
        String sources = value("AMBER_IT_SOURCES");
        Assumptions.assumeTrue(sources != null, "no sources in src/test/resources/online-it.properties; skipped");
        List<DynamicTest> tests = new ArrayList<>();
        for (String id : sources.split("\\s*,\\s*")) {
            if (!id.isBlank()) {
                tests.add(DynamicTest.dynamicTest(id, () -> check(id)));
            }
        }
        return tests.stream();
    }

    private static void check(String id) throws Exception {
        DataSourceSpec ds = spec(id);
        Dialect dialect = ds.dialectEnum();
        try (MetadataReader r = MetadataReader.open(ds)) {
            MetadataReader.ServerInfo info = r.test();
            assertFalse(info.product().isBlank());           // some drivers ignore setReadOnly, so it is not asserted
        }
        List<ObjectName> names = new ArrayList<>(Arrays.stream(Objects.requireNonNullElse(value(key(id, "TABLES")), "").split("\\s*,\\s*"))
                .filter(s -> !s.isBlank()).map(s -> ObjectName.parse(dialect, s)).toList());
        Assumptions.assumeFalse(names.isEmpty(), id + ": no tables listed");
        int listed = names.size();
        names.add(ObjectName.parse(dialect, "amber_it_no_such_table_x9"));
        OnlineMetadata.Result result = OnlineMetadata.load(List.of(ds), names);
        assertTrue(result.errors().isEmpty(), () -> result.errors().toString());
        assertEquals(listed, result.tables().size(), () -> "found " + result.tables().stream().map(TableMeta::qualifiedName).toList()
                + ", missing " + result.missing());
        assertEquals(1, result.missing().size(), () -> result.missing().toString());
        for (TableMeta t : result.tables()) {
            assertFalse(t.columns().isEmpty(), t.qualifiedName() + " has columns");
        }
    }
}
