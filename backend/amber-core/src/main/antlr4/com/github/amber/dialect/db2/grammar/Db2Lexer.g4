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
 * IBM Db2 for Linux, UNIX and Windows (Db2 12.1) SQL and SQL PL lexer grammar.
 *
 * Written from scratch for Amber (a SQL script structure visualizer) using the
 * public IBM Db2 12.1 SQL Reference documentation as the reference:
 * https://www.ibm.com/docs/en/db2/12.1
 * No IBM source code was used.
 *
 * Statement terminators: Amber splits CLP scripts into units before parsing
 * (terminator ';' or '@', or the one set with '--#SET TERMINATOR'). The lexer
 * therefore treats '@' as an identifier character, as Db2 does.
 */
lexer grammar Db2Lexer;

options {
    caseInsensitive = true;
}

// ============================================================================
// Whitespace and comments
// ============================================================================

WHITESPACE      : [ \t\r\n\f ﻿]+ -> channel(HIDDEN);

// Bracketed comments nest in Db2.
BLOCK_COMMENT   : '/*' (BLOCK_COMMENT | .)*? '*/' -> channel(HIDDEN);
// Also covers CLP directives such as --#SET TERMINATOR @
LINE_COMMENT    : '--' ~[\r\n]* -> channel(HIDDEN);

// ============================================================================
// Keywords (one token per keyword used by the parser; most of them are
// non-reserved, see non_reserved_keyword in Db2Parser.g4)
// ============================================================================

