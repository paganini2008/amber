CREATE PROCEDURE app.p_simple ()
LANGUAGE SQL
BEGIN
    UPDATE app.counter SET n = n + 1;
END;
CREATE OR REPLACE PROCEDURE app.p_params (IN p_id INTEGER, OUT p_name VARCHAR(100), INOUT p_cnt INTEGER, p_plain INT DEFAULT 1)
    SPECIFIC app.p_params_v1
    LANGUAGE SQL
    DYNAMIC RESULT SETS 1
    MODIFIES SQL DATA
    NOT DETERMINISTIC
    CALLED ON NULL INPUT
    COMMIT ON RETURN NO
    INHERIT SPECIAL REGISTERS
    OLD SAVEPOINT LEVEL
P1: BEGIN
    DECLARE v_count INTEGER DEFAULT 0;
    DECLARE v_a, v_b VARCHAR(10);
    DECLARE SQLSTATE CHAR(5) DEFAULT '00000';
    DECLARE SQLCODE INTEGER DEFAULT 0;
    DECLARE v_row ANCHOR ROW OF app.customers;
    DECLARE v_name ANCHOR DATA TYPE TO app.customers.name;
    DECLARE v_ids app.int_list;
    DECLARE c_const INTEGER CONSTANT 10;
    DECLARE not_found CONDITION FOR SQLSTATE '02000';
    DECLARE dup_key CONDITION FOR SQLSTATE VALUE '23505';
    DECLARE c1 CURSOR WITH RETURN TO CALLER FOR SELECT id, name FROM app.customers WHERE region_id = p_id;
    DECLARE c2 CURSOR WITH HOLD FOR SELECT id FROM app.customers FOR UPDATE OF name;
    DECLARE c3 INSENSITIVE CURSOR WITHOUT RETURN FOR stmt1;
    DECLARE c5 ASENSITIVE CURSOR WITHOUT HOLD FOR SELECT 1 FROM sysibm.sysdummy1;
    DECLARE c4 CURSOR WITH HOLD WITH RETURN TO CLIENT FOR SELECT * FROM app.orders WITH UR;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        GET DIAGNOSTICS EXCEPTION 1 p_name = MESSAGE_TEXT;
        ROLLBACK;
        RESIGNAL;
    END;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_count = -1;
    DECLARE CONTINUE HANDLER FOR SQLWARNING, SQLSTATE '01003' BEGIN END;
    DECLARE UNDO HANDLER FOR dup_key SET p_cnt = 0;
    DECLARE EXIT HANDLER FOR SQLSTATE '72822' BEGIN SIGNAL SQLSTATE '75002' SET MESSAGE_TEXT = 'Wrapped'; END;

    SELECT name INTO p_name FROM app.customers WHERE id = p_id;
    SET v_count = v_count + 1, p_cnt = COALESCE(p_cnt, 0) + v_count;
    SET (v_a, v_b) = ('x', 'y');
    SET v_ids[1] = 10;
    SET v_count = (SELECT COUNT(*) FROM app.orders WHERE customer_id = p_id);
    VALUES CURRENT DATE INTO v_a;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    GET CURRENT DIAGNOSTICS v_count = DB2_RETURN_STATUS;
    GET DIAGNOSTICS EXCEPTION 1 v_a = MESSAGE_TEXT, v_b = DB2_TOKEN_STRING;
    GET STACKED DIAGNOSTICS v_count = DB2_RETURN_STATUS;
    IF v_count < 0 THEN
        SIGNAL SQLSTATE '75009' ('Old-style message text');
    END IF;
    OPEN c1;
END P1;
CREATE PROCEDURE app.p_control (IN p_mode CHAR(1))
LANGUAGE SQL
BEGIN NOT ATOMIC
    DECLARE i INTEGER DEFAULT 0;
    DECLARE done INTEGER DEFAULT 0;
    DECLARE v_id INTEGER;
    DECLARE cur CURSOR FOR SELECT id FROM app.work_queue ORDER BY id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    IF p_mode = 'A' THEN
        SET i = 1;
    ELSEIF p_mode = 'B' THEN
        SET i = 2;
    ELSEIF p_mode IS NULL THEN
        SIGNAL SQLSTATE '75001' SET MESSAGE_TEXT = 'Mode is null';
    ELSE
        SET i = 3;
    END IF;

    CASE p_mode
        WHEN 'A' THEN SET i = i * 10;
        WHEN 'B' THEN
            SET i = i * 20;
            SET i = i + 1;
        ELSE SET i = 0;
    END CASE;

    CASE
        WHEN i > 10 THEN CALL app.log_msg('big');
        WHEN i > 0 THEN CALL app.log_msg('small');
    END CASE;

    OPEN cur;
    fetch_loop: LOOP
        FETCH cur INTO v_id;
        IF done = 1 THEN
            LEAVE fetch_loop;
        END IF;
        IF MOD(v_id, 2) = 0 THEN
            ITERATE fetch_loop;
        END IF;
        UPDATE app.work_queue SET processed = 'Y' WHERE CURRENT OF cur;
    END LOOP fetch_loop;
    CLOSE cur;

    SET i = 0;
    w1: WHILE i < 10 DO
        SET i = i + 1;
    END WHILE w1;

    WHILE i > 0 DO SET i = i - 1; END WHILE;

    r1: REPEAT
        SET i = i + 2;
    UNTIL i >= 10
    END REPEAT r1;

    FOR v AS SELECT id, name FROM app.customers WHERE region_id = 1 DO
        CALL app.log_msg(v.name);
    END FOR;

    f2: FOR r AS c_named CURSOR WITH HOLD FOR SELECT order_id, amount FROM app.orders WHERE status = 'N' DO
        UPDATE app.orders SET status = 'P' WHERE order_id = r.order_id;
        COMMIT;
    END FOR f2;

    IF i > 100 THEN
        GOTO done_label;
    END IF;
    done_label: SET i = -1;
    inner_block: BEGIN
        DECLARE x INT DEFAULT 1;
        SET x = x + 1;
    END inner_block;
    RETURN 0;
