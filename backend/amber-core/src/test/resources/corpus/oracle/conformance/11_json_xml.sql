-- Oracle conformance: JSON and XML
SELECT JSON_VALUE(doc, '$.customer.name') FROM json_docs;
SELECT JSON_VALUE(doc, '$.total' RETURNING NUMBER DEFAULT 0 ON ERROR) FROM json_docs;
SELECT JSON_QUERY(doc, '$.items[*]' WITH WRAPPER) FROM json_docs;
SELECT JSON_QUERY(doc, '$.items' RETURNING VARCHAR2(4000) PRETTY) FROM json_docs;
SELECT * FROM json_docs WHERE JSON_EXISTS(doc, '$.items?(@.qty > 10)');
SELECT * FROM json_docs WHERE doc IS JSON;
SELECT * FROM json_docs WHERE doc IS NOT JSON STRICT;
SELECT JSON_OBJECT('id' VALUE order_id, 'amount' VALUE amount) FROM orders;
SELECT JSON_OBJECT(KEY 'id' VALUE order_id, 'note' VALUE note ABSENT ON NULL RETURNING CLOB) FROM orders;
SELECT JSON_OBJECT(*) FROM orders;
SELECT JSON_ARRAY(1, 'two', NULL NULL ON NULL) FROM dual;
SELECT JSON_ARRAYAGG(order_id ORDER BY order_id RETURNING CLOB) FROM orders;
SELECT JSON_OBJECTAGG(KEY product_code VALUE price) FROM products;
SELECT jt.* FROM json_docs d,
  JSON_TABLE(d.doc, '$.items[*]'
    COLUMNS (
      line_no FOR ORDINALITY,
      sku     VARCHAR2(20) PATH '$.sku',
      qty     NUMBER       PATH '$.qty' DEFAULT 1 ON EMPTY,
      has_tax VARCHAR2(5)  EXISTS PATH '$.tax',
      NESTED PATH '$.discounts[*]' COLUMNS (disc_code VARCHAR2(10) PATH '$.code')
    )) jt;
SELECT d.doc.customer.name FROM json_docs d;
SELECT d.doc.items[0].sku FROM json_docs d;
SELECT JSON_SERIALIZE(doc PRETTY) FROM json_docs;
SELECT JSON_MERGEPATCH(doc, '{"status":"CLOSED"}') FROM json_docs;
UPDATE json_docs SET doc = JSON_TRANSFORM(doc, SET '$.status' = 'DONE', REMOVE '$.tmp') WHERE id = 1;
CREATE TABLE json_store (id NUMBER PRIMARY KEY, doc CLOB CONSTRAINT json_store_chk CHECK (doc IS JSON));
CREATE SEARCH INDEX json_store_sx ON json_store (doc) FOR JSON;
SELECT XMLELEMENT("order", XMLATTRIBUTES(order_id AS "id"), XMLFOREST(amount AS "amount", status AS "status")) FROM orders;
SELECT XMLAGG(XMLELEMENT("id", order_id) ORDER BY order_id) FROM orders;
SELECT XMLSERIALIZE(CONTENT XMLTYPE('<a>1</a>') AS CLOB INDENT) FROM dual;
SELECT x.* FROM xml_docs d, XMLTABLE('/orders/order' PASSING d.doc COLUMNS order_id NUMBER PATH '@id', amount NUMBER PATH 'amount') x;
SELECT XMLQUERY('/orders/order[1]/amount/text()' PASSING doc RETURNING CONTENT) FROM xml_docs;
SELECT * FROM xml_docs WHERE XMLEXISTS('/orders/order[@id=1]' PASSING doc);
SELECT EXTRACTVALUE(doc, '/orders/order[1]/@id') FROM xml_docs;
SELECT d.doc.getClobVal() FROM xml_docs d;
SELECT XMLCAST(XMLQUERY('/a/b' PASSING doc RETURNING CONTENT) AS VARCHAR2(100)) FROM xml_docs;
