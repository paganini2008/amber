-- Stored procedures and functions with DELIMITER
DROP PROCEDURE IF EXISTS sp_refresh_totals;
DELIMITER //
CREATE DEFINER = `etl_user`@`%` PROCEDURE sp_refresh_totals(IN p_since DATE, OUT p_rows INT, INOUT p_note VARCHAR(200))
    MODIFIES SQL DATA
    SQL SECURITY INVOKER
    COMMENT 'Rebuilds order totals'
BEGIN
    DECLARE v_done INT DEFAULT FALSE;
    DECLARE v_cust INT UNSIGNED;
    DECLARE v_total DECIMAL(12,2) DEFAULT 0;
    DECLARE cur_cust CURSOR FOR SELECT customer_id, SUM(line_total) FROM orders WHERE order_date >= p_since GROUP BY customer_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = TRUE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        GET DIAGNOSTICS CONDITION 1 @sqlstate = RETURNED_SQLSTATE, @msg = MESSAGE_TEXT;
        ROLLBACK;
        RESIGNAL;
    END;
    SET p_rows = 0;
    START TRANSACTION;
    OPEN cur_cust;
    read_loop: LOOP
        FETCH cur_cust INTO v_cust, v_total;
        IF v_done THEN
            LEAVE read_loop;
        END IF;
        INSERT INTO order_totals (customer_id, total) VALUES (v_cust, v_total)
            ON DUPLICATE KEY UPDATE total = VALUES(total);
        SET p_rows = p_rows + 1;
    END LOOP read_loop;
    CLOSE cur_cust;
    COMMIT;
    SET p_note = CONCAT(IFNULL(p_note, ''), ' rows=', p_rows);
END //
DELIMITER ;

DELIMITER $$
CREATE FUNCTION fn_discount(p_amount DECIMAL(10,2), p_tier VARCHAR(10)) RETURNS DECIMAL(10,2)
DETERMINISTIC NO SQL
BEGIN
    DECLARE v_rate DECIMAL(4,3);
    CASE p_tier
        WHEN 'gold' THEN SET v_rate = 0.10;
        WHEN 'silver' THEN SET v_rate = 0.05;
        ELSE SET v_rate = 0;
    END CASE;
    RETURN ROUND(p_amount * (1 - v_rate), 2);
END$$

CREATE PROCEDURE sp_loops(IN p_n INT)
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE s VARCHAR(1000) DEFAULT '';
    WHILE i < p_n DO
        SET i = i + 1;
        IF MOD(i, 2) = 0 THEN
            ITERATE_LABEL: BEGIN END;
        ELSEIF i = 7 THEN
            SET s = CONCAT(s, 'seven');
        ELSE
            SET s = CONCAT(s, i, ',');
        END IF;
    END WHILE;
    outer_rep: REPEAT
        SET i = i - 1;
    UNTIL i <= 0 END REPEAT outer_rep;
    lbl: LOOP
        SET i = i + 1;
        IF i < 3 THEN ITERATE lbl; END IF;
        LEAVE lbl;
    END LOOP lbl;
    CASE WHEN i > 2 THEN SELECT 'big'; ELSE SELECT 'small'; END CASE;
    SELECT s AS result;
END$$

CREATE PROCEDURE sp_errors(IN p_id INT)
BEGIN
    DECLARE duplicate_key CONDITION FOR 1062;
    DECLARE custom_err CONDITION FOR SQLSTATE '45000';
    DECLARE CONTINUE HANDLER FOR duplicate_key SET @dup = 1;
    DECLARE CONTINUE HANDLER FOR SQLWARNING, NOT FOUND BEGIN END;
    IF p_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'id required', MYSQL_ERRNO = 5001;
    END IF;
    IF p_id < 0 THEN
        SIGNAL custom_err SET MESSAGE_TEXT = 'negative id';
    END IF;
    INSERT INTO customers (customer_id, email) VALUES (p_id, CONCAT('u', p_id, '@example.com'));
    GET DIAGNOSTICS @cnt = ROW_COUNT;
END$$

CREATE PROCEDURE sp_dynamic(IN p_table VARCHAR(64))
BEGIN
    SET @sql = CONCAT('SELECT COUNT(*) INTO @c FROM ', p_table);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
    SELECT @c;
END$$

CREATE FUNCTION fn_customer_name(p_id INT) RETURNS VARCHAR(100) CHARSET utf8mb4
READS SQL DATA
BEGIN
    DECLARE v_name VARCHAR(100);
    SELECT display_name INTO v_name FROM customers WHERE customer_id = p_id;
    RETURN v_name;
END$$
DELIMITER ;

CALL sp_refresh_totals('2024-01-01', @rows, @note);
SELECT @rows, @note;
CALL sp_loops(10);
CALL sp_dynamic('orders');
SELECT fn_discount(100, 'gold'), sales_db.fn_customer_name(1);
CREATE FUNCTION fn_simple(x INT) RETURNS INT DETERMINISTIC RETURN x * 2;
CREATE PROCEDURE sp_simple() SELECT NOW();
ALTER PROCEDURE sp_simple COMMENT 'one liner' SQL SECURITY DEFINER;
ALTER FUNCTION fn_simple NO SQL;
SHOW CREATE PROCEDURE sp_simple;
SHOW PROCEDURE STATUS WHERE Db = 'sales_db';
DROP FUNCTION IF EXISTS fn_simple;
DROP PROCEDURE sp_simple;
