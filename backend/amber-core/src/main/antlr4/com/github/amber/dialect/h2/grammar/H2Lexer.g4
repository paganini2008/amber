/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * H2 Database (2.x) SQL lexer grammar.
 *
 * Written from scratch for Amber (a SQL script structure visualizer) using the
 * public H2 documentation as the reference: https://h2database.com/html/grammar.html
 * No H2 source code was used.
 */
lexer grammar H2Lexer;

options {
    caseInsensitive = true;
}

// ============================================================================
// Whitespace and comments
// ============================================================================

WHITESPACE      : [ \t\r\n\f ﻿]+ -> channel(HIDDEN);

// H2 block comments nest.
BLOCK_COMMENT   : '/*' (BLOCK_COMMENT | .)*? '*/' -> channel(HIDDEN);
LINE_COMMENT    : ('--' | '//') ~[\r\n]* -> channel(HIDDEN);

// ============================================================================
// Keywords (one token per keyword used by the parser; most of them are
// non-reserved, see non_reserved_keyword in H2Parser.g4)
// ============================================================================

// ----- generated: keywords -----
ABSENT                  : 'ABSENT';
ACTION                  : 'ACTION';
ADD                     : 'ADD';
ADMIN                   : 'ADMIN';
AFTER                   : 'AFTER';
AGGREGATE               : 'AGGREGATE';
ALIAS                   : 'ALIAS';
ALL                     : 'ALL';
ALTER                   : 'ALTER';
ALWAYS                  : 'ALWAYS';
ANALYZE                 : 'ANALYZE';
AND                     : 'AND';
ANY                     : 'ANY';
ARRAY                   : 'ARRAY';
AS                      : 'AS';
ASC                     : 'ASC';
ASYMMETRIC              : 'ASYMMETRIC';
AT                      : 'AT';
AUTHORIZATION           : 'AUTHORIZATION';
AUTOCOMMIT              : 'AUTOCOMMIT';
AUTO_INCREMENT          : 'AUTO_INCREMENT';
BACKUP                  : 'BACKUP';
BEFORE                  : 'BEFORE';
BEGIN                   : 'BEGIN';
BETWEEN                 : 'BETWEEN';
BINARY                  : 'BINARY';
BLOCKSIZE               : 'BLOCKSIZE';
BOTH                    : 'BOTH';
BY                      : 'BY';
CACHE                   : 'CACHE';
CACHED                  : 'CACHED';
CALL                    : 'CALL';
CASCADE                 : 'CASCADE';
CASE                    : 'CASE';
CAST                    : 'CAST';
CHANGE                  : 'CHANGE';
CHAR                    : 'CHAR';
CHARACTER               : 'CHARACTER';
CHARACTERISTICS         : 'CHARACTERISTICS';
CHARACTERS              : 'CHARACTERS';
CHARSET                 : 'CHARSET';
CHECK                   : 'CHECK';
CHECKPOINT              : 'CHECKPOINT';
CIPHER                  : 'CIPHER';
COLLATE                 : 'COLLATE';
COLUMN                  : 'COLUMN';
COLUMNS                 : 'COLUMNS';
COMMENT                 : 'COMMENT';
COMMIT                  : 'COMMIT';
COMMITTED               : 'COMMITTED';
COMPACT                 : 'COMPACT';
COMPRESSION             : 'COMPRESSION';
CONSTANT                : 'CONSTANT';
CONSTRAINT              : 'CONSTRAINT';
CONTINUE                : 'CONTINUE';
COUNT                   : 'COUNT';
CREATE                  : 'CREATE';
CROSS                   : 'CROSS';
CUBE                    : 'CUBE';
CURRENT                 : 'CURRENT';
CURRENT_CATALOG         : 'CURRENT_CATALOG';
CURRENT_DATE            : 'CURRENT_DATE';
CURRENT_PATH            : 'CURRENT_PATH';
CURRENT_ROLE            : 'CURRENT_ROLE';
CURRENT_SCHEMA          : 'CURRENT_SCHEMA';
CURRENT_TIME            : 'CURRENT_TIME';
CURRENT_TIMESTAMP       : 'CURRENT_TIMESTAMP';
CURRENT_USER            : 'CURRENT_USER';
CYCLE                   : 'CYCLE';
DATA                    : 'DATA';
DATE                    : 'DATE';
DAY                     : 'DAY';
DEFAULT                 : 'DEFAULT';
DEFRAG                  : 'DEFRAG';
DELETE                  : 'DELETE';
DESC                    : 'DESC';
DETERMINISTIC           : 'DETERMINISTIC';
DIRECT                  : 'DIRECT';
DISTINCT                : 'DISTINCT';
DOMAIN                  : 'DOMAIN';
DOUBLE                  : 'DOUBLE';
DROP                    : 'DROP';
DUPLICATE               : 'DUPLICATE';
EACH                    : 'EACH';
ELSE                    : 'ELSE';
EMIT                    : 'EMIT';
END                     : 'END';
ENGINE                  : 'ENGINE';
ENUM                    : 'ENUM';
ERROR                   : 'ERROR';
ESCAPE                  : 'ESCAPE';
EXCEPT                  : 'EXCEPT';
EXCLUDE                 : 'EXCLUDE';
EXECUTE                 : 'EXECUTE';
EXHAUSTED               : 'EXHAUSTED';
EXISTS                  : 'EXISTS';
EXPLAIN                 : 'EXPLAIN';
EXPRESSION              : 'EXPRESSION';
FALSE                   : 'FALSE';
FETCH                   : 'FETCH';
FETCH_SIZE              : 'FETCH_SIZE';
FILES                   : 'FILES';
FILTER                  : 'FILTER';
FINAL                   : 'FINAL';
FIRST                   : 'FIRST';
FOLLOWING               : 'FOLLOWING';
FOR                     : 'FOR';
FORCE                   : 'FORCE';
FOREIGN                 : 'FOREIGN';
FORMAT                  : 'FORMAT';
FROM                    : 'FROM';
FROM_1X                 : 'FROM_1X';
FULL                    : 'FULL';
GENERATED               : 'GENERATED';
GEOMETRY                : 'GEOMETRY';
GLOBAL                  : 'GLOBAL';
GRANT                   : 'GRANT';
GROUP                   : 'GROUP';
GROUPING                : 'GROUPING';
GROUPS                  : 'GROUPS';
HASH                    : 'HASH';
HAVING                  : 'HAVING';
HELP                    : 'HELP';
HOUR                    : 'HOUR';
IDENTITY                : 'IDENTITY';
IF                      : 'IF';
IGNORE                  : 'IGNORE';
ILIKE                   : 'ILIKE';
IMMEDIATE               : 'IMMEDIATE';
IMMEDIATELY             : 'IMMEDIATELY';
IN                      : 'IN';
INCLUDE                 : 'INCLUDE';
INCREMENT               : 'INCREMENT';
INDEX                   : 'INDEX';
INNER                   : 'INNER';
INSERT                  : 'INSERT';
INSTEAD                 : 'INSTEAD';
INTERSECT               : 'INTERSECT';
INTERVAL                : 'INTERVAL';
INTO                    : 'INTO';
INVISIBLE               : 'INVISIBLE';
IS                      : 'IS';
ISOLATION               : 'ISOLATION';
JOIN                    : 'JOIN';
JSON                    : 'JSON';
KEY                     : 'KEY';
KEYS                    : 'KEYS';
LARGE                   : 'LARGE';
LAST                    : 'LAST';
LATERAL                 : 'LATERAL';
LEADING                 : 'LEADING';
LEFT                    : 'LEFT';
LEVEL                   : 'LEVEL';
LIKE                    : 'LIKE';
LIMIT                   : 'LIMIT';
LINKED                  : 'LINKED';
LOCAL                   : 'LOCAL';
LOCALTIME               : 'LOCALTIME';
LOCALTIMESTAMP          : 'LOCALTIMESTAMP';
LOCKED                  : 'LOCKED';
MATCHED                 : 'MATCHED';
MATERIALIZED            : 'MATERIALIZED';
MAXVALUE                : 'MAXVALUE';
MEMORY                  : 'MEMORY';
MERGE                   : 'MERGE';
MINUS                   : 'MINUS';
MINUTE                  : 'MINUTE';
MINVALUE                : 'MINVALUE';
MODIFY                  : 'MODIFY';
MONTH                   : 'MONTH';
NATIONAL                : 'NATIONAL';
NATURAL                 : 'NATURAL';
NCHAR                   : 'NCHAR';
NEW                     : 'NEW';
NEXT                    : 'NEXT';
NO                      : 'NO';
NOBUFFER                : 'NOBUFFER';
NOCACHE                 : 'NOCACHE';
NOCHECK                 : 'NOCHECK';
NOCYCLE                 : 'NOCYCLE';
NODATA                  : 'NODATA';
NOMAXVALUE              : 'NOMAXVALUE';
NOMINVALUE              : 'NOMINVALUE';
NOORDER                 : 'NOORDER';
NOPASSWORDS             : 'NOPASSWORDS';
NOSETTINGS              : 'NOSETTINGS';
NOT                     : 'NOT';
NOWAIT                  : 'NOWAIT';
NULL                    : 'NULL';
NULLS                   : 'NULLS';
NULL_TO_DEFAULT         : 'NULL_TO_DEFAULT';
OBJECT                  : 'OBJECT';
OBJECTS                 : 'OBJECTS';
OCTETS                  : 'OCTETS';
OF                      : 'OF';
OFF                     : 'OFF';
OFFSET                  : 'OFFSET';
OLD                     : 'OLD';
ON                      : 'ON';
ONLY                    : 'ONLY';
OR                      : 'OR';
ORDER                   : 'ORDER';
ORDINALITY              : 'ORDINALITY';
OTHERS                  : 'OTHERS';
OUTER                   : 'OUTER';
OVER                    : 'OVER';
OVERFLOW                : 'OVERFLOW';
OVERRIDING              : 'OVERRIDING';
PARTITION               : 'PARTITION';
PASSWORD                : 'PASSWORD';
PERCENT                 : 'PERCENT';
PERSISTENT              : 'PERSISTENT';
PLACING                 : 'PLACING';
PLAN                    : 'PLAN';
PRECEDING               : 'PRECEDING';
PRECISION               : 'PRECISION';
PREPARE                 : 'PREPARE';
PRESERVE                : 'PRESERVE';
PRIMARY                 : 'PRIMARY';
PRIVILEGES              : 'PRIVILEGES';
QUALIFY                 : 'QUALIFY';
QUEUE                   : 'QUEUE';
QUIRKS_MODE             : 'QUIRKS_MODE';
RANGE                   : 'RANGE';
READ                    : 'READ';
READONLY                : 'READONLY';
RECOMPILE               : 'RECOMPILE';
RECURSIVE               : 'RECURSIVE';
REFERENCES              : 'REFERENCES';
REFERENTIAL_INTEGRITY   : 'REFERENTIAL_INTEGRITY';
REFRESH                 : 'REFRESH';
REGEXP                  : 'REGEXP';
RELEASE                 : 'RELEASE';
RENAME                  : 'RENAME';
REPEATABLE              : 'REPEATABLE';
REPLACE                 : 'REPLACE';
RESPECT                 : 'RESPECT';
RESTART                 : 'RESTART';
RESTRICT                : 'RESTRICT';
REVOKE                  : 'REVOKE';
RIGHT                   : 'RIGHT';
ROLE                    : 'ROLE';
ROLLBACK                : 'ROLLBACK';
ROLLUP                  : 'ROLLUP';
ROW                     : 'ROW';
ROWS                    : 'ROWS';
RUNSCRIPT               : 'RUNSCRIPT';
SALT                    : 'SALT';
SAMPLE_SIZE             : 'SAMPLE_SIZE';
SAVEPOINT               : 'SAVEPOINT';
SCALAR                  : 'SCALAR';
SCHEMA                  : 'SCHEMA';
SCHEMAS                 : 'SCHEMAS';
SCRIPT                  : 'SCRIPT';
SECOND                  : 'SECOND';
SELECT                  : 'SELECT';
SELECTIVITY             : 'SELECTIVITY';
SEPARATOR               : 'SEPARATOR';
SEQUENCE                : 'SEQUENCE';
SERIALIZABLE            : 'SERIALIZABLE';
SESSION                 : 'SESSION';
SESSION_USER            : 'SESSION_USER';
SET                     : 'SET';
SETS                    : 'SETS';
SHOW                    : 'SHOW';
SHUTDOWN                : 'SHUTDOWN';
SIMPLE                  : 'SIMPLE';
SKIP_                   : 'SKIP';
SNAPSHOT                : 'SNAPSHOT';
SOME                    : 'SOME';
SPATIAL                 : 'SPATIAL';
START                   : 'START';
STATEMENT               : 'STATEMENT';
SYMMETRIC               : 'SYMMETRIC';
SYNC                    : 'SYNC';
SYNONYM                 : 'SYNONYM';
SYSTEM                  : 'SYSTEM';
SYSTEM_USER             : 'SYSTEM_USER';
TABLE                   : 'TABLE';
TABLES                  : 'TABLES';
TABLE_DISTINCT          : 'TABLE_DISTINCT';
TARGET                  : 'TARGET';
TEMP                    : 'TEMP';
TEMPORARY               : 'TEMPORARY';
THEN                    : 'THEN';
TIES                    : 'TIES';
TIME                    : 'TIME';
TIMESTAMP               : 'TIMESTAMP';
TO                      : 'TO';
TOP                     : 'TOP';
TRAILING                : 'TRAILING';
TRANSACTION             : 'TRANSACTION';
TRANSACTIONAL           : 'TRANSACTIONAL';
TRIGGER                 : 'TRIGGER';
TRUE                    : 'TRUE';
TRUNCATE                : 'TRUNCATE';
TYPE                    : 'TYPE';
UESCAPE                 : 'UESCAPE';
UNBOUNDED               : 'UNBOUNDED';
UNCOMMITTED             : 'UNCOMMITTED';
UNION                   : 'UNION';
UNIQUE                  : 'UNIQUE';
UNKNOWN                 : 'UNKNOWN';
UNSIGNED                : 'UNSIGNED';
UPDATE                  : 'UPDATE';
UPDATES                 : 'UPDATES';
USE                     : 'USE';
USER                    : 'USER';
USING                   : 'USING';
VALUE                   : 'VALUE';
VALUES                  : 'VALUES';
VARIABLE_BINARY         : 'VARIABLE_BINARY';
VARYING                 : 'VARYING';
VIEW                    : 'VIEW';
VISIBLE                 : 'VISIBLE';
WAIT                    : 'WAIT';
WHEN                    : 'WHEN';
WHERE                   : 'WHERE';
WINDOW                  : 'WINDOW';
WITH                    : 'WITH';
WITHIN                  : 'WITHIN';
WITHOUT                 : 'WITHOUT';
WORK                    : 'WORK';
YEAR                    : 'YEAR';
ZONE                    : 'ZONE';
// ----- end generated: keywords -----

