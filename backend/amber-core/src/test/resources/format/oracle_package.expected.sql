CREATE OR REPLACE PACKAGE BODY pkg_billing AS
    -- package state
    g_rate CONSTANT NUMBER := 0.2;
    CURSOR c_open(p_cust NUMBER) IS
        SELECT invoice_id, amount
        FROM invoices
        WHERE customer_id = p_cust
            AND status = 'OPEN';
    type t_ids IS TABLE OF NUMBER INDEX BY pls_integer;

    FUNCTION tax(p_amount IN NUMBER) RETURN NUMBER IS
    BEGIN
        RETURN round(p_amount * g_rate, 2);
    END tax;

    PROCEDURE close_invoices(p_cust IN NUMBER, p_closed OUT NUMBER) IS
        v_ids t_ids;
        v_total NUMBER := 0;
    BEGIN
        p_closed := 0;
        FOR r IN c_open(p_cust) LOOP
            v_total := v_total + r.amount + tax(r.amount);
            IF r.amount > 1000 THEN
                dbms_output.put_line('large invoice ' || r.invoice_id);
            ELSIF r.amount = 0 THEN
                CONTINUE;
            ELSE
                NULL;
            END IF;
            v_ids(v_ids.count + 1) := r.invoice_id;
        END LOOP;
        FORALL i IN 1 .. v_ids.count
            UPDATE invoices
            SET status = 'CLOSED'
            WHERE invoice_id = v_ids(i);
        p_closed := sql%rowcount;
        CASE
            WHEN p_closed = 0 THEN
                dbms_output.put_line('nothing');
            WHEN p_closed < 10 THEN
                dbms_output.put_line('few');
            ELSE
                dbms_output.put_line('many');
        END CASE;
        <<retry_block>>
        DECLARE
            v_try pls_integer := 0;
        BEGIN
            WHILE v_try < 3 LOOP
                v_try := v_try + 1;
                EXIT WHEN v_total > 0;
            END LOOP;
        END retry_block;
    EXCEPTION
        WHEN no_data_found THEN
            p_closed := 0;
        WHEN OTHERS THEN
            ROLLBACK;
            raise_application_error(-20001, 'close failed: ' || sqlerrm);
    END close_invoices;
END pkg_billing;
/
show errors
