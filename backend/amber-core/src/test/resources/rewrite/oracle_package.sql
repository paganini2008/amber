create table acct (id number primary key, owner_id number not null, opened date not null, kind varchar2(5));

create or replace package body pkg_acct as
  procedure p_clean(p_day date, v_flag number) is
    v_cnt number;
  begin
    -- count the accounts opened that day
    select count(*) into v_cnt from acct a where trunc(a.opened) = trunc(p_day);
    delete from acct where owner_id not in (select o.id from owners o);
    select count(*) into v_cnt from acct where v_flag not in (select k.flag from flags k);
    for r in (select id from acct order by id) loop
      update acct set kind = 'Y' where id = r.id;
    end loop;
    commit;
  end p_clean;
end pkg_acct;
/