END;
CREATE PROCEDURE app.p_dynamic (IN p_table VARCHAR(128), OUT p_rows BIGINT)
LANGUAGE SQL
BEGIN
    DECLARE v_sql VARCHAR(4000);
    DECLARE v_cnt BIGINT;
    DECLARE c_dyn CURSOR FOR s_dyn;
    SET v_sql = 'SELECT COUNT(*) FROM ' || p_table || ' WHERE status = ?';
    PREPARE s_dyn FROM v_sql;
    OPEN c_dyn USING 'N';
    FETCH FROM c_dyn INTO v_cnt;
    CLOSE c_dyn;
    EXECUTE IMMEDIATE 'DELETE FROM ' || p_table || ' WHERE status = ''X''';
    EXECUTE IMMEDIATE v_sql;
    SET v_sql = 'UPDATE ' || p_table || ' SET status = ? WHERE id = ?';
    PREPARE s_upd FROM v_sql;
    EXECUTE s_upd USING 'Y', 42;
    PREPARE s_sel FROM 'SELECT MAX(id) FROM app.orders';
    EXECUTE s_sel INTO p_rows;
    SET p_rows = v_cnt;
END;
CREATE PROCEDURE app.p_result_sets ()
    LANGUAGE SQL
    DYNAMIC RESULT SETS 2
    READS SQL DATA
BEGIN
    DECLARE r1 CURSOR WITH RETURN FOR SELECT * FROM app.orders;
    DECLARE r2 CURSOR WITH RETURN TO CLIENT FOR SELECT * FROM app.customers;
    OPEN r1;
    OPEN r2;
END;
CREATE PROCEDURE app.p_caller ()
LANGUAGE SQL
BEGIN
    DECLARE loc1 RESULT_SET_LOCATOR VARYING;
    DECLARE v_id INTEGER;
    CALL app.p_result_sets();
    ASSOCIATE RESULT SET LOCATOR (loc1) WITH PROCEDURE app.p_result_sets;
    ALLOCATE rc1 CURSOR FOR RESULT SET loc1;
    FETCH rc1 INTO v_id;
    CLOSE rc1;
END;
CREATE PROCEDURE app.p_autonomous (IN p_msg VARCHAR(200))
    LANGUAGE SQL AUTONOMOUS
BEGIN
    INSERT INTO app.log (msg) VALUES (p_msg);
END;
CREATE PROCEDURE app.p_external (IN a INT, OUT b INT)
    EXTERNAL NAME 'mylib!myproc'
    LANGUAGE C PARAMETER STYLE SQL FENCED THREADSAFE MODIFIES SQL DATA PROGRAM TYPE SUB DBINFO;
CREATE PROCEDURE app.p_java (IN a VARCHAR(10))
    LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME 'com.example.Procs.run' FENCED NO DBINFO;
CREATE PROCEDURE app.p_cursor_var (OUT p_total INT)
LANGUAGE SQL
BEGIN
    DECLARE cv CURSOR;
    DECLARE v INT;
    DECLARE at_end INT DEFAULT 0;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET at_end = 1;
    SET cv = CURSOR FOR SELECT amount FROM app.orders;
    OPEN cv;
    SET p_total = 0;
    REPEAT
        FETCH cv INTO v;
        IF at_end = 0 THEN SET p_total = p_total + v; END IF;
    UNTIL at_end = 1 END REPEAT;
    CLOSE cv;
END;
-- anonymous block
BEGIN
    DECLARE v INT DEFAULT 0;
    SET v = v + 1;
    CALL DBMS_OUTPUT.PUT_LINE('v = ' || v);
END;
BEGIN ATOMIC
    INSERT INTO t VALUES (1);
    INSERT INTO t VALUES (2);
END;
