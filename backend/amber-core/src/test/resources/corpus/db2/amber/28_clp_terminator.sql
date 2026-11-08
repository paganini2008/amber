-- CLP script with an alternative terminator, split the way Amber splits it
CONNECT TO sample;
SET SCHEMA app;
--#SET TERMINATOR @
CREATE OR REPLACE PROCEDURE app.p_at ()
LANGUAGE SQL
BEGIN
    DECLARE v INT DEFAULT 0;
    SET v = 1;
    IF v = 1 THEN
        UPDATE app.counter SET n = n + 1;
    END IF;
END@
CREATE OR REPLACE PROCEDURE app.p_at2 ()
LANGUAGE SQL
BEGIN
    CALL app.p_at();
END;
@
CREATE TRIGGER app.trg_at AFTER INSERT ON app.orders
REFERENCING NEW AS n FOR EACH ROW
BEGIN ATOMIC
    UPDATE app.stats SET cnt = cnt + 1;
END
@
SELECT * FROM app.orders@
--#SET TERMINATOR ;
RUNSTATS ON TABLE app.orders WITH DISTRIBUTION AND DETAILED INDEXES ALL;
REORG TABLE app.orders INPLACE ALLOW WRITE ACCESS;
EXPORT TO /tmp/orders.del OF DEL MODIFIED BY nochardel SELECT * FROM app.orders;
IMPORT FROM /tmp/orders.del OF DEL INSERT INTO app.orders_copy;
LOAD FROM /tmp/orders.del OF DEL REPLACE INTO app.orders_copy NONRECOVERABLE;
DESCRIBE TABLE app.orders;
REORGCHK CURRENT STATISTICS ON TABLE app.orders;
COMMIT;
TERMINATE;
