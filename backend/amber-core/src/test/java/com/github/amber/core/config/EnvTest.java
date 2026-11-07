package com.github.amber.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading backend/.env: syntax, precedence of the process environment, and where the file is looked for.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class EnvTest {

    @Test
    void parsesDotEnvSyntax(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".env");
        Files.writeString(f, """
                # comment
                A=1
                export B = two words
                C="quoted # not a comment"
                D='single'
                E=value # trailing comment
                EMPTY=
                not a line
                """);
        Map<String, String> m = Env.read(f);
        assertEquals("1", m.get("A"));
        assertEquals("two words", m.get("B"));
        assertEquals("quoted # not a comment", m.get("C"));
        assertEquals("single", m.get("D"));
        assertEquals("value", m.get("E"));
        assertEquals("", m.get("EMPTY"));
        assertEquals(6, m.size());
        assertTrue(Env.read(dir.resolve("missing")).isEmpty());
    }

    @Test
    void processEnvironmentWinsAndBlankMeansUnset() {
        Env env = new Env(Map.of("A", "file", "B", "file", "N", "x7", "E", ""), Map.of("A", "process", "B", " "), null);
        assertEquals("process", env.value("A"));
        assertEquals("file", env.value("B"), "a blank process value does not hide the file");
        assertNull(env.value("E"));
        assertEquals("fallback", env.value("E", "fallback"));
        assertEquals(42, env.integer("MISSING", 42));
        assertEquals(42, env.integer("N", 42), "malformed numbers fall back");
        assertTrue(env.fileKeys().contains("A"));
        assertNull(env.source());
    }

    @Test
    void onlyTheBackendSourceRootCounts(@TempDir Path dir) throws Exception {
        Path backend = Files.createDirectories(dir.resolve("backend/amber-core/target/classes"));
        Path root = dir.resolve("backend");
        Files.writeString(dir.resolve(".env"), "X=outside\n");           // a stray .env above: ignored
        assertNull(Env.search(backend));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve(".env"), "X=1\n");
        assertEquals(root.resolve(".env"), Env.search(backend));
        assertEquals(root.resolve(".env"), Env.locate(root.resolve(".env").toString()));
        assertNull(Env.locate(dir.resolve("nope.env").toString()));
        assertTrue(Env.get() != null);
    }
}
