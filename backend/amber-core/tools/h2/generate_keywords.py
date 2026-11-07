#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0.
"""Regenerates the keyword section of H2Lexer.g4 and the non_reserved_keyword rule of H2Parser.g4.

Usage: python3 tools/generate_keywords.py   (run from the project root)

Every upper-case token referenced by H2Parser.g4 that is not a literal/operator token becomes a
keyword token 'NAME' in H2Lexer.g4. All keywords except RESERVED and the words listed in the
parser rule alias_restricted_keyword are emitted into non_reserved_keyword, so they stay usable
as identifiers. Edit RESERVED below to reserve or un-reserve a word.
"""
import re, sys, os
d = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
    'src', 'main', 'antlr4', 'com', 'github', 'amber', 'dialect', 'h2', 'grammar')
P = d + '/H2Parser.g4'
L = d + '/H2Lexer.g4'
p = open(P).read()
marker = '// @@NON_RESERVED@@'
body = p.split(marker)[0] if marker in p else p.split('// ----- generated: non_reserved_keyword -----')[0]
# strip comments and literal strings
b = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
b = re.sub(r'//[^\n]*', '', b)
b = re.sub(r"'[^']*'", '', b)
toks = set(re.findall(r'\b([A-Z][A-Z0-9_]*)\b', b))
NONKW = {'IDENTIFIER','QUOTED_IDENTIFIER','BACKTICK_IDENTIFIER','STRING','NATIONAL_STRING','UNICODE_STRING',
 'BINARY_STRING','DOLLAR_STRING','INTEGER_LITERAL','DECIMAL_LITERAL','HEX_LITERAL','PARAMETER','NAMED_PARAMETER','VARIABLE',
 'SEMI','COMMA','DOT','LPAREN','RPAREN','LBRACKET','RBRACKET','EQ','NEQ','LT','LE','GT','GE','OVERLAP','PLUS',
 'MINUS_SIGN','STAR','SLASH','PERCENT_SIGN','CONCAT','TILDE','COLON','COLON_EQ','EOF'}
kws = sorted(toks - NONKW)
RESERVED = set('''ALL AND ANY ARRAY AS ASYMMETRIC AUTHORIZATION BETWEEN BOTH CASE CAST CHECK CONSTRAINT CROSS
CURRENT_CATALOG CURRENT_DATE CURRENT_PATH CURRENT_ROLE CURRENT_SCHEMA CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER
DEFAULT DISTINCT ELSE END EXCEPT EXISTS FALSE FETCH FOR FOREIGN FROM FULL GROUP HAVING IF ILIKE IN INNER INTERSECT
INTERVAL IS JOIN LEADING LEFT LIKE LIMIT LOCALTIME LOCALTIMESTAMP MINUS NATURAL NOT NULL OFFSET ON OR ORDER PRIMARY
QUALIFY REGEXP RIGHT ROW SELECT SESSION_USER SET SOME SYMMETRIC SYSTEM_USER TABLE THEN TO TRAILING TRUE UESCAPE
UNION UNIQUE UNKNOWN USING VALUES WHEN WHERE WINDOW WITH ROLLUP CUBE UNBOUNDED LATERAL'''.split())
m = re.search(r'alias_restricted_keyword\s*:(.*?);', body, re.S)
RESTRICTED = set(re.findall(r'[A-Z_][A-Z0-9_]*', m.group(1)))
nonres = [k for k in kws if k not in RESERVED and k not in RESTRICTED]
missing = RESERVED - set(kws)
if missing: print('reserved but unused:', sorted(missing))
lines = []
for i in range(0, len(nonres), 6):
    lines.append(' | '.join(nonres[i:i+6]))
rule = ('// ----- generated: non_reserved_keyword -----\n'
        '// Keywords that H2 does not reserve (or that this grammar can accept unambiguously)\n'
        '// and that therefore remain usable as identifiers.\n'
        'non_reserved_keyword\n    : ' + '\n    | '.join(lines) + '\n    ;\n')
open(P, 'w').write(body.rstrip() + '\n\n' + rule)
l = open(L).read()
start = '// ----- generated: keywords -----'
end = '// ----- end generated: keywords -----'
def text(k):
    return {'SKIP_': 'SKIP'}.get(k, k)
kwlines = '\n'.join(f"{k:<24}: '{text(k)}';" for k in kws)
pre, rest = l.split(start)
_, post = rest.split(end)
open(L, 'w').write(pre + start + '\n' + kwlines + '\n' + end + post)
print(len(kws), 'keywords;', len(nonres), 'non-reserved')
