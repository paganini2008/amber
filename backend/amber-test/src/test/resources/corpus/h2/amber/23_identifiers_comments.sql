-- Identifier quoting, case, comments
// H2 also accepts double-slash line comments
/* block comment */
/* nested /* block */ comment */
SELECT /* inline */ 1 -- trailing
;
SELECT "lower case", "has ""quotes"" inside", "SELECT" FROM "weird""table";
SELECT `mysql`, `has `` backtick` FROM `db`.`table`;
SELECT Test.Id, TEST.ID, test.id FROM Test;
SELECT "PUBLIC"."TEST"."ID" FROM "PUBLIC"."TEST";
SELECT umlaut_ä, café_name FROM unicode_table;
SELECT a$b, _x FROM t;
/*
  multi-line
  /* nested level 2 /* level 3 */ */
*/
SELECT 2;;
;
select lower_case_keywords from mixed where x = 1 order by 1;
SeLeCt MiXeD FrOm CaSeS;
SELECT 1
