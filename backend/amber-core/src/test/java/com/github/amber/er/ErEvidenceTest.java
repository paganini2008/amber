package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import com.github.amber.er.ErModel.Cardinality;
import com.github.amber.er.ErModel.Certainty;
import com.github.amber.er.ErModel.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.er.ErTestSupport.between;
import static com.github.amber.er.ErTestSupport.model;
import static com.github.amber.er.ErTestSupport.one;
import static com.github.amber.er.ErTestSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joins without aliases whose columns only the script's own column evidence ties to tables: the relationship
 * appears with at most INFERRED confidence and a note naming the evidence; ambiguous evidence and metadata
 * keep today's result.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ErEvidenceTest {

    private static final String JOIN = "select order_id, name from orders join customers on cust_id = id;\n";
    private static final String EVIDENCE = """
            insert into orders (order_id, cust_id) values (1, 10);
            insert into customers (id, name) values (10, 'A');
            """;

    @Test
    void withoutEvidenceTheJoinIsUnresolved() {
        ErModel m = model(Dialect.ORACLE, JOIN);
        assertTrue(between(m, "ORDERS", "CUSTOMERS").isEmpty(), m.relationships()::toString);
        assertEquals(1, m.stats().unresolved());
    }

    @Test
    void insertColumnListsPlaceTheJoinInEveryDialect() {
        for (Dialect d : List.of(Dialect.ORACLE, Dialect.SQLSERVER, Dialect.POSTGRESQL, Dialect.MYSQL)) {
            ErModel m = model(d, EVIDENCE + JOIN);
            ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
            assertEquals(Evidence.JOIN, r.evidence(), d::toString);
            assertEquals(Certainty.POSSIBLE, r.confidence(), () -> d + " " + ErTestSupport.str(r));
            assertEquals(Cardinality.MANY_TO_ONE, r.cardinality(), d::toString);
            assertEquals("ORDERS", r.from().entity(), d::toString);
            assertEquals(List.of("cust_id"), r.from().columns(), d::toString);
            assertEquals(List.of("id"), r.to().columns(), d::toString);
            assertTrue(r.note().contains("placed by the script's own evidence: orders.cust_id (INSERT column list, line 1)"),
                    () -> d + " " + r.note());
            assertTrue(r.note().contains("customers.id (INSERT column list, line 2)"), r::note);
            assertEquals(0, m.stats().unresolved(), d::toString);
            assertEquals(1, m.stats().joinEqualities(), d::toString);
        }
    }

    @Test
    void keyMetadataMakesItInferred() {
        ErModel m = model(Dialect.ORACLE, """
                insert into orders (order_id, cust_id) values (1, 10);
                select order_id from orders join customers on cust_id = id join regions on regions.code = customers.region;
                """, table("customers", List.of("id"), "id", "name", "region"));
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals(Cardinality.MANY_TO_ONE, r.cardinality());
        assertEquals(Certainty.INFERRED, r.confidence(), () -> ErTestSupport.str(r));
        assertEquals("ORDERS", r.from().entity());
        assertTrue(r.note().contains("orders.cust_id (INSERT column list, line 1)"), r::note);
        assertTrue(!r.note().contains("customers.id ("), "customers.id is known from the metadata: " + r.note());
    }

    @Test
    void someJoinsByEvidence() {
        ErModel m = model(Dialect.SQLSERVER, """
                SELECT o.order_id FROM orders o JOIN customers c ON o.cust_id = c.id;
                SELECT order_id FROM orders, customers WHERE cust_id = id;
                """);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals(2, r.count());
        assertTrue(r.note().contains("In 1 of the joins"), r::note);
        assertTrue(r.note().contains("orders.cust_id (qualified reference, line 1)"), r::note);
    }

    @Test
    void ambiguousEvidenceStaysUnresolved() {
        ErModel m = model(Dialect.POSTGRESQL, """
                insert into orders (id, cust_id) values (1, 10);
                insert into customers (id, cust_id) values (10, 1);
                select 1 from orders join customers on cust_id = id;
                """);
        assertTrue(between(m, "ORDERS", "CUSTOMERS").isEmpty(), m.relationships()::toString);
        assertEquals(1, m.stats().unresolved());
    }

    @Test
    void metadataWinsOverEvidence() {
        // evidence says orders has cust_id, the metadata of customers says customers has it: no join of the two
        // columns of one table, so no relationship
        ErModel m = model(Dialect.MYSQL, EVIDENCE + JOIN,
                table("customers", List.of("id"), "id", "name", "cust_id"));
        assertTrue(between(m, "ORDERS", "CUSTOMERS").isEmpty(), m.relationships()::toString);
    }

    @Test
    void scriptBuiltWithAnotherCatalogStillUsesEvidence() {
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        Script s = ScriptIrBuilder.build(f, f.parse(SourceText.of(EVIDENCE + JOIN)), new Catalog());
        ErModel m = ErModelBuilder.build(s, new Catalog(), ErModelBuilder.Options.DEFAULTS);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertNotEquals(Certainty.CERTAIN, r.confidence());
    }

    @Test
    void notesAreLocalized() throws Exception {
        String zh = com.github.amber.core.i18n.Messages.call(com.github.amber.core.i18n.Messages.of("zh-CN"),
                () -> one(model(Dialect.ORACLE, EVIDENCE + JOIN), "ORDERS", "CUSTOMERS").note());
        assertTrue(zh.contains("INSERT 列清单"), zh);
    }
}
