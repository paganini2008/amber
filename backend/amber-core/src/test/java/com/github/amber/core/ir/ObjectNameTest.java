package com.github.amber.core.ir;

import com.github.amber.core.Dialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class ObjectNameTest {

    @Test
    void foldsUnquotedNamesTheWayEachDatabaseDoes() {
        assertEquals("SALES.ORDERS", ObjectName.parse(Dialect.ORACLE, "sales.Orders").key());
        assertEquals("SALES.ORDERS", ObjectName.parse(Dialect.ORACLE, "sales.\"Orders\"").key());
        assertEquals("SALES.ORDERS", ObjectName.parse(Dialect.POSTGRESQL, "Sales.ORDERS").key());
        assertEquals("SALES.ORDERS", ObjectName.parse(Dialect.POSTGRESQL, "sales.\"Orders\"").key());
        assertEquals("DBO.ORDERS", ObjectName.parse(Dialect.SQLSERVER, "[dbo].[Orders]").key());
        assertEquals("SHOP.ORDERS", ObjectName.parse(Dialect.MYSQL, "`shop`.`orders`").key());
    }

    @Test
    void stopsAtTheEndOfTheName() {
        ObjectName name = ObjectName.parse(Dialect.ORACLE, "hr.employees@remote_db e");
        assertEquals("HR.EMPLOYEES@REMOTE_DB", name.key());
        assertEquals(2, ObjectName.parse(Dialect.ORACLE, "a . b (x)").parts().size());
        assertEquals("employees", ObjectName.parse(Dialect.ORACLE, "hr.employees").simpleName());
        assertEquals("a\"b", ObjectName.parse(Dialect.ORACLE, "\"a\"\"b\"").simpleName());
        assertNull(ObjectName.parse(Dialect.ORACLE, "(select 1 from dual)"));
        assertNull(ObjectName.parse(Dialect.ORACLE, "   "));
    }

    @Test
    void sameObjectAcrossQualification() {
        ObjectName qualified = ObjectName.parse(Dialect.ORACLE, "dw.orders");
        assertTrue(qualified.sameObject(ObjectName.parse(Dialect.ORACLE, "ORDERS")));
        assertFalse(qualified.sameObject(ObjectName.parse(Dialect.ORACLE, "stage.orders")));
        assertEquals(qualified, ObjectName.parse(Dialect.ORACLE, "DW.Orders"));
        assertEquals(qualified.hashCode(), ObjectName.parse(Dialect.ORACLE, "DW.Orders").hashCode());
        assertEquals("dw.orders", qualified.toString());
        assertEquals("ORDERS", qualified.simpleKey());
    }
}
