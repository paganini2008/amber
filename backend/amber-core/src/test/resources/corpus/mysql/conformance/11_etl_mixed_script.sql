-- Nightly ETL: staging -> warehouse (realistic mixed script, as produced by a DBA)
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
SET NAMES utf8mb4;
USE `warehouse`;

DROP TABLE IF EXISTS `stg_sales`;
CREATE TABLE `stg_sales` (
  `src_id` varchar(40) NOT NULL,
  `sold_at` datetime NOT NULL,
  `store_code` varchar(10) NOT NULL,
  `sku` varchar(30) NOT NULL,
  `qty` int NOT NULL DEFAULT '1',
  `price` decimal(10,2) NOT NULL,
  `payload` json DEFAULT NULL,
  PRIMARY KEY (`src_id`),
  KEY `ix_stg_store` (`store_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

LOCK TABLES `stg_sales` WRITE;
/*!40000 ALTER TABLE `stg_sales` DISABLE KEYS */;
INSERT INTO `stg_sales` VALUES ('A-1','2024-05-01 10:00:00','S01','SKU-9',2,19.99,'{"channel": "web"}'),('A-2','2024-05-01 11:30:00','S02','SKU-3',1,5.00,NULL);
/*!40000 ALTER TABLE `stg_sales` ENABLE KEYS */;
UNLOCK TABLES;

CREATE TEMPORARY TABLE tmp_daily (
  store_code VARCHAR(10), sale_day DATE, revenue DECIMAL(14,2), units INT,
  PRIMARY KEY (store_code, sale_day)
);

INSERT INTO tmp_daily (store_code, sale_day, revenue, units)
WITH cleaned AS (
    SELECT store_code, DATE(sold_at) AS sale_day, qty, price,
           ROW_NUMBER() OVER (PARTITION BY src_id ORDER BY sold_at DESC) AS dup_rank
    FROM stg_sales
    WHERE qty > 0 AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.channel')) <> 'test'
)
SELECT store_code, sale_day, SUM(qty * price), SUM(qty)
FROM cleaned WHERE dup_rank = 1
GROUP BY store_code, sale_day;

INSERT INTO fact_daily_sales (store_code, sale_day, revenue, units, loaded_at)
SELECT * FROM (SELECT t.store_code, t.sale_day, t.revenue AS new_rev, t.units AS new_units, NOW() AS ts FROM tmp_daily AS t) AS src
ON DUPLICATE KEY UPDATE revenue = new_rev, units = new_units, loaded_at = ts;

UPDATE dim_store s
JOIN (SELECT store_code, MAX(sale_day) last_day FROM tmp_daily GROUP BY store_code) d ON d.store_code = s.store_code
SET s.last_sale_day = d.last_day;

DELETE s FROM stg_sales s JOIN tmp_daily t ON t.store_code = s.store_code AND t.sale_day = DATE(s.sold_at);

DROP PROCEDURE IF EXISTS `sp_close_period`;
DELIMITER ;;
CREATE DEFINER=`etl`@`localhost` PROCEDURE `sp_close_period`(IN p_period CHAR(7))
BEGIN
  DECLARE v_err TEXT;
  DECLARE v_store VARCHAR(10);
  DECLARE v_done TINYINT DEFAULT 0;
  DECLARE c_stores CURSOR FOR SELECT DISTINCT store_code FROM fact_daily_sales WHERE DATE_FORMAT(sale_day, '%Y-%m') = p_period;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    GET DIAGNOSTICS CONDITION 1 v_err = MESSAGE_TEXT;
    ROLLBACK;
    INSERT INTO etl_errors (proc_name, message, logged_at) VALUES ('sp_close_period', v_err, NOW());
  END;

  START TRANSACTION;
  OPEN c_stores;
  store_loop: LOOP
    FETCH c_stores INTO v_store;
    IF v_done = 1 THEN LEAVE store_loop; END IF;
    SET @stmt = CONCAT('INSERT INTO monthly_', REPLACE(p_period, '-', '_'),
                       ' SELECT store_code, SUM(revenue) FROM fact_daily_sales WHERE store_code = ? GROUP BY store_code');
    PREPARE ps FROM @stmt;
    SET @s = v_store;
    EXECUTE ps USING @s;
    DEALLOCATE PREPARE ps;
  END LOOP;
  CLOSE c_stores;
  COMMIT;
END ;;
DELIMITER ;

CALL `sp_close_period`('2024-05');

SELECT store_code, sale_day, revenue,
       SUM(revenue) OVER (PARTITION BY store_code ORDER BY sale_day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS rev_7d,
       revenue - LAG(revenue) OVER (PARTITION BY store_code ORDER BY sale_day) AS delta
FROM fact_daily_sales
WHERE sale_day >= CURDATE() - INTERVAL 30 DAY
ORDER BY store_code, sale_day;

DROP TEMPORARY TABLE IF EXISTS tmp_daily;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