// ============================================================================
// Literals
// ============================================================================

// '...' with '' as the escaped quote
STRING          : '\'' (~'\'' | '\'\'')* '\'';
NATIONAL_STRING : 'N\'' (~'\'' | '\'\'')* '\'';
UNICODE_STRING  : 'U&\'' (~'\'' | '\'\'')* '\'';
BINARY_STRING   : 'X\'' [0-9A-F \t\r\n]* '\'';
// $$ ... $$ (used for Java source of aliases and triggers, and as a plain string)
DOLLAR_STRING   : '$$' .*? '$$';

HEX_LITERAL     : '0X' [0-9A-F] ([0-9A-F_]* [0-9A-F])?;
DECIMAL_LITERAL
    : DIGITS '.' DIGITS? EXPONENT?
    | '.' DIGITS EXPONENT?
    | DIGITS EXPONENT
    ;
INTEGER_LITERAL : DIGITS;

fragment DIGITS   : [0-9] ([0-9_]* [0-9])?;
fragment EXPONENT : 'E' [+-]? [0-9]+;

// ============================================================================
// Parameters and variables
// ============================================================================

PARAMETER       : '?' [0-9]*;
NAMED_PARAMETER : ':' [A-Z_\p{L}] [A-Z_0-9\p{L}]*;
VARIABLE        : '@' [A-Z0-9_$]+;

// ============================================================================
// Identifiers
// ============================================================================

IDENTIFIER          : [A-Z_\p{L}] [A-Z_0-9$\p{L}\p{Nd}]*;
QUOTED_IDENTIFIER   : '"' (~'"' | '""')* '"';
BACKTICK_IDENTIFIER : '`' (~'`' | '``')* '`';

// ============================================================================
// Operators and punctuation
// ============================================================================

CONCAT          : '||';
OVERLAP         : '&&';
NEQ             : '<>' | '!=';
LE              : '<=';
GE              : '>=';
COLON_EQ        : ':=';
EQ              : '=';
LT              : '<';
GT              : '>';
PLUS            : '+';
MINUS_SIGN      : '-';
STAR            : '*';
SLASH           : '/';
PERCENT_SIGN    : '%';
TILDE           : '~';
COLON           : ':';
SEMI            : ';';
COMMA           : ',';
DOT             : '.';
LPAREN          : '(';
RPAREN          : ')';
LBRACKET        : '[';
RBRACKET        : ']';

// Anything else becomes a single error token so the parser reports it.
ERROR_CHAR      : .;