// ----- generated: keywords -----
ACCESS                  : 'ACCESS';
ACTION                  : 'ACTION';
ACTIVATE                : 'ACTIVATE';
ADAPTIVE                : 'ADAPTIVE';
ADD                     : 'ADD';
ADMIN                   : 'ADMIN';
ADMINISTRATION          : 'ADMINISTRATION';
AFTER                   : 'AFTER';
AGE                     : 'AGE';
ALIAS                   : 'ALIAS';
ALL                     : 'ALL';
ALLOCATE                : 'ALLOCATE';
ALLOW                   : 'ALLOW';
ALTER                   : 'ALTER';
ALWAYS                  : 'ALWAYS';
ANCHOR                  : 'ANCHOR';
AND                     : 'AND';
ANY                     : 'ANY';
APPEND                  : 'APPEND';
ARRAY                   : 'ARRAY';
AS                      : 'AS';
ASC                     : 'ASC';
ASCII                   : 'ASCII';
ASENSITIVE              : 'ASENSITIVE';
ASSOCIATE               : 'ASSOCIATE';
ASYNCHRONY              : 'ASYNCHRONY';
AT                      : 'AT';
ATOMIC                  : 'ATOMIC';
ATTACH                  : 'ATTACH';
ATTRIBUTES              : 'ATTRIBUTES';
AUTHORIZATION           : 'AUTHORIZATION';
AUTOMATIC               : 'AUTOMATIC';
AUTONOMOUS              : 'AUTONOMOUS';
AUTORESIZE              : 'AUTORESIZE';
BEFORE                  : 'BEFORE';
BEGIN                   : 'BEGIN';
BERNOULLI               : 'BERNOULLI';
BETWEEN                 : 'BETWEEN';
BINARY                  : 'BINARY';
BIT                     : 'BIT';
BLOCKINSERT             : 'BLOCKINSERT';
BLOCKSIZE               : 'BLOCKSIZE';
BODY                    : 'BODY';
BOTH                    : 'BOTH';
BUFFERPOOL              : 'BUFFERPOOL';
BUILD                   : 'BUILD';
BUSINESS_TIME           : 'BUSINESS_TIME';
BY                      : 'BY';
CACHE                   : 'CACHE';
CACHING                 : 'CACHING';
CALL                    : 'CALL';
CALLED                  : 'CALLED';
CALLER                  : 'CALLER';
CAPTURE                 : 'CAPTURE';
CARDINALITY             : 'CARDINALITY';
CASCADE                 : 'CASCADE';
CASCADED                : 'CASCADED';
CASE                    : 'CASE';
CAST                    : 'CAST';
CCSID                   : 'CCSID';
CHANGE                  : 'CHANGE';
CHANGES                 : 'CHANGES';
CHAR                    : 'CHAR';
CHARACTER               : 'CHARACTER';
CHECK                   : 'CHECK';
CHECKED                 : 'CHECKED';
CLASS                   : 'CLASS';
CLIENT                  : 'CLIENT';
CLOSE                   : 'CLOSE';
CLUSTER                 : 'CLUSTER';
COLLECT                 : 'COLLECT';
COLUMN                  : 'COLUMN';
COLUMNS                 : 'COLUMNS';
COMMENT                 : 'COMMENT';
COMMIT                  : 'COMMIT';
COMPACT                 : 'COMPACT';
COMPARISONS             : 'COMPARISONS';
COMPRESS                : 'COMPRESS';
COMPRESSION             : 'COMPRESSION';
CONCAT                  : 'CONCAT';
CONDITION               : 'CONDITION';
CONNECT                 : 'CONNECT';
CONNECT_BY_ROOT         : 'CONNECT_BY_ROOT';
CONSTANT                : 'CONSTANT';
CONSTRAINT              : 'CONSTRAINT';
CONTAINERS              : 'CONTAINERS';
CONTAINS                : 'CONTAINS';
CONTINUE                : 'CONTINUE';
CONTROL                 : 'CONTROL';
CONVERT                 : 'CONVERT';
CREATE                  : 'CREATE';
CROSS                   : 'CROSS';
CS                      : 'CS';
CUBE                    : 'CUBE';
CURRENT                 : 'CURRENT';
CURSOR                  : 'CURSOR';
CURSORS                 : 'CURSORS';
CYCLE                   : 'CYCLE';
DATA                    : 'DATA';
DATABASE                : 'DATABASE';
DATE                    : 'DATE';
DAY                     : 'DAY';
DAYS                    : 'DAYS';
DB2SQL                  : 'DB2SQL';
DBINFO                  : 'DBINFO';
DBPARTITIONNUM          : 'DBPARTITIONNUM';
DBPARTITIONNUMS         : 'DBPARTITIONNUMS';
DEACTIVATE              : 'DEACTIVATE';
DECFLOAT                : 'DECFLOAT';
DECLARE                 : 'DECLARE';
DEFAULT                 : 'DEFAULT';
DEFAULTS                : 'DEFAULTS';
DEFERRED                : 'DEFERRED';
DEFINITION              : 'DEFINITION';
DELETE                  : 'DELETE';
DESC                    : 'DESC';
DESCRIBE                : 'DESCRIBE';
DETACH                  : 'DETACH';
DETAILED                : 'DETAILED';
DETERMINISTIC           : 'DETERMINISTIC';
DEVICE                  : 'DEVICE';
DIAGNOSTICS             : 'DIAGNOSTICS';
DIMENSIONS              : 'DIMENSIONS';
DISABLE                 : 'DISABLE';
DISALLOW                : 'DISALLOW';
DISCONNECT              : 'DISCONNECT';
DISPATCH                : 'DISPATCH';
DISTINCT                : 'DISTINCT';
DISTRIBUTE              : 'DISTRIBUTE';
DO                      : 'DO';
DOUBLE                  : 'DOUBLE';
DROP                    : 'DROP';
DROPPED                 : 'DROPPED';
DYNAMIC                 : 'DYNAMIC';
EACH                    : 'EACH';
EBCDIC                  : 'EBCDIC';
ELSE                    : 'ELSE';
ELSEIF                  : 'ELSEIF';
EMPTY                   : 'EMPTY';
ENABLE                  : 'ENABLE';
ENCRYPTION              : 'ENCRYPTION';
END                     : 'END';
ENDING                  : 'ENDING';
ENFORCED                : 'ENFORCED';
ESCAPE                  : 'ESCAPE';
EVENT                   : 'EVENT';
EVERY                   : 'EVERY';
EXCEPT                  : 'EXCEPT';
EXCEPTION               : 'EXCEPTION';
EXCLUDE                 : 'EXCLUDE';
EXCLUDING               : 'EXCLUDING';
EXCLUSIVE               : 'EXCLUSIVE';
EXECUTE                 : 'EXECUTE';
EXISTS                  : 'EXISTS';
EXIT                    : 'EXIT';
EXPLAIN                 : 'EXPLAIN';
EXPORT                  : 'EXPORT';
EXPRESSION              : 'EXPRESSION';
EXTEND                  : 'EXTEND';
EXTENDED                : 'EXTENDED';
EXTENTSIZE              : 'EXTENTSIZE';
EXTERNAL                : 'EXTERNAL';
FALSE                   : 'FALSE';
FEDERATED               : 'FEDERATED';
FEDERATED_TOOL          : 'FEDERATED_TOOL';
FENCED                  : 'FENCED';
FETCH                   : 'FETCH';
FILE                    : 'FILE';
FINAL                   : 'FINAL';
FIRST                   : 'FIRST';
FOLLOWING               : 'FOLLOWING';
FOR                     : 'FOR';
FOREIGN                 : 'FOREIGN';
FOUND                   : 'FOUND';
FROM                    : 'FROM';
FULL                    : 'FULL';
FUNCTION                : 'FUNCTION';
GENERATED               : 'GENERATED';
GENERIC                 : 'GENERIC';
GET                     : 'GET';
GLOBAL                  : 'GLOBAL';
GOTO                    : 'GOTO';
GRANT                   : 'GRANT';
GROUP                   : 'GROUP';
GROUPING                : 'GROUPING';
HANDLER                 : 'HANDLER';
HASH                    : 'HASH';
HASHING                 : 'HASHING';
HAVING                  : 'HAVING';
HIDDEN_                 : 'HIDDEN';
HIERARCHY               : 'HIERARCHY';
HIGH                    : 'HIGH';
HINT                    : 'HINT';
HISTORY                 : 'HISTORY';
HOLD                    : 'HOLD';
HOUR                    : 'HOUR';
HOURS                   : 'HOURS';
ID                      : 'ID';
IDENTITY                : 'IDENTITY';
IF                      : 'IF';
IGNORE                  : 'IGNORE';
IMMEDIATE               : 'IMMEDIATE';
IMPLICIT                : 'IMPLICIT';
IMPLICITLY              : 'IMPLICITLY';
IMPORT                  : 'IMPORT';
IN                      : 'IN';
INCLUDE                 : 'INCLUDE';
INCLUDING               : 'INCLUDING';
INCLUSIVE               : 'INCLUSIVE';
INCREASESIZE            : 'INCREASESIZE';
INCREMENT               : 'INCREMENT';
INCREMENTAL             : 'INCREMENTAL';
INDEX                   : 'INDEX';
INDEXES                 : 'INDEXES';
INDICATOR               : 'INDICATOR';
INHERIT                 : 'INHERIT';
INITIALLY               : 'INITIALLY';
INITIALSIZE             : 'INITIALSIZE';
INLINE                  : 'INLINE';
INNER                   : 'INNER';
INOUT                   : 'INOUT';
INPUT                   : 'INPUT';
INSENSITIVE             : 'INSENSITIVE';
INSERT                  : 'INSERT';
INSTANTIABLE            : 'INSTANTIABLE';
INSTEAD                 : 'INSTEAD';
INTEGRITY               : 'INTEGRITY';
INTERSECT               : 'INTERSECT';
INTO                    : 'INTO';
IS                      : 'IS';
ITERATE                 : 'ITERATE';
JOIN                    : 'JOIN';
KEEP                    : 'KEEP';
KEY                     : 'KEY';
KEYS                    : 'KEYS';
LABEL                   : 'LABEL';
LANGUAGE                : 'LANGUAGE';
LARGE                   : 'LARGE';
LAST                    : 'LAST';
LATERAL                 : 'LATERAL';
LEADING                 : 'LEADING';
LEAVE                   : 'LEAVE';
LEFT                    : 'LEFT';
LENGTH                  : 'LENGTH';
LEVEL                   : 'LEVEL';
LEVEL2                  : 'LEVEL2';
LIKE                    : 'LIKE';
LIMIT                   : 'LIMIT';
LOAD                    : 'LOAD';
LOCAL                   : 'LOCAL';
LOCALE                  : 'LOCALE';
LOCATOR                 : 'LOCATOR';
LOCATORS                : 'LOCATORS';
LOCK                    : 'LOCK';
LOCKED                  : 'LOCKED';
LOCKS                   : 'LOCKS';
LOCKSIZE                : 'LOCKSIZE';
LOGGED                  : 'LOGGED';
LONG                    : 'LONG';
LONGVAR                 : 'LONGVAR';
LOOP                    : 'LOOP';
LOW                     : 'LOW';
LOWER                   : 'LOWER';
MAIN                    : 'MAIN';
MAINTAINED              : 'MAINTAINED';
MANAGED                 : 'MANAGED';
MARK                    : 'MARK';
MASK                    : 'MASK';
MATCHED                 : 'MATCHED';
MATCHING                : 'MATCHING';
MATERIALIZED            : 'MATERIALIZED';
MAX                     : 'MAX';
MAXSIZE                 : 'MAXSIZE';
MAXVALUE                : 'MAXVALUE';
MDC                     : 'MDC';
MERGE                   : 'MERGE';
MICROSECOND             : 'MICROSECOND';
MICROSECONDS            : 'MICROSECONDS';
MINPCTUSED              : 'MINPCTUSED';
MINUS                   : 'MINUS';
MINUTE                  : 'MINUTE';
MINUTES                 : 'MINUTES';
MINVALUE                : 'MINVALUE';
MISSING                 : 'MISSING';
MIXED                   : 'MIXED';
MODE_                   : 'MODE';
MODIFIES                : 'MODIFIES';
MODULE                  : 'MODULE';
MONITOR                 : 'MONITOR';
MONTH                   : 'MONTH';
MONTHS                  : 'MONTHS';
NAME                    : 'NAME';
NATIONAL                : 'NATIONAL';
NEW                     : 'NEW';
NEW_TABLE               : 'NEW_TABLE';
NEXT                    : 'NEXT';
NEXTVAL                 : 'NEXTVAL';
NICKNAME                : 'NICKNAME';
NO                      : 'NO';
NOCACHE                 : 'NOCACHE';
NOCYCLE                 : 'NOCYCLE';
NODEGROUP               : 'NODEGROUP';
NOMAXVALUE              : 'NOMAXVALUE';
NOMINVALUE              : 'NOMINVALUE';
NONE                    : 'NONE';
NOORDER                 : 'NOORDER';
NOT                     : 'NOT';
NULL                    : 'NULL';
NULLS                   : 'NULLS';
NUMBLOCKPAGES           : 'NUMBLOCKPAGES';
OBJECT                  : 'OBJECT';
OF                      : 'OF';
OFF                     : 'OFF';
OFFSET                  : 'OFFSET';
OLD                     : 'OLD';
OLD_TABLE               : 'OLD_TABLE';
ON                      : 'ON';
ONCE                    : 'ONCE';
ONLINE                  : 'ONLINE';
ONLY                    : 'ONLY';
OPEN                    : 'OPEN';
OPTIMIZATION            : 'OPTIMIZATION';
OPTIMIZE                : 'OPTIMIZE';
OPTION                  : 'OPTION';
OR                      : 'OR';
ORDER                   : 'ORDER';
ORDINALITY              : 'ORDINALITY';
ORGANIZE                : 'ORGANIZE';
OUT                     : 'OUT';
OUTCOME                 : 'OUTCOME';
OUTER                   : 'OUTER';
OUTPUT                  : 'OUTPUT';
OVER                    : 'OVER';
OVERFLOW                : 'OVERFLOW';
OVERHEAD                : 'OVERHEAD';
OVERLAPS                : 'OVERLAPS';
OVERRIDING              : 'OVERRIDING';
PACKAGE                 : 'PACKAGE';
PAGE                    : 'PAGE';
PAGESIZE                : 'PAGESIZE';
PARALLEL                : 'PARALLEL';
PARAMETER               : 'PARAMETER';
PARTITION               : 'PARTITION';
PARTITIONED             : 'PARTITIONED';
PARTITIONING            : 'PARTITIONING';
PASSING                 : 'PASSING';
PASSWORD                : 'PASSWORD';
PATH                    : 'PATH';
PCTFREE                 : 'PCTFREE';
PERCENT                 : 'PERCENT';
PERIOD                  : 'PERIOD';
PERMISSION              : 'PERMISSION';
PIPE                    : 'PIPE';
PIPELINED               : 'PIPELINED';
PLAN                    : 'PLAN';
POLICY                  : 'POLICY';
PORTION                 : 'PORTION';
PRECEDING               : 'PRECEDING';
PRECISION               : 'PRECISION';
PREFETCHSIZE            : 'PREFETCHSIZE';
PREPARE                 : 'PREPARE';
PRESERVE                : 'PRESERVE';
PREVIOUS                : 'PREVIOUS';
PREVVAL                 : 'PREVVAL';
PRIMARY                 : 'PRIMARY';
PRIOR                   : 'PRIOR';
PRIVILEGES              : 'PRIVILEGES';
PROCEDURE               : 'PROCEDURE';
PROFILE                 : 'PROFILE';
PROGRAM                 : 'PROGRAM';
PRUNE                   : 'PRUNE';
PUBLIC                  : 'PUBLIC';
PUBLISH                 : 'PUBLISH';
QUERIES                 : 'QUERIES';
QUERY                   : 'QUERY';
QUERYNO                 : 'QUERYNO';
QUERYTAG                : 'QUERYTAG';
RANDOM                  : 'RANDOM';
RANGE                   : 'RANGE';
RATE                    : 'RATE';
READ                    : 'READ';
READS                   : 'READS';
REBALANCE               : 'REBALANCE';
RECOVERY                : 'RECOVERY';
REDUCE                  : 'REDUCE';
REF                     : 'REF';
REFERENCES              : 'REFERENCES';
REFERENCING             : 'REFERENCING';
REFRESH                 : 'REFRESH';
REGISTERS               : 'REGISTERS';
REGULAR                 : 'REGULAR';
RELEASE                 : 'RELEASE';
RENAME                  : 'RENAME';
REOPT                   : 'REOPT';
REORG                   : 'REORG';
REORGCHK                : 'REORGCHK';
REPEAT                  : 'REPEAT';
REPEATABLE              : 'REPEATABLE';
REPLACE                 : 'REPLACE';
REPLICATION             : 'REPLICATION';
REQUIRE                 : 'REQUIRE';
RESET                   : 'RESET';
RESIGNAL                : 'RESIGNAL';
RESIZE                  : 'RESIZE';
RESTART                 : 'RESTART';
RESTRICT                : 'RESTRICT';
RESULT                  : 'RESULT';
RESULT_SET_LOCATOR      : 'RESULT_SET_LOCATOR';
RESUME                  : 'RESUME';
RETAIN                  : 'RETAIN';
RETURN                  : 'RETURN';
RETURNING               : 'RETURNING';
RETURNS                 : 'RETURNS';
REUSE                   : 'REUSE';
REVERSE                 : 'REVERSE';
REVOKE                  : 'REVOKE';
RIGHT                   : 'RIGHT';
ROLE                    : 'ROLE';
ROLLBACK                : 'ROLLBACK';
ROLLOUT                 : 'ROLLOUT';
ROLLUP                  : 'ROLLUP';
ROUNDING                : 'ROUNDING';
ROW                     : 'ROW';
ROWS                    : 'ROWS';
RR                      : 'RR';
RS                      : 'RS';
RUNSTATS                : 'RUNSTATS';
SAMPLED                 : 'SAMPLED';
SAVEPOINT               : 'SAVEPOINT';
SBCS                    : 'SBCS';
SCANS                   : 'SCANS';
SCHEMA                  : 'SCHEMA';
SCOPE                   : 'SCOPE';
SCRATCHPAD              : 'SCRATCHPAD';
SECOND                  : 'SECOND';
SECONDS                 : 'SECONDS';
SECURED                 : 'SECURED';
SECURITY                : 'SECURITY';
SELECT                  : 'SELECT';
SELECTION               : 'SELECTION';
SEQUENCE                : 'SEQUENCE';
SERVER                  : 'SERVER';
SERVICE                 : 'SERVICE';
SESSION                 : 'SESSION';
SET                     : 'SET';
SETS                    : 'SETS';
SHARE                   : 'SHARE';
SIBLINGS                : 'SIBLINGS';
SIGNAL                  : 'SIGNAL';
SIZE                    : 'SIZE';
SKIP_                   : 'SKIP';
SNAPSHOT                : 'SNAPSHOT';
SOME                    : 'SOME';
SOURCE                  : 'SOURCE';
SPECIAL                 : 'SPECIAL';
SPECIFIC                : 'SPECIFIC';
SPECIFICATION           : 'SPECIFICATION';
SPLIT                   : 'SPLIT';
SQL                     : 'SQL';
SQLCODE                 : 'SQLCODE';
SQLEXCEPTION            : 'SQLEXCEPTION';
SQLID                   : 'SQLID';
SQLSTATE                : 'SQLSTATE';
SQLWARNING              : 'SQLWARNING';
STACKED                 : 'STACKED';
STAGING                 : 'STAGING';
START                   : 'START';
STARTING                : 'STARTING';
STATEMENT               : 'STATEMENT';
STATIC                  : 'STATIC';
STATISTICS              : 'STATISTICS';
STOGROUP                : 'STOGROUP';
STOP                    : 'STOP';
STORAGE                 : 'STORAGE';
STRIPE                  : 'STRIPE';
STYLE                   : 'STYLE';
SUB                     : 'SUB';
SUMMARY                 : 'SUMMARY';
SUSPEND                 : 'SUSPEND';
SWITCH                  : 'SWITCH';
SYMMETRIC               : 'SYMMETRIC';
SYNONYM                 : 'SYNONYM';
SYSTEM                  : 'SYSTEM';
SYSTEM_TIME             : 'SYSTEM_TIME';
TABLE                   : 'TABLE';
TABLESAMPLE             : 'TABLESAMPLE';
TABLESPACE              : 'TABLESPACE';
TABLESPACES             : 'TABLESPACES';
TAG                     : 'TAG';
TEMPORAL                : 'TEMPORAL';
TEMPORARY               : 'TEMPORARY';
TERMINATE               : 'TERMINATE';
THEN                    : 'THEN';
THREADSAFE              : 'THREADSAFE';
TIME                    : 'TIME';
TIMEOUT                 : 'TIMEOUT';
TIMESTAMP               : 'TIMESTAMP';
TO                      : 'TO';
TOKEN                   : 'TOKEN';
TRAILING                : 'TRAILING';
TRANSACTION             : 'TRANSACTION';
TRANSFERRATE            : 'TRANSFERRATE';
TRANSFORM               : 'TRANSFORM';
TRIGGER                 : 'TRIGGER';
TRIGGERS                : 'TRIGGERS';
TRUE                    : 'TRUE';
TRUNCATE                : 'TRUNCATE';
TRUSTED                 : 'TRUSTED';
TYPE                    : 'TYPE';
TYPES                   : 'TYPES';
UNBOUNDED               : 'UNBOUNDED';
UNCHECKED               : 'UNCHECKED';
UNDER                   : 'UNDER';
UNDO                    : 'UNDO';
UNICODE                 : 'UNICODE';
UNION                   : 'UNION';
UNIQUE                  : 'UNIQUE';
UNKNOWN                 : 'UNKNOWN';
UNNEST                  : 'UNNEST';
UNTIL                   : 'UNTIL';
UPDATE                  : 'UPDATE';
UR                      : 'UR';
USAGE                   : 'USAGE';
USE                     : 'USE';
USER                    : 'USER';
USING                   : 'USING';
VALUE                   : 'VALUE';
VALUES                  : 'VALUES';
VARCHAR                 : 'VARCHAR';
VARGRAPHIC              : 'VARGRAPHIC';
VARIABLE                : 'VARIABLE';
VARYING                 : 'VARYING';
VERSIONING              : 'VERSIONING';
VIEW                    : 'VIEW';
VOLATILE                : 'VOLATILE';
WAIT                    : 'WAIT';
WATER                   : 'WATER';
WHEN                    : 'WHEN';
WHERE                   : 'WHERE';
WHILE                   : 'WHILE';
WITH                    : 'WITH';
WITHIN                  : 'WITHIN';
WITHOUT                 : 'WITHOUT';
WORK                    : 'WORK';
WORKLOAD                : 'WORKLOAD';
WRAPPER                 : 'WRAPPER';
WRITE                   : 'WRITE';
XMLPARSE                : 'XMLPARSE';
YEAR                    : 'YEAR';
YEARS                   : 'YEARS';
YES                     : 'YES';
ZONE                    : 'ZONE';
// ----- end generated: keywords -----

