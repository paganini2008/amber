create or replace package body pkg_billing as
  -- package state
  g_rate constant number := 0.2;
  cursor c_open(p_cust number) is select invoice_id, amount from invoices where customer_id = p_cust and status = 'OPEN';
  type t_ids is table of number index by pls_integer;

  function tax(p_amount in number) return number is
  begin
    return round(p_amount * g_rate, 2);
  end tax;

  procedure close_invoices(p_cust in number, p_closed out number) is
    v_ids t_ids;
    v_total number := 0;
  begin
    p_closed := 0;
    for r in c_open(p_cust) loop
      v_total := v_total + r.amount + tax(r.amount);
      if r.amount > 1000 then dbms_output.put_line('large invoice ' || r.invoice_id);
      elsif r.amount = 0 then continue;
      else null;
      end if;
      v_ids(v_ids.count + 1) := r.invoice_id;
    end loop;
    forall i in 1 .. v_ids.count update invoices set status = 'CLOSED' where invoice_id = v_ids(i);
    p_closed := sql%rowcount;
    case
      when p_closed = 0 then dbms_output.put_line('nothing');
      when p_closed < 10 then dbms_output.put_line('few');
      else dbms_output.put_line('many');
    end case;
    <<retry_block>>
    declare
      v_try pls_integer := 0;
    begin
      while v_try < 3 loop
        v_try := v_try + 1;
        exit when v_total > 0;
      end loop;
    end retry_block;
  exception
    when no_data_found then p_closed := 0;
    when others then
      rollback;
      raise_application_error(-20001, 'close failed: ' || sqlerrm);
  end close_invoices;
end pkg_billing;
/
show errors
