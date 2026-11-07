/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
drop procedure if exists `sp_archive`;
DELIMITER $$
create procedure `sp_archive`(in p_days int, out p_moved int)
begin
  declare v_done int default 0;
  declare v_id bigint;
  declare cur cursor for select id from orders where created_at < now() - interval p_days day;
  declare continue handler for not found set v_done = 1;
  set p_moved = 0;
  open cur;
  fetch_loop: loop
    fetch cur into v_id;
    if v_done = 1 then leave fetch_loop; end if;
    insert into orders_archive select * from orders where id = v_id;
    delete from orders where id = v_id;
    set p_moved = p_moved + 1;
  end loop fetch_loop;
  close cur;
end$$
DELIMITER ;
select 5--1 as not_a_comment, @@session.sql_mode, `order`.`id` from `order` # hash comment
where `order`.note <> 'a -- b' and `order`.qty between 1 and 10;
insert into t (a, b) values (1, 'x'), (2, 'y') on duplicate key update b = values(b);