// ============================================================================
// Literals
// ============================================================================

// '...' with '' as the escaped quote
STRING              : '\'' (~'\'' | '\'\'')* '\'';
// X'..' hexadecimal character string, BX'..' binary string
HEX_STRING          : 'X\'' [0-9A-F \t\r\n]* '\'';
BINARY_STRING       : 'BX\'' [0-9A-F \t\r\n]* '\'';
// G'..' and N'..' graphic strings
GRAPHIC_STRING      : [GN] '\'' (~'\'' | '\'\'')* '\'';
// GX'..' and UX'..' hexadecimal graphic strings
HEX_GRAPHIC_STRING  : [GU] 'X\'' [0-9A-F \t\r\n]* '\'';
// U&'..' Unicode string
UNICODE_STRING      : 'U&\'' (~'\'' | '\'\'')* '\'';

FLOAT_LITERAL
    : DIGITS ('.' DIGITS?)? EXPONENT
    | '.' DIGITS EXPONENT
    ;
DECIMAL_LITERAL
    : DIGITS '.' DIGITS?
    | '.' DIGITS
    ;
INTEGER_LITERAL     : DIGITS;

fragment DIGITS     : [0-9]+;
fragment EXPONENT   : 'E' [+-]? [0-9]+;

// ============================================================================
// Identifiers
// ============================================================================

// Ordinary identifiers: letters (including @, # and $), digits and _
IDENTIFIER          : [A-Z_@#$\p{L}] [A-Z_0-9@#$\p{L}\p{Nd}]*;
// Delimited identifiers: "..." with "" as the escaped quote
QUOTED_IDENTIFIER   : '"' (~'"' | '""')* '"';

// ============================================================================
// Operators and punctuation
// ============================================================================

CONCAT_OP       : '||';
ARROW           : '=>';
NEQ             : '<>' | '!=' | '^=' | '¬=';
LE              : '<=' | '!>' | '^>' | '¬>';
GE              : '>=' | '!<' | '^<' | '¬<';
EQ              : '=';
LT              : '<';
GT              : '>';
PLUS            : '+';
MINUS_SIGN      : '-';
STAR            : '*';
SLASH           : '/';
QUESTION        : '?';
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
