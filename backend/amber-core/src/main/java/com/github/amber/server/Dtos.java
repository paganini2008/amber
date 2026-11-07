package com.github.amber.server;

import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.source.TextPosition;
import com.github.amber.core.source.TextRange;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON shapes of the engine protocol (see docs/protocol.md) and their mapping from the IR.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Dtos {

    private Dtos() {
    }

    public record Position(int offset, int line, int column) {
        static Position of(TextPosition p) {
            return new Position(p.offset(), p.line(), p.column());
        }
    }

    public record Range(Position start, Position end) {
        static Range of(TextRange r) {
            return r == null ? null : new Range(Position.of(r.start()), Position.of(r.end()));
        }
    }

    public record DiagnosticDto(String severity, String code, String message, Range range, String offending,
                                List<String> expected) {
        static DiagnosticDto of(Diagnostic d) {
            return new DiagnosticDto(d.severity().name(), d.code(), d.message(), Range.of(d.range()), d.offending(),
                    d.expected());
        }
    }

    /** {@code via}: the script's own procedure or function through which the statement touches the object. */
    public record RefDto(String name, String key, String kind, String access, String confidence, Range range,
                         String via) {
        static RefDto of(ObjectRef r) {
            return new RefDto(r.name().toString(), r.name().key(), r.kind().name(), r.access().name(),
                    r.confidence().name(), Range.of(r.range()), r.via() == null ? null : r.via().toString());
        }
    }

    public record StatementDto(int id, String kind, String category, boolean container, String confidence,
                               String name, String summary, Range range, List<String> ctes, List<RefDto> refs,
                               List<StatementDto> children, List<QueryBlockDto> structure,
                               List<StepDto> executionOrder) {
    }

    /** One step of a statement's logical processing order. */
    public record StepDto(int step, int block, int depth, String phase, String detail, Range range) {
    }

    /** A logical phase of a query block with the range of its clause. */
    public record PhaseDto(String name, int rank, Range range) {
    }

    /** One box of a statement's nested query structure. */
    public record QueryBlockDto(int id, String kind, String name, String role, Range range,
                                List<com.github.amber.core.ir.QueryBlock.Source> sources, List<String> outputs,
                                List<String> clauses, List<PhaseDto> phases, List<QueryBlockDto> children) {
        static QueryBlockDto of(com.github.amber.core.ir.QueryBlock b) {
            return new QueryBlockDto(b.id(), b.kind().name(), b.name(), b.role(), Range.of(b.range()), b.sources(),
                    b.outputs(), b.clauses(),
                    b.phases().stream().map(p -> new PhaseDto(p.name(), p.rank(), Range.of(p.range()))).toList(),
                    b.children().stream().map(QueryBlockDto::of).toList());
        }
    }

    public record EventDto(int step, String access, Range range, String via) {
    }

    public record LifecycleDto(String name, String key, String kind, String role, boolean createdInScript,
                               boolean droppedInScript, List<EventDto> events) {
        static LifecycleDto of(ObjectLifecycle l) {
            return new LifecycleDto(l.name().toString(), l.name().key(), l.kind().name(), l.role().name(),
                    l.createdInScript(), l.droppedInScript(),
                    l.events().stream().map(e -> new EventDto(e.step(), e.access().name(), Range.of(e.range()),
                            e.via() == null ? null : e.via().toString())).toList());
        }
    }

    public record ColumnNodeDto(int id, String ownerKind, String owner, String ownerKey, String column) {
    }

    public record ColumnEdgeDto(int from, int to, String kind, String transform, String role, String confidence,
                                int statement, Range range) {
    }

    /** The script's column lineage graph; node ids index {@code nodes}, {@code statement} is a step id. */
    public record LineageDto(List<ColumnNodeDto> nodes, List<ColumnEdgeDto> edges) {
    }

    static LineageDto lineage(com.github.amber.core.lineage.LineageGraph graph, List<Statement> steps) {
        Map<Statement, Integer> ids = new IdentityHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            ids.put(steps.get(i), i);
        }
        Map<String, Integer> nodeIds = new java.util.LinkedHashMap<>();
        List<ColumnNodeDto> nodes = new ArrayList<>();
        for (com.github.amber.core.lineage.ColumnNode n : graph.nodes()) {
            nodeIds.put(n.key(), nodes.size());
            nodes.add(new ColumnNodeDto(nodes.size(), n.ownerKind().name(), n.owner(), n.ownerKey(), n.column()));
        }
        List<ColumnEdgeDto> edges = new ArrayList<>();
        for (var e : graph.edges()) {
            var ce = e.edge();
            edges.add(new ColumnEdgeDto(nodeIds.get(ce.from().key()), nodeIds.get(ce.to().key()),
                    ce.isDirect() ? "DIRECT" : "INDIRECT", ce.transform() == null ? null : ce.transform().name(),
                    ce.role() == null ? null : ce.role().name(), ce.confidence().name(),
                    ids.getOrDefault(e.statement(), -1), Range.of(ce.range())));
        }
        return new LineageDto(nodes, edges);
    }

    public record FindingDto(String rule, String severity, String title, String message, String suggestion,
                             int statement, Range range, String confidence) {
    }

    static List<FindingDto> findings(List<com.github.amber.dialect.findings.Finding> findings, List<Statement> steps) {
        Map<Statement, Integer> ids = new IdentityHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            ids.put(steps.get(i), i);
        }
        return findings.stream().map(f -> new FindingDto(f.rule(), f.severity().name(), f.title(), f.message(),
                f.suggestion(), ids.getOrDefault(f.statement(), -1), Range.of(f.range()), f.confidence().name())).toList();
    }

    /** Maps statements to DTOs; ids are step indexes (source order, depth first). */
    static List<StatementDto> statements(List<Statement> top, List<Statement> steps) {
        Map<Statement, Integer> ids = new IdentityHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            ids.put(steps.get(i), i);
        }
        List<StatementDto> out = new ArrayList<>(top.size());
        for (Statement st : top) {
            out.add(statement(st, ids));
        }
        return out;
    }

    private static StatementDto statement(Statement st, Map<Statement, Integer> ids) {
        List<StatementDto> children = new ArrayList<>(st.children().size());
        for (Statement child : st.children()) {
            children.add(statement(child, ids));
        }
        return new StatementDto(ids.get(st), st.kind().name(), st.kind().category().name(), st.kind().isContainer(),
                st.confidence().name(), st.name() == null ? null : st.name().toString(), st.summary(),
                Range.of(st.range()), st.ctes(), st.refs().stream().map(RefDto::of).toList(), children,
                st.structure().stream().map(QueryBlockDto::of).toList(),
                st.executionOrder().stream().map(x -> new StepDto(x.step(), x.block(), x.depth(), x.phase(), x.detail(),
                        Range.of(x.range()))).toList());
    }
}
