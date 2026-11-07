package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;

import java.nio.charset.StandardCharsets;

/**
 * Prints the lineage edges of a test script (development aid, not a test).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class LineageDump {

    private LineageDump() {
    }

    public static void main(String[] args) throws Exception {
        DialectFrontend f = DialectFrontends.get(Dialect.fromId(args[0]));
        String sql = new String(LineageDump.class.getResourceAsStream(args[1]).readAllBytes(), StandardCharsets.UTF_8);
        Script s = ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
        s.diagnostics().forEach(d -> System.out.println("DIAG " + d.severity() + " " + d.message()));
        for (Statement st : s.flatten()) {
            if (st.lineage() != null) {
                System.out.println("== " + st.kind() + " L" + st.range().start().line() + " outputs=" + st.lineage().outputs());
                for (ColumnEdge e : st.lineage().edges()) {
                    System.out.println("   " + e.from().ownerKind() + ":" + e.from() + " -> " + e.to().ownerKind() + ":" + e.to()
                            + "  " + (e.isDirect() ? e.transform() : "~" + e.role()));
                }
            }
        }
    }
}
