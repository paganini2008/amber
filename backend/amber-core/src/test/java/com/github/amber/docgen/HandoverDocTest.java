package com.github.amber.docgen;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handover document covers overview, outline, objects, data flow, target lineage, risks, the most
 * complex statements and unreadable parts, as Markdown and HTML.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class HandoverDocTest {

    private static final String SQL = """
            create table tmp_paid (customer_id int, total numeric);
            insert into tmp_paid (customer_id, total)
            select o.customer_id, sum(o.amount) from sales.orders o where o.status = 'PAID' group by o.customer_id;
            insert into report.vip (customer_id, total, label)
            select p.customer_id, p.total, 'x|y' from tmp_paid p where p.customer_id not in (select id from crm.blocked);
            this is not sql;
            drop table tmp_paid;
            """;

    private static String markdown() {
        DialectFrontend f = DialectFrontends.get(Dialect.POSTGRESQL);
        ScriptParse parse = f.parse(new SourceText("nightly.sql", SQL), ParseOptions.FALLBACK);
        Script script = ScriptIrBuilder.build(f, parse);
        return HandoverDoc.markdown(script, Findings.of(script), parse.diagnostics());
    }

    @Test
    void markdownHasEverySection() {
        String md = markdown();
        assertTrue(md.startsWith("# nightly.sql — handover notes"), md);
        for (String h : new String[] {"## Overview", "## What the script does", "## Objects", "## Data flow",
                "## Where the target columns come from", "## Risks and improvement hints", "## The most complex statements",
                "## Parts Amber could not read"}) {
            assertTrue(md.contains(h), () -> h + "\n" + md);
        }
        assertTrue(md.contains("| `tmp_paid` | temp_table | intermediate | yes | yes |") || md.contains("| `tmp_paid` |"), md);
        assertTrue(md.contains("sales.orders.amount"), "target column traced to its base column");
        assertTrue(md.contains("`AMB-S004`"), md);
        assertTrue(md.contains("flowchart LR") && md.contains("--> "), md);
        assertTrue(md.contains("**FROM**") && md.contains("**WRITE**"), md);
    }

    @Test
    void htmlIsSelfContainedAndEscaped() {
        String html = HandoverDoc.toHtml("nightly.sql — <notes>", markdown());
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("<title>nightly.sql — &lt;notes&gt;</title>"));
        assertTrue(html.contains("<table>") && html.contains("<th>") && html.contains("<ol>") && html.contains("<ul>")
                && html.contains("<pre class=\"code mermaid\">") && html.contains("<strong>") && html.contains("<code>"), html);
        assertFalse(html.contains("<script"), "no scripts: the page works offline");
        assertTrue(html.contains("prefers-color-scheme:dark"));
    }
}
