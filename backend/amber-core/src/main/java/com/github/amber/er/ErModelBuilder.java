package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.i18n.Messages;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnEvidence;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import com.github.amber.dialect.lineage.ColumnEvidenceScanner;
import com.github.amber.er.ErModel.Cardinality;
import com.github.amber.er.ErModel.Certainty;
import com.github.amber.er.ErModel.ColumnSource;
import com.github.amber.er.ErModel.Evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@link ErModel} from an analyzed script and the catalog it was analyzed with (imported DDL,
 * metadata packs, online metadata, and the script's own DDL).
 *
 * <p>Cardinality of a join: when the columns of one side cover its primary or a unique key (metadata or
 * DDL), the other side is the many side ({@code MANY_TO_ONE}, INFERRED); both unique gives
 * {@code ONE_TO_ONE}. {@code MANY_TO_MANY} is only claimed when metadata with keys proves neither side
 * unique. Without keys, a single-column join is oriented by naming conventions ({@link Naming}: the side
 * that looks like its table's own key — {@code id}, {@code customer_id} in {@code customers} — is the one
 * side) with confidence POSSIBLE; otherwise the cardinality is {@code UNKNOWN}.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class ErModelBuilder {

    /**
     * @param includeDataFlow  add {@code DATA_FLOW} relationships for rows copied between tables
     * @param includeNameMatch add {@code NAME_MATCH} relationships suggested by column names only
     * @param maxOccurrences   occurrences listed per relationship ({@code count} has them all)
     */
    public record Options(boolean includeDataFlow, boolean includeNameMatch, int maxOccurrences) {

        public static final Options DEFAULTS = new Options(false, true, 100);
    }

    private static final String SCRIPT_DDL = "script DDL";
    /** Evidence facts named in a relationship note. */
    private static final int MAX_FACTS = 3;
    private static final Set<ObjectKind> REF_KINDS = Set.of(ObjectKind.TABLE, ObjectKind.TEMP_TABLE,
            ObjectKind.TABLE_VARIABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW);

    /** A relationship while it is built: ends in canonical orientation, then directed by {@link #decide}. */
    private static final class Rel {
        Entities.Info a;
        List<String> aCols;
        Entities.Info b;
        List<String> bCols;
        Evidence evidence;
        Certainty confidence;
        Cardinality cardinality;
        String note;
        String constraint;
        final List<ErModel.Occurrence> occurrences = new ArrayList<>();
        int count;
        int joins;
        /** Joins whose columns only the script's column evidence tied to a table, and the first such facts. */
        int evidenceJoins;
        final List<ColumnEvidence.Fact> facts = new ArrayList<>();
    }

    /** A column while it is built. */
    private static final class Col {
        String name;
        String type;
        Boolean nullable;
        ColumnSource source;
        boolean fk;
        boolean joined;
    }

    private final Script script;
    private final Catalog catalog;
    private final Options options;
    private final Dialect dialect;
    private final Entities entities = new Entities();
    private final Map<Entities.Info, Map<String, Col>> columns = new HashMap<>();
    private final Map<String, Rel> rels = new LinkedHashMap<>();
    /** Entities the script creates or alters (their DDL foreign keys bring the referenced tables in). */
    private final List<Entities.Info> declared = new ArrayList<>();
    private boolean strict;

    private ErModelBuilder(Script script, Catalog catalog, Options options) {
        this.script = script;
        this.catalog = catalog == null ? new Catalog() : catalog;
        this.options = options == null ? Options.DEFAULTS : options;
        this.dialect = script.dialect();
    }

    /** Builds the IR with a copy of {@code imported} and the model from both. */
    public static ErModel build(DialectFrontend frontend, ScriptParse parse, Catalog imported, Options options) {
        Catalog working = imported == null ? new Catalog() : imported.copy();
        Script script = ScriptIrBuilder.build(frontend, parse, working);
        return build(script, working, options);
    }

    /**
     * @param catalog the catalog the script was built with (it holds the script's own DDL as well as the
     *                imported metadata)
     */
    public static ErModel build(Script script, Catalog catalog, Options options) {
        return new ErModelBuilder(script, catalog, options).run();
    }

    /** As {@link #build(Script, Catalog, Options)}; {@code strict} rethrows a statement's failure (tests). */
    static ErModel build(Script script, Catalog catalog, Options options, boolean strict) {
        ErModelBuilder b = new ErModelBuilder(script, catalog, options);
        b.strict = strict;
        return b.run();
    }

    private ErModel run() {
        long started = System.nanoTime();
        List<Statement> steps = script.flatten();
        ColumnEvidence evidence = catalog.evidence();
        if (evidence == null) {
            evidence = ColumnEvidenceScanner.scan(dialect, script.source());   // a script built without this catalog
        }
        JoinScanner scanner = new JoinScanner(dialect, script.source(), catalog, evidence);
        for (int i = 0; i < steps.size(); i++) {
            try {
                scanner.scan(i, steps.get(i));
            } catch (RuntimeException e) {
                if (strict) {
                    throw e;
                }
                // one unreadable statement never spoils the model
            }
        }
        noteEntities(steps, scanner);
        for (JoinScanner.Observation o : scanner.observations()) {
            entities.note(o.left().get(0).table(), o.left().get(0).kind());
            entities.note(o.right().get(0).table(), o.right().get(0).kind());
        }
        noteForeignKeyTargets();
        entities.freeze();
        for (Entities.Info e : entities.all()) {
            columnsOf(e);
        }
        usageColumns(steps);
        foreignKeys();
        joins(scanner.observations());
        for (Rel r : rels.values()) {
            if (r.evidence == Evidence.JOIN) {
                decide(r);
            }
        }
        if (options.includeNameMatch()) {
            nameMatches();
        }
        if (options.includeDataFlow()) {
            dataFlows(steps);
        }
        List<ErModel.Relationship> relationships = relationships();
        List<ErModel.Entity> list = entityList();
        long ms = (System.nanoTime() - started) / 1_000_000;
        return new ErModel(list, relationships, MermaidWriter.write(list, relationships),
                new ErModel.Stats(steps.size(), scanner.equalities(), scanner.unresolved(), ms));
    }

    // ----- entities -------------------------------------------------------------------------------

    private void noteEntities(List<Statement> steps, JoinScanner scanner) {
        for (Statement st : steps) {
            for (ObjectRef r : st.refs()) {
                if (r.via() != null || !REF_KINDS.contains(r.kind()) || scanner.isAlias(r)) {
                    continue;
                }
                Entities.Info i = entities.note(r.name(), r.kind().name());
                if (r.access() == Access.CREATE) {
                    i.created = true;
                    if (i.definition == null) {
                        i.definition = st.range();
                    }
                }
                if ((r.access() == Access.CREATE || r.access() == Access.ALTER) && !i.declaredInScript) {
                    i.declaredInScript = true;
                    declared.add(i);
                }
            }
        }
    }

    /** Tables a foreign key of the script's own DDL references are part of the model. */
    private void noteForeignKeyTargets() {
        for (Entities.Info i : new ArrayList<>(declared)) {
            TableMeta m = meta(i.key);
            if (m == null || !SCRIPT_DDL.equals(m.source())) {
                continue;
            }
            for (TableMeta.ForeignKeyMeta fk : m.foreignKeys()) {
                ObjectName target = refName(fk);
                if (target != null) {
                    entities.note(target, "TABLE");
                }
            }
        }
    }

    private ObjectName refName(TableMeta.ForeignKeyMeta fk) {
        if (fk.refTable() == null || fk.refTable().isBlank()) {
            return null;
        }
        List<ObjectName.Part> parts = new ArrayList<>();
        if (fk.refSchema() != null && !fk.refSchema().isBlank()) {
            parts.add(new ObjectName.Part(fk.refSchema(), false));
        }
        parts.add(new ObjectName.Part(fk.refTable(), false));
        return ObjectName.of(dialect, parts);
    }

    private TableMeta meta(String key) {
        int dot = key.lastIndexOf('.');
        return catalog.meta(key, dot < 0 ? key : key.substring(dot + 1)).orElse(null);
    }

    private TableMeta meta(Entities.Info e) {
        return meta(e.key);
    }

    // ----- columns --------------------------------------------------------------------------------

    private Map<String, Col> columnsOf(Entities.Info e) {
        return columns.computeIfAbsent(e, x -> {
            Map<String, Col> cols = new LinkedHashMap<>();
            TableMeta m = meta(x);
            if (m != null) {
                ColumnSource src = SCRIPT_DDL.equals(m.source()) ? ColumnSource.DDL : ColumnSource.METADATA;
                for (TableMeta.ColumnMeta c : m.columns()) {
                    Col col = new Col();
                    col.name = c.name();
                    col.type = c.type();
                    col.nullable = c.nullable();
                    col.source = src;
                    cols.putIfAbsent(upper(c.name()), col);
                }
            }
            return cols;
        });
    }

    private Col column(Entities.Info e, String name) {
        String n = name.replaceAll("^[\"`\\[]|[\"`\\]]$", "");
        return columnsOf(e).computeIfAbsent(upper(n), k -> {
            Col c = new Col();
            c.name = n;
            c.source = ColumnSource.USAGE;
            return c;
        });
    }

    private void usageColumns(List<Statement> steps) {
        for (Statement st : steps) {
            if (st.lineage() == null) {
                continue;
            }
            for (ColumnEdge edge : st.lineage().edges()) {
                usage(edge.from());
                usage(edge.to());
            }
        }
    }

    private void usage(ColumnNode n) {
        if (n.isRows() || "*".equals(n.column()) || !JoinScanner.BASE_KINDS.contains(n.ownerKind().name())) {
            return;
        }
        Entities.Info e = entities.getKey(n.ownerKey());
        if (e != null) {
            column(e, n.column());
        }
    }

    /** Column names as the entity spells them. */
    private List<String> names(Entities.Info e, List<String> cols) {
        List<String> out = new ArrayList<>();
        for (String c : cols) {
            out.add(column(e, c).name);
        }
        return out;
    }

    // ----- relationships --------------------------------------------------------------------------

    private void foreignKeys() {
        for (Entities.Info e : entities.all()) {
            TableMeta m = meta(e);
            if (m == null || m.foreignKeys().isEmpty()) {
                continue;
            }
            Evidence evidence = SCRIPT_DDL.equals(m.source()) ? Evidence.DECLARED_FK : Evidence.METADATA_FK;
            for (TableMeta.ForeignKeyMeta fk : m.foreignKeys()) {
                Entities.Info target = entities.get(refName(fk));
                if (target == null || fk.columns().isEmpty()) {
                    continue;
                }
                List<String> to = fk.refColumns();
                TableMeta tm = meta(target);
                if (to.isEmpty() && tm != null && tm.primaryKey().size() == fk.columns().size()) {
                    to = tm.primaryKey();
                }
                if (to.size() != fk.columns().size()) {
                    to = List.of();
                }
                Rel r = new Rel();
                r.a = e;
                r.aCols = names(e, fk.columns());
                r.b = target;
                r.bCols = names(target, to);
                r.evidence = evidence;
                r.confidence = Certainty.CERTAIN;
                r.cardinality = unique(m, fk.columns()) ? Cardinality.ONE_TO_ONE : Cardinality.MANY_TO_ONE;
                r.constraint = fk.name();
                String key = key(e, r.aCols, target, r.bCols);
                Rel have = rels.get(key);
                if (have == null || have.evidence.ordinal() > evidence.ordinal()) {
                    rels.put(key, r);
                }
            }
        }
    }

    private void joins(List<JoinScanner.Observation> observations) {
        for (JoinScanner.Observation o : observations) {
            Entities.Info a = entities.get(o.left().get(0).table());
            Entities.Info b = entities.get(o.right().get(0).table());
            if (a == null || b == null) {
                continue;
            }
            List<String> aCols = names(a, o.left().stream().map(JoinScanner.Side::column).toList());
            List<String> bCols = names(b, o.right().stream().map(JoinScanner.Side::column).toList());
            if (a == b && upperAll(aCols).equals(upperAll(bCols))) {
                continue;                                   // x.k = y.k on the same table: no relationship
            }
            aCols.forEach(c -> column(a, c).joined = true);
            bCols.forEach(c -> column(b, c).joined = true);
            String key = key(a, aCols, b, bCols);
            Rel r = rels.get(key);
            if (r == null) {
                r = new Rel();
                r.a = a;
                r.aCols = aCols;
                r.b = b;
                r.bCols = bCols;
                r.evidence = Evidence.JOIN;
                rels.put(key, r);
            }
            r.joins++;
            r.count++;
            if (!o.evidence().isEmpty()) {
                r.evidenceJoins++;
                for (ColumnEvidence.Fact f : o.evidence()) {
                    if (r.facts.size() < MAX_FACTS && !r.facts.contains(f)) {
                        r.facts.add(f);
                    }
                }
            }
            if (r.occurrences.size() < options.maxOccurrences()) {
                r.occurrences.add(new ErModel.Occurrence(o.statement(), o.range()));
            }
        }
    }

    /** Direction, cardinality and confidence of a join seen in the script. */
    private void decide(Rel r) {
        TableMeta ma = meta(r.a);
        TableMeta mb = meta(r.b);
        boolean ua = unique(ma, r.aCols);
        boolean ub = unique(mb, r.bCols);
        String seen = r.joins == 1 ? t("er.seen.one") : t("er.seen.other", r.joins);
        if (ua && ub) {
            r.cardinality = Cardinality.ONE_TO_ONE;
            r.confidence = Certainty.INFERRED;
            r.note = t("er.note.oneToOne", seen);
        } else if (ub || ua) {
            if (ua) {
                flip(r);
            }
            r.cardinality = Cardinality.MANY_TO_ONE;
            r.confidence = Certainty.INFERRED;
            r.note = t(isPk(meta(r.b), r.bCols) ? "er.note.coversPk" : "er.note.coversUk", seen, display(r.b, r.bCols),
                    r.b.display);
        } else if (keysKnown(ma) && keysKnown(mb)) {
            r.cardinality = Cardinality.MANY_TO_MANY;
            r.confidence = Certainty.INFERRED;
            r.note = t("er.note.manyToMany", seen);
        } else {
            int one = r.aCols.size() == 1 ? namedOneSide(r, ma, mb) : 0;
            if (one != 0) {
                if (one < 0) {
                    flip(r);
                }
                r.cardinality = Cardinality.MANY_TO_ONE;
                r.confidence = Certainty.POSSIBLE;
                r.note = t("er.note.guessed", seen, display(r.a, r.aCols), display(r.b, r.bCols));
            } else {
                r.cardinality = Cardinality.UNKNOWN;
                r.confidence = Certainty.POSSIBLE;
                r.note = t("er.note.unknown", seen);
            }
        }
        if (r.evidenceJoins > 0) {
            r.note = r.note + " " + evidenceNote(r);
        }
    }

    /**
     * Why columns of a join without aliases were tied to their tables: the script's own column evidence
     * (a join is never CERTAIN, so the confidence decided above stays INFERRED or POSSIBLE).
     */
    private static String evidenceNote(Rel r) {
        List<String> facts = new ArrayList<>();
        for (ColumnEvidence.Fact f : r.facts) {
            facts.add(t("er.evidence.fact", f.table() + "." + f.column(),
                    t("er.evidence.kind." + f.kind().name()), f.line()));
        }
        String list = String.join("; ", facts);
        return r.evidenceJoins == r.joins ? t("er.note.evidence.all", list)
                : t("er.note.evidence.some", r.evidenceJoins, list);
    }

    /**
     * 1 when b looks like the one side by names, -1 when a does, 0 when the names do not tell. The one side's
     * column must look like its own table's key ({@code id}, {@code customer_id} in {@code customers}), the
     * other side's like a reference ({@code …_id}, {@code …_no}, {@code …_code}), and a side whose keys are
     * known from metadata (and do not cover the column) cannot be the one side.
     */
    private static int namedOneSide(Rel r, TableMeta ma, TableMeta mb) {
        String ca = r.aCols.get(0);
        String cb = r.bCols.get(0);
        boolean aOwn = Naming.ownKey(ca, r.a.display);
        boolean bOwn = Naming.ownKey(cb, r.b.display);
        if (bOwn && !aOwn && Naming.base(ca) != null && !keysKnown(mb)) {
            return 1;
        }
        if (aOwn && !bOwn && Naming.base(cb) != null && !keysKnown(ma)) {
            return -1;
        }
        return 0;
    }

    private static void flip(Rel r) {
        Entities.Info e = r.a;
        r.a = r.b;
        r.b = e;
        List<String> c = r.aCols;
        r.aCols = r.bCols;
        r.bCols = c;
    }

    /** Relationships suggested only by names: {@code orders.customer_id} next to {@code customers.id}. */
    private void nameMatches() {
        Map<String, List<Entities.Info>> byStem = new HashMap<>();
        for (Entities.Info e : entities.all()) {
            for (String s : Naming.stems(e.display)) {
                byStem.computeIfAbsent(s, k -> new ArrayList<>()).add(e);
            }
        }
        Set<String> linked = new HashSet<>();
        for (Rel r : rels.values()) {
            for (String c : r.aCols) {
                linked.add(r.a.key + "." + upper(c) + ">" + r.b.key);
            }
            for (String c : r.bCols) {
                linked.add(r.b.key + "." + upper(c) + ">" + r.a.key);
            }
        }
        for (Entities.Info a : entities.all()) {
            for (Col c : new ArrayList<>(columnsOf(a).values())) {
                String base = Naming.base(c.name);
                if (base == null || Naming.ownKey(c.name, a.display)) {
                    continue;
                }
                Set<Entities.Info> candidates = new LinkedHashSet<>(byStem.getOrDefault(base, List.of()));
                candidates.addAll(byStem.getOrDefault(Naming.singular(base), List.of()));
                candidates.remove(a);
                if (candidates.size() != 1) {
                    continue;                               // none, or ambiguous (customer and customers)
                }
                Entities.Info b = candidates.iterator().next();
                if (linked.contains(a.key + "." + upper(c.name) + ">" + b.key)) {
                    continue;
                }
                String target = targetColumn(b, c.name);
                if (target == null) {
                    continue;
                }
                Rel r = new Rel();
                r.a = a;
                r.aCols = List.of(c.name);
                r.b = b;
                r.bCols = List.of(target);
                r.evidence = Evidence.NAME_MATCH;
                r.confidence = Certainty.POSSIBLE;
                r.cardinality = unique(meta(a), r.aCols) ? Cardinality.ONE_TO_ONE : Cardinality.MANY_TO_ONE;
                r.note = t("er.note.nameMatch", display(a, r.aCols), display(b, r.bCols));
                rels.putIfAbsent(key(a, r.aCols, b, r.bCols), r);
                linked.add(a.key + "." + upper(c.name) + ">" + b.key);
            }
        }
    }

    /** The key column of {@code b} that {@code column} would refer to, or null when b has none that fits. */
    private String targetColumn(Entities.Info b, String column) {
        TableMeta mb = meta(b);
        if (mb != null && !mb.primaryKey().isEmpty()) {
            if (mb.primaryKey().size() != 1) {
                return null;
            }
            String pk = mb.primaryKey().get(0);
            return pk.equalsIgnoreCase(column) || pk.equalsIgnoreCase("id") || Naming.ownKey(pk, b.display)
                    ? column(b, pk).name : null;
        }
        Map<String, Col> cols = columnsOf(b);
        if (cols.containsKey(upper(column))) {
            return cols.get(upper(column)).name;
        }
        return cols.containsKey("ID") ? cols.get("ID").name : null;
    }

    /** Rows copied from one table into another, through CTEs and derived tables of the same statement. */
    private void dataFlows(List<Statement> steps) {
        for (int i = 0; i < steps.size(); i++) {
            Statement st = steps.get(i);
            if (st.lineage() == null) {
                continue;
            }
            Map<String, List<ColumnEdge>> into = new HashMap<>();
            for (ColumnEdge e : st.lineage().edges()) {
                if (e.isDirect()) {
                    into.computeIfAbsent(e.to().key(), k -> new ArrayList<>()).add(e);
                }
            }
            Map<String, Rel> perStatement = new LinkedHashMap<>();
            for (ColumnEdge e : st.lineage().edges()) {
                ColumnNode to = e.to();
                if (!e.isDirect() || to.isRows() || !JoinScanner.BASE_KINDS.contains(to.ownerKind().name())) {
                    continue;
                }
                Entities.Info b = entities.getKey(to.ownerKey());
                if (b == null) {
                    continue;
                }
                for (ColumnNode from : bases(e.from(), into, 0)) {
                    Entities.Info a = entities.getKey(from.ownerKey());
                    if (a == null || a == b) {
                        continue;
                    }
                    Rel r = perStatement.computeIfAbsent(a.key + "|" + b.key, k -> {
                        Rel x = new Rel();
                        x.a = a;
                        x.b = b;
                        x.aCols = new ArrayList<>();
                        x.bCols = new ArrayList<>();
                        return x;
                    });
                    if ("*".equals(from.column()) || "*".equals(to.column())) {
                        continue;                           // INSERT … SELECT *: the flow, without column pairs
                    }
                    String fc = column(a, from.column()).name;
                    String tc = column(b, to.column()).name;
                    boolean known = false;
                    for (int k = 0; k < r.aCols.size(); k++) {
                        known |= r.aCols.get(k).equalsIgnoreCase(fc) && r.bCols.get(k).equalsIgnoreCase(tc);
                    }
                    if (!known) {
                        r.aCols.add(fc);
                        r.bCols.add(tc);
                    }
                }
            }
            for (Rel p : perStatement.values()) {
                Rel r = rels.computeIfAbsent("DATA_FLOW|" + p.a.key + "|" + p.b.key, k -> {
                    Rel x = new Rel();
                    x.a = p.a;
                    x.b = p.b;
                    x.aCols = new ArrayList<>();
                    x.bCols = new ArrayList<>();
                    x.evidence = Evidence.DATA_FLOW;
                    x.confidence = Certainty.INFERRED;
                    x.cardinality = Cardinality.UNKNOWN;
                    return x;
                });
                for (int k = 0; k < p.aCols.size(); k++) {
                    boolean known = false;
                    for (int j = 0; j < r.aCols.size(); j++) {
                        known |= r.aCols.get(j).equalsIgnoreCase(p.aCols.get(k))
                                && r.bCols.get(j).equalsIgnoreCase(p.bCols.get(k));
                    }
                    if (!known) {
                        r.aCols.add(p.aCols.get(k));
                        r.bCols.add(p.bCols.get(k));
                    }
                }
                r.count++;
                if (r.occurrences.size() < options.maxOccurrences()) {
                    r.occurrences.add(new ErModel.Occurrence(i, st.range()));
                }
                r.note = t(r.count == 1 ? "er.note.dataFlow.one" : "er.note.dataFlow.other", p.a.display, p.b.display,
                        r.count);
            }
        }
    }

    /** Base-table columns behind a node, through CTEs and derived tables (any transform). */
    private static List<ColumnNode> bases(ColumnNode n, Map<String, List<ColumnEdge>> into, int depth) {
        if (JoinScanner.BASE_KINDS.contains(n.ownerKind().name())) {
            return n.isRows() ? List.of() : List.of(n);
        }
        if (depth > 8 || n.ownerKind() != ColumnNode.OwnerKind.CTE && n.ownerKind() != ColumnNode.OwnerKind.SUBQUERY) {
            return List.of();
        }
        List<ColumnNode> out = new ArrayList<>();
        for (ColumnEdge e : into.getOrDefault(n.key(), List.of())) {
            out.addAll(bases(e.from(), into, depth + 1));
        }
        return out;
    }

    // ----- output ---------------------------------------------------------------------------------

    private List<ErModel.Relationship> relationships() {
        List<Rel> list = new ArrayList<>(rels.values());
        for (Rel r : list) {
            if (r.evidence == Evidence.DECLARED_FK || r.evidence == Evidence.METADATA_FK) {
                String kind = t(r.evidence == Evidence.DECLARED_FK ? "er.fk.declared" : "er.fk.metadata");
                String name = r.constraint == null ? "" : " (" + r.constraint + ")";
                r.note = kind + name
                        + (r.bCols.isEmpty() ? t("er.fk.refsUnknown") : "")
                        + (r.joins == 0 ? t("er.fk.notJoined") : r.joins == 1 ? t("er.fk.joined.one")
                        : t("er.fk.joined.other", r.joins));
            }
            boolean pointsAtKey = r.cardinality == Cardinality.MANY_TO_ONE || r.cardinality == Cardinality.ONE_TO_ONE;
            if (pointsAtKey && r.evidence != Evidence.DATA_FLOW && r.evidence != Evidence.NAME_MATCH) {
                r.aCols.forEach(c -> column(r.a, c).fk = true);
            }
        }
        list.sort(Comparator.<Rel>comparingInt(r -> r.evidence.ordinal())
                .thenComparing(r -> -r.count)
                .thenComparing(r -> r.a.display.toLowerCase(Locale.ROOT))
                .thenComparing(r -> r.b.display.toLowerCase(Locale.ROOT))
                .thenComparing(r -> String.join(",", r.aCols).toLowerCase(Locale.ROOT)));
        List<ErModel.Relationship> out = new ArrayList<>();
        for (Rel r : list) {
            out.add(new ErModel.Relationship("R" + (out.size() + 1), new ErModel.End(r.a.key, r.aCols),
                    new ErModel.End(r.b.key, r.bCols), r.cardinality, r.evidence, r.confidence, r.occurrences, r.count,
                    r.note, r.constraint));
        }
        return out;
    }

    private List<ErModel.Entity> entityList() {
        List<ErModel.Entity> out = new ArrayList<>();
        for (Entities.Info e : entities.all()) {
            TableMeta m = meta(e);
            String kind = e.kind;
            if ("TABLE".equals(kind) && m != null && "VIEW".equalsIgnoreCase(m.kind())) {
                kind = "VIEW";
            }
            List<ErModel.Column> cols = new ArrayList<>();
            for (Col c : columnsOf(e).values()) {
                boolean pk = m != null && containsIgnoreCase(m.primaryKey(), c.name);
                cols.add(new ErModel.Column(c.name, c.type, c.nullable, pk, unique(m, List.of(c.name)), c.fk,
                        c.joined, c.source));
            }
            out.add(new ErModel.Entity(e.key, e.display, kind, cols, e.created, e.definition,
                    m == null ? null : m.source()));
        }
        return out;
    }

    // ----- helpers --------------------------------------------------------------------------------

    /** Same key for both orientations of the same column pairs. */
    static String key(Entities.Info a, List<String> aCols, Entities.Info b, List<String> bCols) {
        if (aCols.size() != bCols.size() || bCols.isEmpty()) {
            return a.key + "|" + upperAll(aCols) + "|" + b.key + "|?";
        }
        List<String> fwd = new ArrayList<>();
        List<String> rev = new ArrayList<>();
        for (int i = 0; i < aCols.size(); i++) {
            fwd.add(upper(aCols.get(i)) + "=" + upper(bCols.get(i)));
            rev.add(upper(bCols.get(i)) + "=" + upper(aCols.get(i)));
        }
        fwd.sort(null);
        rev.sort(null);
        String f = a.key + "|" + b.key + "|" + fwd;
        String r = b.key + "|" + a.key + "|" + rev;
        return f.compareTo(r) <= 0 ? f : r;
    }

    private static boolean unique(TableMeta m, List<String> cols) {
        return m != null && !cols.isEmpty() && m.isUnique(cols);
    }

    private static boolean isPk(TableMeta m, List<String> cols) {
        return m != null && !m.primaryKey().isEmpty()
                && upperAll(cols).containsAll(upperAll(m.primaryKey()));
    }

    /** Metadata that says which keys exist: any key or index, or a catalog read (which always reads keys). */
    private static boolean keysKnown(TableMeta m) {
        if (m == null || m.columns().isEmpty()) {
            return false;
        }
        return !m.primaryKey().isEmpty() || !m.uniqueKeys().isEmpty() || !m.indexes().isEmpty()
                || !SCRIPT_DDL.equals(m.source());
    }

    private static String display(Entities.Info e, List<String> cols) {
        return e.display + (cols.size() == 1 ? "." + cols.get(0) : "(" + String.join(", ", cols) + ")");
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) {
            if (x.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    private static List<String> upperAll(List<String> l) {
        return l.stream().map(ErModelBuilder::upper).toList();
    }

    /** A note text in the language of the request. */
    private static String t(String key, Object... args) {
        return Messages.t(key, args);
    }

}
