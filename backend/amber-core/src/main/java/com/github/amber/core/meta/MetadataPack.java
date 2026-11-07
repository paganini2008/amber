package com.github.amber.core.meta;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * A portable bundle of table metadata (JSON), saved from online mode or built from DDL, so the same
 * precise analysis works offline and can be shared with teammates.
 *
 * @param format   file format version
 * @param dialect  dialect id the metadata belongs to
 * @param source   where it was taken from (data source description or DDL files)
 * @param created  ISO-8601 time of creation
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record MetadataPack(int format, String dialect, String source, String created, List<TableMeta> tables) {

    public static final int FORMAT = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public MetadataPack {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public static MetadataPack of(String dialect, String source, List<TableMeta> tables) {
        return new MetadataPack(FORMAT, dialect, source, Instant.now().toString(), tables);
    }

    public static MetadataPack read(Path file) throws IOException {
        MetadataPack pack = MAPPER.readValue(Files.readAllBytes(file), MetadataPack.class);
        if (pack.format() > FORMAT) {
            throw new IOException("Metadata pack format " + pack.format() + " is newer than this version of Amber supports");
        }
        return pack;
    }

    public void write(Path file) throws IOException {
        Files.write(file, MAPPER.writeValueAsBytes(this));
    }
}
