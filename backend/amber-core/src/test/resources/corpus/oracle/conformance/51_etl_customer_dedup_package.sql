--------------------------------------------------------------------------------
-- Customer de-duplication package + driver
--------------------------------------------------------------------------------
CREATE GLOBAL TEMPORARY TABLE gtt_cust_candidates (
  master_id    NUMBER,
  duplicate_id NUMBER,
  score        NUMBER(5,2)
) ON COMMIT PRESERVE ROWS;

CREATE OR REPLACE PACKAGE cust_dedup AS
  PROCEDURE find_candidates (p_min_score IN NUMBER DEFAULT 0.9);
  PROCEDURE merge_duplicates (p_dry_run IN BOOLEAN DEFAULT TRUE, p_merged OUT PLS_INTEGER);
  FUNCTION normalize_name (p_name IN VARCHAR2) RETURN VARCHAR2 DETERMINISTIC;
END cust_dedup;
/

CREATE OR REPLACE PACKAGE BODY cust_dedup AS

  TYPE t_pair IS RECORD (master_id NUMBER, duplicate_id NUMBER);
  TYPE t_pairs IS TABLE OF t_pair;

  FUNCTION normalize_name (p_name IN VARCHAR2) RETURN VARCHAR2 DETERMINISTIC IS
  BEGIN
    RETURN REGEXP_REPLACE(UPPER(TRANSLATE(p_name, 'ÄÖÜäöü', 'AOUaou')), '[^A-Z0-9]', '');
  END normalize_name;

  PROCEDURE find_candidates (p_min_score IN NUMBER DEFAULT 0.9) IS
  BEGIN
    DELETE FROM gtt_cust_candidates;
    INSERT INTO gtt_cust_candidates (master_id, duplicate_id, score)
    SELECT master_id, duplicate_id, score
    FROM (
      SELECT a.customer_id AS master_id,
             b.customer_id AS duplicate_id,
             UTL_MATCH.JARO_WINKLER_SIMILARITY(normalize_name(a.name), normalize_name(b.name)) / 100 AS score,
             ROW_NUMBER() OVER (PARTITION BY b.customer_id ORDER BY a.created_at) AS rn
      FROM   customers a
      JOIN   customers b
             ON  a.postal_code = b.postal_code
             AND a.customer_id < b.customer_id
    )
    WHERE score >= p_min_score
    AND   rn = 1;
  END find_candidates;

  PROCEDURE merge_duplicates (p_dry_run IN BOOLEAN DEFAULT TRUE, p_merged OUT PLS_INTEGER) IS
    v_pairs t_pairs;
    CURSOR c_pairs IS
      SELECT master_id, duplicate_id FROM gtt_cust_candidates ORDER BY score DESC;
    e_dml_errors EXCEPTION;
    PRAGMA EXCEPTION_INIT(e_dml_errors, -24381);
  BEGIN
    p_merged := 0;
    OPEN c_pairs;
    LOOP
      FETCH c_pairs BULK COLLECT INTO v_pairs LIMIT 500;
      EXIT WHEN v_pairs.COUNT = 0;
      IF NOT p_dry_run THEN
        BEGIN
          FORALL i IN 1 .. v_pairs.COUNT SAVE EXCEPTIONS
            UPDATE orders SET customer_id = v_pairs(i).master_id WHERE customer_id = v_pairs(i).duplicate_id;
        EXCEPTION
          WHEN e_dml_errors THEN
            FOR j IN 1 .. SQL%BULK_EXCEPTIONS.COUNT LOOP
              DBMS_OUTPUT.PUT_LINE('error at ' || SQL%BULK_EXCEPTIONS(j).ERROR_INDEX || ': ' || SQLERRM(-SQL%BULK_EXCEPTIONS(j).ERROR_CODE));
            END LOOP;
        END;
        FORALL i IN 1 .. v_pairs.COUNT
          DELETE FROM customers WHERE customer_id = v_pairs(i).duplicate_id;
      END IF;
      p_merged := p_merged + v_pairs.COUNT;
    END LOOP;
    CLOSE c_pairs;
    IF NOT p_dry_run THEN
      COMMIT;
    END IF;
  EXCEPTION
    WHEN OTHERS THEN
      IF c_pairs%ISOPEN THEN
        CLOSE c_pairs;
      END IF;
      ROLLBACK;
      RAISE;
  END merge_duplicates;

END cust_dedup;
/
SHOW ERRORS PACKAGE BODY cust_dedup

VARIABLE merged NUMBER
DECLARE
  v_merged PLS_INTEGER;
BEGIN
  cust_dedup.find_candidates(p_min_score => 0.92);
  cust_dedup.merge_duplicates(p_dry_run => FALSE, p_merged => v_merged);
  :merged := v_merged;
END;
/
PRINT merged

SELECT c.customer_id, c.name, COUNT(o.order_id) AS orders
FROM   customers c
LEFT   JOIN orders o ON o.customer_id = c.customer_id
WHERE  c.customer_id IN (SELECT master_id FROM gtt_cust_candidates)
GROUP  BY c.customer_id, c.name
ORDER  BY orders DESC;

TRUNCATE TABLE gtt_cust_candidates;
DROP TABLE gtt_cust_candidates;
