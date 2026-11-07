/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
DROP PROCEDURE IF EXISTS `sp_archive`;
DELIMITER $$
CREATE PROCEDURE `sp_archive`(IN p_days INT, OUT p_moved INT)
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_id BIGINT;
    DECLARE cur CURSOR FOR
        SELECT id
        FROM orders
        WHERE created_at < now() - INTERVAL p_days day;
    DECLARE CONTINUE HANDLER FOR NOT found SET v_done = 1;
    SET p_moved = 0;
    OPEN cur;
    fetch_loop: LOOP
        FETCH cur INTO v_id;
        IF v_done = 1 THEN
            LEAVE fetch_loop;
        END IF;
        INSERT INTO orders_archive
        SELECT *
        FROM orders
        WHERE id = v_id;
        DELETE FROM orders
        WHERE id = v_id;
        SET p_moved = p_moved + 1;
    END LOOP fetch_loop;
    CLOSE cur;
END$$
DELIMITER ;
SELECT 5--1 AS not_a_comment, @@session.sql_mode, `order`.`id`
FROM `order` # hash comment
WHERE `order`.note <> 'a -- b'
    AND `order`.qty BETWEEN 1 AND 10;
INSERT INTO t (a, b)
VALUES
    (1, 'x'),
    (2, 'y')
ON DUPLICATE KEY UPDATE b = VALUES(b);
