package com.github.amber.dialect.lineage;

import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.StatementLineage;
import com.github.amber.core.lineage.Transform;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Text helpers and lookups of {@link VariableScopes}.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class VariableScopesTest {

    @Test
    void parameterAndDeclarationNames() {
        assertEquals("p_day", VariableScopes.parameterName("p_day in date"));
        assertEquals("p_out", VariableScopes.parameterName("IN OUT p_out number"));
        assertEquals("@from", VariableScopes.parameterName("@from date = null"));
        assertNull(VariableScopes.parameterName("  "));
        assertEquals(List.of("a", "b"), VariableScopes.declaredNames("DECLARE a, b INT DEFAULT 0"));
        assertEquals(List.of("v_x"), VariableScopes.declaredNames("v_x CONSTANT number := 1;"));
        assertEquals(List.of(), VariableScopes.declaredNames("declare c cursor for select 1"));
        assertEquals(List.of(), VariableScopes.declaredNames("DECLARE @t AS TABLE (id int)"));
        assertEquals(List.of(), VariableScopes.declaredNames("declare continue handler for not found set done = 1"));
        assertEquals(List.of(), VariableScopes.declaredNames("("));
    }

    @Test
    void intoTargetsAndCursors() {
        assertEquals(List.of("v_a", "r.f"), VariableScopes.intoNames("into v_a, r . f"));
        assertEquals(List.of("v_a"), VariableScopes.intoNames("INTO STRICT v_a"));
        assertEquals(Arrays.asList("v_a", null), VariableScopes.intoNames("bulk collect into v_a, :host"));
        assertEquals(List.of("arr"), VariableScopes.intoNames("fetch c bulk collect into arr limit 100"));
        assertEquals(List.of(), VariableScopes.intoNames("into outfile '/tmp/x'"));
        assertEquals(List.of(), VariableScopes.intoNames("select f(into_x) from t"));
        assertEquals(List.of("v"), VariableScopes.intoNames("select (1) into v"));
        assertEquals("c", VariableScopes.fetchCursor("FETCH NEXT FROM c INTO @a"));
        assertNull(VariableScopes.fetchCursor("FETCH c"));
        assertNull(VariableScopes.fetchCursor("FETCH INTO v"));
        assertEquals("c_ord", VariableScopes.cursorName("cursor c_ord is select 1 from dual"));
        assertEquals("c", VariableScopes.cursorName("DECLARE c CURSOR FOR select 1"));
        assertEquals("rc", VariableScopes.cursorName("open rc for select 1"));
        assertNull(VariableScopes.cursorName("("));
    }

    @Test
    void assignmentTargets() {
        VariableScopes.Scope s = new VariableScopes.Scope(null, "P@0", "p", true);
        s.addDeclared("r");
        assertEquals(List.of("r"), VariableScopes.targetNames("r.amount", s));
        assertEquals(List.of("pkg.g_var"), VariableScopes.targetNames("pkg . g_var", s));
        assertEquals(List.of("t"), VariableScopes.targetNames("t(i)", s));
        assertEquals(List.of("a", "b"), VariableScopes.targetNames("(a, b)", s));
        assertEquals(List.of("x"), VariableScopes.targetNames("x", null));
    }

    @Test
    void lookupsAcrossScopes() {
        VariableScopes.Scope outer = new VariableScopes.Scope(null, "P@0", "dw.p", true);
        outer.addParameter("p_a");
        outer.addParameter("\"P_B\"");
        VariableScopes.Scope inner = new VariableScopes.Scope(outer, "BLOCK@9", "block@L2", false);
        inner.addDeclared("v");
        inner.addTarget("w");
        VariableScopes fixed = VariableScopes.fixed(inner);
        assertEquals(VariableRules.NONE, fixed.rules());
        fixed.register(null, null, null);                         // ignored for a fixed scope
        assertEquals(inner, fixed.scopeOf(null));
        assertEquals(ColumnNode.OwnerKind.PARAMETER, fixed.lookup(inner, "P_A").ownerKind());
        assertEquals("var:P@0:P_A", fixed.lookup(inner, "p_a").ownerKey());
        assertEquals("var:BLOCK@9:W", fixed.lookup(inner, "w").ownerKey());
        assertEquals("block@L2", fixed.lookup(inner, "v").owner());
        assertNull(fixed.lookup(inner, "nope"));
        assertNull(fixed.lookup(inner, "@x"));                   // no @ variables in this dialect
        assertNull(fixed.lookup(inner, "@@rowcount"));
        assertNull(fixed.lookup(inner, " "));
        assertEquals("P_B", fixed.positional(inner, 2).column());
        assertNull(fixed.positional(inner, 3));
        assertNull(fixed.positional(new VariableScopes.Scope(null, "X", "x", false), 1));
        assertNull(fixed.cursor(inner, "c"));
        outer.addCursor("c", new VariableScopes.Cursor("result@1", List.of("a")));
        assertEquals("result@1", fixed.cursor(inner, "C").ownerKey());
        assertEquals("P@0", outer.key());
        assertEquals("dw.p", outer.display());
    }

    @Test
    void assignRedirectsQueryResultIntoVariables() {
        SourceText src = SourceText.of("select a, b from t where c = 1");
        ColumnNode a = new ColumnNode(ColumnNode.OwnerKind.TABLE, "t", "T", "a");
        ColumnNode c = new ColumnNode(ColumnNode.OwnerKind.TABLE, "t", "T", "c");
        ColumnNode outA = new ColumnNode(ColumnNode.OwnerKind.RESULT, "result", "result@0", "a");
        ColumnNode outB = new ColumnNode(ColumnNode.OwnerKind.RESULT, "result", "result@0", "b");
        ColumnNode rows = new ColumnNode(ColumnNode.OwnerKind.RESULT, "result", "result@0", ColumnNode.ROWS);
        ColumnNode other = new ColumnNode(ColumnNode.OwnerKind.CTE, "x", "cte:X", "a");
        StatementLineage l = new StatementLineage(List.of(outA, outB), List.of(
                ColumnEdge.direct(a, outA, Transform.PASS_THROUGH, Confidence.INFERRED, src.range(7, 8)),
                ColumnEdge.direct(a, other, Transform.PASS_THROUGH, Confidence.INFERRED, null),
                ColumnEdge.indirect(c, rows, com.github.amber.core.lineage.IndirectRole.FILTER, Confidence.INFERRED,
                        src.range(25, 30))));
        VariableScopes.Scope s = new VariableScopes.Scope(null, "P@0", "p", true);
        ColumnNode v = s.node("v");
        StatementLineage one = VariableScopes.assign(l, "result@0", List.of(v), src);
        assertEquals(List.of(v), one.outputs());
        assertEquals(3, one.edges().size());
        assertEquals(v, one.edges().get(0).to());
        assertEquals(ColumnNode.ROWS, one.edges().get(2).to().column());
        StatementLineage two = VariableScopes.assign(l, "result@0", Arrays.asList(v, null), src);
        assertEquals(List.of(v), two.outputs());
    }
}
