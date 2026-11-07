-- Store statistics refresh (synthetic test script for Amber).
DROP TEMPORARY TABLE IF EXISTS tmp_sales;
CREATE TEMPORARY TABLE tmp_sales AS
WITH recent AS (
  SELECT s.store_id, s.amount FROM retail.sale s WHERE s.sold_at >= CURDATE() - INTERVAL 7 DAY
)
SELECT r.store_id, SUM(r.amount) AS total FROM recent r GROUP BY r.store_id;

UPDATE retail.store st JOIN tmp_sales t ON t.store_id = st.store_id SET st.weekly_total = t.total;
DELETE s FROM retail.sale_stage s JOIN tmp_sales t ON t.store_id = s.store_id;
INSERT INTO retail.store_history (store_id, total, noted_at)
SELECT store_id, total, NOW() FROM tmp_sales
ON DUPLICATE KEY UPDATE total = VALUES(total);

DELIMITER //
CREATE PROCEDURE retail.flag_slow_stores(IN threshold DECIMAL(12,2))
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE v_store INT;
  DECLARE cur CURSOR FOR SELECT store_id FROM retail.store WHERE weekly_total < threshold;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_store;
    IF done THEN
      LEAVE read_loop;
    END IF;
    INSERT INTO retail.store_flag (store_id, flagged_at) VALUES (v_store, NOW());
  END LOOP;
  CLOSE cur;
END//
DELIMITER ;

CALL retail.flag_slow_stores(1000.00);
DROP TEMPORARY TABLE tmp_sales;
