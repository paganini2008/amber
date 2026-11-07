-- run as SYSTEM: creates the LIB schema owner (no login), then its tables
create user lib no authentication quota unlimited on users;
create table lib.branches (branch_id number primary key, parent_id number, name varchar2(80) not null, overdue_count number default 0);
create table lib.members (member_id number primary key, display_name varchar2(120) not null, status varchar2(10) not null, fee_cap number(6,2));
create table lib.copies (copy_id number primary key, branch_id number not null references lib.branches);
create index lib.ix_copies_branch on lib.copies (branch_id);
create table lib.loans (loan_id number primary key, copy_id number not null, member_id number not null, due_on date not null, returned_on date);
create index lib.ix_loans_member on lib.loans (member_id);
create table lib.member_fees (member_id number not null, period varchar2(7) not null, amount number(8,2), updated_on date, primary key (member_id, period));
create table lib.fee_waivers (member_id number not null, reason varchar2(200));
insert into lib.branches (branch_id, parent_id, name) values (1, null, 'Central');
insert into lib.branches (branch_id, parent_id, name) values (2, 1, 'Riverside');
insert into lib.members values (100, 'Ada Reader', 'ACTIVE', 10);
insert into lib.copies values (500, 2);
insert into lib.loans values (900, 500, 100, date '2026-10-01', null);
commit;
