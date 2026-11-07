-- Native-mode stored programs, triggers, events, compound statements
DELIMITER //
CREATE OR REPLACE PROCEDURE sp_archive_orders(IN p_before DATE, OUT p_moved INT)
MODIFIES SQL DATA
BEGIN
    DECLARE v_id INT;
    DECLARE v_done BOOLEAN DEFAULT FALSE;
    DECLARE cur CURSOR FOR SELECT id FROM orders WHERE created_at < p_before;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = TRUE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL SET MESSAGE_TEXT = 'archive failed';
    END;
    SET p_moved = 0;
    START TRANSACTION;
    OPEN cur;
    fetch_loop: LOOP
        FETCH cur INTO v_id;
        IF v_done THEN LEAVE fetch_loop; END IF;
        INSERT INTO orders_archive SELECT * FROM orders WHERE id = v_id;
        DELETE FROM orders WHERE id = v_id;
        SET p_moved = p_moved + 1;
    END LOOP;
    CLOSE cur;
    COMMIT;
END //

CREATE FUNCTION IF NOT EXISTS fn_net(p_gross DECIMAL(10,2)) RETURNS DECIMAL(10,2)
DETERMINISTIC
RETURN p_gross / 1.2 //

CREATE PROCEDURE sp_cursor_for()
BEGIN
    DECLARE total INT DEFAULT 0;
    FOR rec IN (SELECT id, qty FROM orders) DO
        SET total = total + rec.qty;
    END FOR;
    FOR i IN 1..10 DO
        SET total = total + i;
    END FOR;
    SELECT total;
END //

CREATE PROCEDURE sp_cursor_params(p_status VARCHAR(10))
BEGIN
    DECLARE v_id INT;
    DECLARE c1 CURSOR (pc_status VARCHAR(10)) FOR SELECT id FROM orders WHERE status = pc_status;
    OPEN c1(p_status);
    FETCH c1 INTO v_id;
    CLOSE c1;
END //

CREATE TRIGGER IF NOT EXISTS trg_orders_bi BEFORE INSERT ON orders FOR EACH ROW
BEGIN
    IF NEW.qty < 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'negative qty';
    END IF;
END //

CREATE OR REPLACE TRIGGER trg_orders_multi AFTER INSERT ON orders FOR EACH ROW
INSERT INTO order_log (order_id, logged_at) VALUES (NEW.id, NOW()) //

CREATE EVENT IF NOT EXISTS ev_cleanup ON SCHEDULE EVERY 1 HOUR
DO BEGIN
    DELETE FROM sessions WHERE expires_at < NOW();
END //
DELIMITER ;

DELIMITER //
BEGIN NOT ATOMIC
    DECLARE v INT DEFAULT 0;
    SET v = (SELECT COUNT(*) FROM orders);
    IF v > 100 THEN
        SELECT 'many orders';
    END IF;
END //
DELIMITER ;

DELIMITER $$
BEGIN NOT ATOMIC
    DECLARE CONTINUE HANDLER FOR SQLWARNING BEGIN END;
    WHILE (SELECT COUNT(*) FROM queue) > 0 DO
        DELETE FROM queue ORDER BY id LIMIT 100;
    END WHILE;
END $$
DELIMITER ;

IF (SELECT COUNT(*) FROM orders) > 0 THEN SELECT 'has rows'; END IF;
CALL sp_archive_orders('2020-01-01', @moved);
SELECT @moved, fn_net(120.00);
EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM orders WHERE qty > ?' USING 5;
PREPARE ps FROM 'DELETE FROM sessions WHERE id = ?';
EXECUTE ps USING 42;
DEALLOCATE PREPARE ps;
DROP PROCEDURE IF EXISTS sp_cursor_for;
DROP FUNCTION IF EXISTS fn_net;
DROP TRIGGER IF EXISTS trg_orders_multi;
DROP EVENT IF EXISTS ev_cleanup;
