package com.github.amber.server;

import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.MetadataPack;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceDecoder;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import com.github.amber.jdbc.DataSourceSpec;
import com.github.amber.jdbc.Deadline;
import com.github.amber.jdbc.MetadataReader;
import com.github.amber.jdbc.OnlineMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code metadata.import}, {@code db.test} and {@code db.loadMetadata}, plus building the catalog an
 * analysis starts from (metadata packs, DDL files and inline tables).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class MetadataService {

    public record ImportRequest(String dialect, List<String> ddlFiles, List<String> packs, String save) {
    }

    public record PackSummary(String dialect, int tables, List<String> names, String saved, MetadataPack pack) {
    }

    public record TestRequest(DataSourceSpec dataSource) {
    }

    public record LoadRequest(String dialect, String text, String path, List<DataSourceSpec> dataSources, String save) {
    }

    public record LoadResult(int tables, List<String> missing, List<OnlineMetadata.Ambiguous> ambiguous,
                             Map<String, String> errors, Map<String, MetadataReader.ServerInfo> servers,
                             String saved, MetadataPack pack) {
    }

    /** Catalog from packs, DDL files and inline tables, for {@code analyze}. */
    public static Catalog catalog(String dialect, List<String> packs, List<String> ddlFiles, List<TableMeta> inline)
            throws IOException {
        Catalog c = new Catalog();
        if (packs != null) {
            for (String p : packs) {
                MetadataPack.read(Path.of(p)).tables().forEach(c::add);
            }
        }
        if (ddlFiles != null && !ddlFiles.isEmpty()) {
            DialectFrontend f = DialectFrontends.get(Dialect.fromId(dialect));
            for (String file : ddlFiles) {
                String text = SourceDecoder.decode(Files.readAllBytes(Path.of(file))).text();
                ScriptParse parse = f.parse(new SourceText(file, text), ParseOptions.FALLBACK);
                ScriptIrBuilder.build(f, parse, c);
            }
        }
        if (inline != null) {
            inline.forEach(c::add);
        }
        return c;
    }

    public PackSummary importMetadata(ImportRequest req) throws IOException {
        if (req == null || req.dialect() == null) {
            throw new IllegalArgumentException("'dialect' is required");
        }
        Catalog c = catalog(req.dialect(), req.packs(), req.ddlFiles(), null);
        String source = req.ddlFiles() == null ? "metadata packs" : "DDL: " + String.join(", ", req.ddlFiles());
        MetadataPack pack = MetadataPack.of(req.dialect(), source, c.tables());
        String saved = null;
        if (req.save() != null && !req.save().isBlank()) {
            pack.write(Path.of(req.save()));
            saved = req.save();
        }
        return new PackSummary(req.dialect(), pack.tables().size(),
                pack.tables().stream().map(TableMeta::qualifiedName).toList(), saved, pack);
    }

    public MetadataReader.ServerInfo test(TestRequest req) throws Exception {
        if (req == null || req.dataSource() == null) {
            throw new IllegalArgumentException("'dataSource' is required");
        }
        var ds = req.dataSource();
        return Deadline.within(ds.id() == null ? ds.describe() : ds.id(), () -> {
            try (MetadataReader r = MetadataReader.open(ds)) {
                return r.test();
            }
        });
    }

    public LoadResult load(LoadRequest req) throws IOException {
        if (req == null || req.dialect() == null || req.dataSources() == null || req.dataSources().isEmpty()) {
            throw new IllegalArgumentException("'dialect' and 'dataSources' are required");
        }
        DialectFrontend f = DialectFrontends.get(Dialect.fromId(req.dialect()));
        String text = req.text() != null ? req.text()
                : SourceDecoder.decode(Files.readAllBytes(Path.of(req.path()))).text();
        var script = ScriptIrBuilder.build(f, f.parse(SourceText.of(text), ParseOptions.FALLBACK));
        OnlineMetadata.Result r = OnlineMetadata.load(req.dataSources(), OnlineMetadata.referencedObjects(script),
                OnlineMetadata.usedColumns(script), f.dialect());
        List<String> sources = new ArrayList<>();
        req.dataSources().forEach(d -> sources.add(d.id() == null ? d.describe() : d.id()));
        MetadataPack pack = MetadataPack.of(req.dialect(), String.join(", ", sources), r.tables());
        String saved = null;
        if (req.save() != null && !req.save().isBlank()) {
            pack.write(Path.of(req.save()));
            saved = req.save();
        }
        return new LoadResult(r.tables().size(), r.missing(), r.ambiguous(), r.errors(), r.servers(), saved, pack);
    }
}
