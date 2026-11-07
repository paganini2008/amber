package com.github.amber.core.meta;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL becomes metadata, metadata packs round-trip, and metadata sharpens lineage and findings.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class MetadataTest {

    private static final String DDL = """
            create table shop.customer (id number(18) primary key, email varchar2(200) not null, tier varchar2(10));
            create table shop.purchase (
              id number(18), customer_id number(18) not null, total number(12,2), bought_on date,
              constraint pk_purchase primary key (id));
            create index ix_purchase_bought on shop.purchase (bought_on);
            """;

    private static Catalog importDdl(Dialect d, String ddl) {
        DialectFrontend f = DialectFrontends.get(d);
        Catalog catalog = new Catalog();
        ScriptIrBuilder.build(f, f.parse(SourceText.of(ddl)), catalog);
        return catalog;
    }

    @Test
    void ddlBecomesTableMetadata() {
        Catalog c = importDdl(Dialect.ORACLE, DDL);
        TableMeta customer = c.meta("SHOP.CUSTOMER", "CUSTOMER").orElseThrow();
        assertEquals(List.of("id", "email", "tier"), customer.columnNames());
        assertEquals("number(18)", customer.columns().getFirst().type());
        assertEquals(List.of("id"), customer.primaryKey());
        TableMeta purchase = c.meta("SHOP.PURCHASE", "PURCHASE").orElseThrow();
        assertEquals(List.of("id"), purchase.primaryKey());
        assertTrue(purchase.isIndexedLeading("bought_on"));
        assertTrue(purchase.isUnique(List.of("ID")));
        assertTrue(!purchase.isUnique(List.of("customer_id")));
    }

    @Test
    void packsRoundTrip(@TempDir Path dir) throws Exception {
        Catalog c = importDdl(Dialect.ORACLE, DDL);
        Path file = dir.resolve("shop.amberpack.json");
        MetadataPack.of("oracle", "unit test DDL", c.tables()).write(file);
        MetadataPack read = MetadataPack.read(file);
        assertEquals(2, read.tables().size());
        assertEquals("oracle", read.dialect());
        Catalog fromPack = Catalog.of(read.tables());
        assertEquals(List.of("id", "email", "tier"), fromPack.columns("SHOP.CUSTOMER", "CUSTOMER").orElseThrow());
    }

    @Test
    void metadataResolvesColumnsAndSharpensFindings() {
        Catalog base = importDdl(Dialect.ORACLE, DDL);
        String sql = """
                insert into report.customer_sales (email, total)
                select email, sum(total) from shop.customer c join shop.purchase p on p.customer_id = c.id
                 where trunc(bought_on) = date '2026-01-01'
                 group by email;
                select c.email, p.total from shop.purchase p join shop.customer c on c.tier = p.total;
                """;
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        Script script = ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)), base.copy());
        List<String> edges = script.statements().getFirst().lineage().edges().stream().filter(ColumnEdge::isDirect)
                .map(e -> e.from() + " -> " + e.to()).toList();
        assertTrue(edges.contains("shop.customer.email -> report.customer_sales.email"), edges::toString);
        assertTrue(edges.contains("shop.purchase.total -> report.customer_sales.total"), edges::toString);

        List<Finding> findings = Findings.of(script, base);
        Finding s008 = findings.stream().filter(x -> x.rule().equals("AMB-S008")).findFirst().orElseThrow();
        assertEquals(Finding.Severity.HIGH, s008.severity());
        assertTrue(s008.message().contains("indexed column"), s008::message);
        List<Finding> fanOut = findings.stream().filter(x -> x.rule().equals("AMB-M001")).toList();
        assertEquals(1, fanOut.size(), findings::toString);
        assertTrue(fanOut.getFirst().message().contains("shop.customer (tier)"), fanOut.getFirst()::message);
        assertEquals(5, fanOut.getFirst().range().start().line(), "only the many-to-many join; the master-detail join is fine");
    }
}
