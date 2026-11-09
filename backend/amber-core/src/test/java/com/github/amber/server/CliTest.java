package com.github.amber.server;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontends;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class CliTest {

    @TempDir
    Path dir;

    private int run(List<String> args, ByteArrayOutputStream out) {
        return Cli.analyze(Main.mapper(), args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void printsOutlineAndObjects() throws Exception {
        Path file = dir.resolve("job.sql");
        Files.writeString(file, "create table stage_a as select * from src_a;\ninsert into tgt select * from stage_a;\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, run(List.of(file.toString(), "--dialect", "postgresql"), out));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Create table stage_a from src_a"), text);
        assertTrue(text.contains("INTERMEDIATE"), text);

        ByteArrayOutputStream json = new ByteArrayOutputStream();
        assertEquals(0, run(List.of(file.toString(), "--dialect", "postgresql", "--json"), json));
        assertTrue(json.toString(StandardCharsets.UTF_8).contains("\"lifecycles\""));
    }

    @Test
    void failsOnSyntaxErrorsAndBadUsage() throws Exception {
        Path file = dir.resolve("bad.sql");
        Files.writeString(file, "select from where;");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(1, run(List.of(file.toString(), "--dialect", "oracle"), out));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("ERROR 1:"));
        assertEquals(0, run(List.of(file.toString(), "--dialect", "oracle", "--fallback"), new ByteArrayOutputStream()));
        assertEquals(2, run(List.of(file.toString()), new ByteArrayOutputStream()));
        assertEquals(2, run(List.of("missing.sql", "--dialect", "oracle"), new ByteArrayOutputStream()));
    }

    @Test
    void warmupParsesEveryDialectScript() {
        for (var d : Dialect.values()) {
            String sql = Warmup.script(d);
            assertTrue(sql != null && !sql.isBlank(), d::toString);
            var parse = DialectFrontends.get(d)
                    .parse(SourceText.of(sql));
            assertTrue(parse.success(), () -> d + ": " + parse.diagnostics());
        }
        Warmup.run(new PrintStream(new ByteArrayOutputStream()));
    }
}
