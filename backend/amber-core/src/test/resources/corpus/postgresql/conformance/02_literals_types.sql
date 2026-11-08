-- Literals and data types
SELECT 'simple string', E'escaped\ttab\n', U&'d\0061t\+000061', $$dollar quoted 'x'$$, $tag$tagged $$ body$tag$;
SELECT B'1010', X'1F', 1e10, 1.5E-3, .5, 42::bigint, 0x1F, 0o17, 0b101, 1_000_000;
SELECT 'line one'
       'line two';
SELECT TRUE, FALSE, NULL, 'yes'::boolean, 't'::bool;
SELECT DATE '2024-01-31', TIME '12:30:00', TIMESTAMP '2024-01-31 10:00:00', TIMESTAMPTZ '2024-01-31 10:00:00+02';
SELECT INTERVAL '1 day 2 hours', INTERVAL '3' MONTH, INTERVAL '1-2' YEAR TO MONTH, '5 minutes'::interval;
SELECT CAST('42' AS integer), '42'::int4, '3.14'::numeric(10,2), CAST(now() AS date);
SELECT 'x'::character varying(20), 'y'::varchar, 'z'::char(3), 'w'::text, 'q'::"char";
SELECT ARRAY[1,2,3], ARRAY[[1,2],[3,4]], '{1,2,3}'::int[], ARRAY[]::text[];
SELECT (ARRAY[10,20,30])[2], (ARRAY[10,20,30])[1:2], array_length(ARRAY[1,2], 1);
SELECT '[1,10)'::int4range, int8range(1, 100, '[]'), tstzrange(now(), now() + interval '1 day');
SELECT '{[1,3), [7,9)}'::int4multirange;
SELECT '{"a":1}'::json, '{"b":[1,2]}'::jsonb, '<x>1</x>'::xml;
SELECT 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid, '192.168.0.1'::inet, '10.0.0.0/8'::cidr, '08:00:2b:01:02:03'::macaddr;
SELECT '(1,2)'::point, '((0,0),(1,1))'::box, 'fat & rat'::tsquery, 'a fat cat'::tsvector;
SELECT 12.5::money, '\xDEADBEEF'::bytea;
SELECT timestamp with time zone '2024-01-01 00:00:00+00', time without time zone '10:00';
SELECT 1::double precision, 2::real, 3::smallint, 4::decimal(5,1), 5::float8, 6::bit varying(8);
SELECT ROW(1, 'a', true), (1, 'b');
SELECT 'today'::date, 'infinity'::timestamp, '-infinity'::date;
SELECT '2024-01-01'::date + 7, now() - interval '1 hour', current_date, current_timestamp(3), localtimestamp;
SELECT 'abc' COLLATE "C", 'Ab' < 'ab' COLLATE "en_US";
SELECT int4 '42', float8 '1.5', jsonb '{"x":1}';
CREATE TABLE type_showcase (
  c_smallint smallint, c_int integer, c_big bigint, c_num numeric(12,4), c_real real, c_dbl double precision,
  c_serial serial, c_bigserial bigserial, c_money money, c_text text, c_vc varchar(100), c_char char(2),
  c_bytea bytea, c_ts timestamp(3) without time zone, c_tstz timestamptz, c_date date, c_time time with time zone,
  c_iv interval day to second, c_bool boolean, c_uuid uuid, c_json json, c_jsonb jsonb, c_xml xml,
  c_arr integer[], c_arr2 text[][], c_arr3 int ARRAY[4], c_range daterange, c_inet inet, c_bit bit(8), c_tsv tsvector
);
DROP TABLE type_showcase;
