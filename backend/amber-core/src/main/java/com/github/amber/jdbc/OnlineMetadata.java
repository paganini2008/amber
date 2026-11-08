package com.github.amber.jdbc;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.meta.TableMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Online mode, minimal: find the objects a script reads or writes that the script does not create
 * itself, look each one up in the configured data sources (in order), and return their metadata.
 * A name found in more than one data source is reported as ambiguous; the first match is used.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class OnlineMetadata {

    private static final Set<ObjectKind> DATA_OBJECTS = Set.of(ObjectKind.TABLE, ObjectKind.VIEW,
            ObjectKind.MATERIALIZED_VIEW);

    /** A name found in several data sources; {@code chosen} is the source whose table was used. */
    private record SourceRead(MetadataReader.ServerInfo info, Map<ObjectName, List<TableMeta>> tables) {
    }

    public record Ambiguous(String name, List<String> dataSources, String chosen) {
    }

    public record Result(List<TableMeta> tables, List<String> missing, List<Ambiguous> ambiguous,
                         Map<String, String> errors, Map<String, MetadataReader.ServerInfo> servers) {
    }

    private OnlineMetadata() {
    }

    /** External data objects the script references: not created by the script, not temp tables or CTEs. */
    private static int score(TableMeta t, Set<String> usedColumns, DataSourceSpec spec,
                             com.github.amber.core.Dialect scriptDialect) {
        int score = 0;
        if (scriptDialect != null && spec.dialect() != null && family(spec.dialectEnum()) == family(scriptDialect)) {
            score += 1000;
        }
        for (String c : usedColumns) {
            if (t.column(c).isPresent()) {
                score++;
            }
        }
        return score;
    }

    /** MySQL and MariaDB share tables and drivers; every other dialect is its own family. */
    private static com.github.amber.core.Dialect family(com.github.amber.core.Dialect d) {
        return d == com.github.amber.core.Dialect.MARIADB ? com.github.amber.core.Dialect.MYSQL : d;
    }

    public static List<ObjectName> referencedObjects(Script script) {
        Map<String, ObjectName> created = new LinkedHashMap<>();
        Map<String, ObjectName> referenced = new LinkedHashMap<>();
        script.flatten().forEach(st -> {
            for (ObjectRef r : st.refs()) {
                if (!DATA_OBJECTS.contains(r.kind())) {
                    continue;
                }
                if (r.access() == Access.CREATE) {
                    created.putIfAbsent(r.name().key(), r.name());
                } else if (created.values().stream().noneMatch(c -> c.sameObject(r.name()))) {
                    referenced.putIfAbsent(r.name().key(), r.name());
                }
            }
        });
        return new ArrayList<>(referenced.values());
    }

    /** Columns of each external table that the script uses, keyed like {@link ObjectName#key()}. */
    public static Map<String, Set<String>> usedColumns(Script script) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        script.flatten().forEach(st -> {
            if (st.lineage() == null) {
                return;
            }
            for (var e : st.lineage().edges()) {
                for (var n : List.of(e.from(), e.to())) {
                    if (n.ownerKind() == com.github.amber.core.lineage.ColumnNode.OwnerKind.TABLE
                            && !n.column().equals(com.github.amber.core.lineage.ColumnNode.ROWS)) {
                        out.computeIfAbsent(n.ownerKey(), k -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                                .add(n.column());
                    }
                }
            }
        });
        return out;
    }

    /** First match wins; see {@link #load(List, List, Map, com.github.amber.core.Dialect)} for a smarter pick. */
    public static Result load(List<DataSourceSpec> sources, List<ObjectName> names) {
        return load(sources, names, Map.of(), null);
    }

    /**
     * Looks every name up in every source. When a name is found in several sources, the table that fits
     * the script best is used: a source of the script's dialect family first, then the table holding
     * most of the columns the script uses, then the earlier source in the list.
     */
    public static Result load(List<DataSourceSpec> sources, List<ObjectName> names, Map<String, Set<String>> used,
                              com.github.amber.core.Dialect scriptDialect) {
        Map<String, TableMeta> found = new LinkedHashMap<>();
        Map<String, List<String>> where = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, MetadataReader.ServerInfo> servers = new LinkedHashMap<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> chosen = new LinkedHashMap<>();
        for (DataSourceSpec spec : sources) {
            String id = spec.id() == null ? spec.describe() : spec.id();
            try {
                // One hard deadline per source: a server that accepts and never answers must not hang the
                // whole lookup; the other sources still run.
                SourceRead read = Deadline.within(id, () -> {
                    try (MetadataReader reader = MetadataReader.open(spec)) {
                        MetadataReader.ServerInfo info = reader.test();
                        Map<ObjectName, List<TableMeta>> tables = new LinkedHashMap<>();
                        for (ObjectName name : names) {
                            tables.put(name, reader.read(List.of(name)));
                        }
                        return new SourceRead(info, tables);
                    }
                });
                servers.put(id, read.info());
                for (ObjectName name : names) {
                    List<TableMeta> t = read.tables().getOrDefault(name, List.of());
                    if (!t.isEmpty()) {
                        int score = score(t.get(0), used.getOrDefault(name.key(), Set.of()), spec, scriptDialect);
                        if (!found.containsKey(name.key()) || score > scores.get(name.key())) {
                            found.put(name.key(), t.get(0));
                            scores.put(name.key(), score);
                            chosen.put(name.key(), id);
                        }
                        where.computeIfAbsent(name.key(), k -> new ArrayList<>()).add(id);
                    }
                }
            } catch (Exception e) {
                errors.put(id, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        List<String> missing = new ArrayList<>();
        for (ObjectName n : names) {
            if (!found.containsKey(n.key())) {
                missing.add(n.toString());
            }
        }
        List<Ambiguous> ambiguous = new ArrayList<>();
        where.forEach((key, ids) -> {
            if (ids.size() > 1) {
                String shown = names.stream().filter(n -> n.key().equals(key)).map(ObjectName::toString).findFirst()
                        .orElse(found.get(key).qualifiedName());     // as the script writes it
                ambiguous.add(new Ambiguous(shown, ids, chosen.get(key)));
            }
        });
        return new Result(new ArrayList<>(found.values()), missing, ambiguous, errors, servers);
    }
}
