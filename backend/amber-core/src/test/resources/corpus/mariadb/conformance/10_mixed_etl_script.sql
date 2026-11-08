-- Realistic nightly job for a MariaDB 11 warehouse
SET @job_start = NOW(6);
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

CREATE OR REPLACE TEMPORARY TABLE tmp_stage (
    src_id VARCHAR(40) PRIMARY KEY,
    customer_email VARCHAR(255) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    sold_at DATETIME NOT NULL
) ENGINE=InnoDB;

INSERT INTO tmp_stage (src_id, customer_email, amount, sold_at)
SELECT src_id, LOWER(TRIM(email)), amount, sold_at
FROM landing_sales
WHERE batch_id = (SELECT MAX(batch_id) FROM landing_sales);

WITH ranked AS (
    SELECT src_id, ROW_NUMBER() OVER (PARTITION BY customer_email, sold_at ORDER BY src_id) AS rn
    FROM tmp_stage
)
SELECT COUNT(*) AS dup_rows FROM ranked WHERE rn > 1;

INSERT INTO dim_customer (email, first_seen)
SELECT DISTINCT customer_email, MIN(sold_at) FROM tmp_stage GROUP BY customer_email
ON DUPLICATE KEY UPDATE first_seen = LEAST(first_seen, VALUES(first_seen));

INSERT INTO fact_sales (sale_id, customer_id, amount, sold_at)
SELECT NEXT VALUE FOR seq_fact_sales, c.id, s.amount, s.sold_at
FROM tmp_stage s JOIN dim_customer c ON c.email = s.customer_email
RETURNING sale_id, customer_id;

UPDATE dim_customer c
JOIN (SELECT customer_email, SUM(amount) AS total FROM tmp_stage GROUP BY customer_email) t ON t.customer_email = c.email
SET c.lifetime_value = c.lifetime_value + t.total, c.updated_at = NOW();

DELETE FROM landing_sales WHERE batch_id < (SELECT MAX(batch_id) - 7 FROM landing_sales) RETURNING batch_id;

DELIMITER //
CREATE OR REPLACE PROCEDURE sp_rebuild_monthly(IN p_month CHAR(7))
BEGIN
    DECLARE v_sql TEXT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        GET DIAGNOSTICS CONDITION 1 @err_no = MYSQL_ERRNO, @err_msg = MESSAGE_TEXT;
        INSERT INTO etl_log (job, status, message) VALUES ('monthly', 'error', CONCAT(@err_no, ': ', @err_msg));
        ROLLBACK;
    END;
    START TRANSACTION;
    SET v_sql = CONCAT('DELETE FROM agg_monthly WHERE month_key = ', QUOTE(p_month));
    EXECUTE IMMEDIATE v_sql;
    INSERT INTO agg_monthly (month_key, customer_id, total, rank_in_month)
    SELECT p_month, customer_id, SUM(amount),
           RANK() OVER (ORDER BY SUM(amount) DESC)
    FROM fact_sales
    WHERE DATE_FORMAT(sold_at, '%Y-%m') = p_month
    GROUP BY customer_id;
    COMMIT;
END //
DELIMITER ;

CALL sp_rebuild_monthly(DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m'));

SELECT customer_id, total
FROM agg_monthly FOR SYSTEM_TIME AS OF TIMESTAMP @job_start
WHERE month_key = DATE_FORMAT(CURDATE(), '%Y-%m');

INSERT INTO etl_log (job, status, started_at, finished_at)
VALUES ('nightly', 'ok', @job_start, NOW(6));

DROP TEMPORARY TABLE IF EXISTS tmp_stage;
