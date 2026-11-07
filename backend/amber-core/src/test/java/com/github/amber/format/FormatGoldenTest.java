package com.github.amber.format;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden files under {@code src/test/resources/format}: {@code <dialect>_<name>.input.sql} must format
 * to {@code <dialect>_<name>.expected.sql}, the result must be a fixed point, keep every token and
 * comment, and parse with the same status as the input. Run with {@code -Dformat.update=true} to
 * (re)write the expected files after a deliberate layout change, then review the diff.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class FormatGoldenTest {

    private static final Path DIR = Path.of("src/test/resources/format");

    @TestFactory
    Stream<DynamicTest> goldenFiles() throws IOException {
        List<Path> inputs;
        try (Stream<Path> files = Files.list(DIR)) {
            inputs = files.filter(p -> p.getFileName().toString().endsWith(".input.sql")).sorted().toList();
        }
        assertTrue(inputs.size() >= 5, "golden inputs missing");
        return inputs.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> check(p)));
    }

    private static void check(Path input) throws IOException {
        String file = input.getFileName().toString();
        Dialect dialect = Dialect.fromId(file.substring(0, file.indexOf('_')));
        String text = Files.readString(input);
        FormatResult result = Formatter.format(dialect, text, FormatOptions.DEFAULTS);
        assertEquals(List.of(), result.warnings(), "no statement should be skipped");

        Path expectedFile = input.resolveSibling(file.replace(".input.sql", ".expected.sql"));
        if (Boolean.getBoolean("format.update") || !Files.exists(expectedFile)) {
            Files.writeString(expectedFile, result.text());
        }
        assertEquals(Files.readString(expectedFile), result.text(), file);

        assertEquals(result.text(), Formatter.format(dialect, result.text(), FormatOptions.DEFAULTS).text(),
                "formatting must be idempotent");
        assertNull(FormatCorpusTest.sameUnits(dialect, text, result.text()));

        ScriptParse before = DialectFrontends.get(dialect).parse(SourceText.of(text));
        ScriptParse after = DialectFrontends.get(dialect).parse(SourceText.of(result.text()));
        assertEquals(before.status(), after.status());
        assertEquals(before.units().size(), after.units().size());
    }
}
