package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import com.github.amber.core.i18n.Messages;
import com.github.amber.core.lineage.Catalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rewrites in another language: the same rewrites, edits, SQL and verdicts as in English, with the prose
 * (title, explanation, safety note, structural differences) translated. The decision to keep a rewrite is
 * made on the English check, so translated differences never drop one.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class RewriteLocaleTest {

    private static final String SQL = """
            SELECT DISTINCT o.status, count(*) cnt FROM orders o GROUP BY status;
            select id from orders where id not in (select order_id from returns);
            select a from t union select a from u;
            select x.id from (select id from orders order by id) x;
            """;

    private static <T> T in(String tag, Callable<T> body) throws Exception {
        return Messages.call(Messages.of(tag), body);
    }

    @Test
    void sameRewritesWithTranslatedProse() throws Exception {
        List<Rewrite> en = RewriteSuggester.suggest(Dialect.ORACLE, SQL, new Catalog(), List.of());
        assertTrue(en.size() >= 4, en::toString);
        for (String tag : List.of("zh-CN", "es")) {
            List<Rewrite> other = in(tag, () -> RewriteSuggester.suggest(Dialect.ORACLE, SQL, new Catalog(), List.of()));
            assertEquals(en.size(), other.size(), tag);
            for (int i = 0; i < en.size(); i++) {
                Rewrite a = en.get(i);
                Rewrite b = other.get(i);
                assertEquals(a.id(), b.id());
                assertEquals(a.rule(), b.rule());
                assertEquals(a.edits(), b.edits());
                assertEquals(a.rewrittenStatement(), b.rewrittenStatement());
                assertEquals(a.safety(), b.safety());
                assertEquals(a.check(), b.check());
                assertEquals(a.checkDifferences().size(), b.checkDifferences().size());
                assertNotEquals(a.explanation(), b.explanation(), tag + " " + a.rule());
                assertNotEquals(a.safetyNote(), b.safetyNote(), tag + " " + a.rule());
                if (!a.title().startsWith("UNION") && !a.title().startsWith("LEFT")) {
                    assertNotEquals(a.title(), b.title(), tag + " " + a.rule());
                }
            }
        }
        Rewrite distinct = en.stream().filter(r -> r.rule().equals("AMB-S011")).findFirst().orElseThrow();
        assertTrue(distinct.checkDifferences().get(0).startsWith("Ordering and row limits"));
    }

    @Test
    void unknownLocaleIsEnglish() throws Exception {
        List<Rewrite> en = RewriteSuggester.suggest(Dialect.ORACLE, SQL, new Catalog(), List.of());
        List<Rewrite> xx = in("xx-YY", () -> RewriteSuggester.suggest(Dialect.ORACLE, SQL, new Catalog(), List.of()));
        assertEquals(en, xx);
    }

    @Test
    void notesOfEveryBranchAreTranslated() throws Exception {
        String sql = """
                select id from orders where trunc(created) = date '2024-01-02';
                select id from orders where trunc(created) between date '2024-01-02' and date '2024-01-05';
                select (select max(r.amount) from returns r where r.order_id = o.id) mx from orders o;
                select (select count(*) from returns r where r.order_id = o.id) n from orders o;
                select * from (select a from t where a > 1) x join (select a from t where a > 1) y on x.a = y.a;
                insert into archive select * from orders;
                """;
        List<Rewrite> en = RewriteSuggester.suggest(Dialect.ORACLE, sql, new Catalog(), List.of());
        List<Rewrite> es = in("es", () -> RewriteSuggester.suggest(Dialect.ORACLE, sql, new Catalog(), List.of()));
        assertEquals(en.size(), es.size());
        for (int i = 0; i < en.size(); i++) {
            assertEquals(en.get(i).rewrittenStatement(), es.get(i).rewrittenStatement());
            assertNotEquals(en.get(i).safetyNote(), es.get(i).safetyNote(), en.get(i).rule());
        }
    }

    @Test
    void optimizerDifferencesFollowTheLanguage() throws Exception {
        String sql = "select distinct status, count(*) from orders group by status;";
        Optimizer.Result en = Optimizer.optimize(Dialect.ORACLE, sql, new Catalog(), null, false, r -> true, false, null);
        Optimizer.Result zh = in("zh-CN", () -> Optimizer.optimize(Dialect.ORACLE, sql, new Catalog(), null, false,
                r -> true, false, null));
        assertEquals(en.optimizedText(), zh.optimizedText());
        assertEquals(en.statements().get(0).check().label(), zh.statements().get(0).check().label());
        assertEquals(en.statements().get(0).applied().size(), zh.statements().get(0).applied().size());
        assertNotEquals(en.statements().get(0).applied().get(0).title(), zh.statements().get(0).applied().get(0).title());
    }
}
