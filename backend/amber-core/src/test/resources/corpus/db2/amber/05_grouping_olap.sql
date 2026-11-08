SELECT workdept, COUNT(*), SUM(salary), AVG(salary), MIN(salary), MAX(salary) FROM employee GROUP BY workdept HAVING COUNT(*) > 2;
SELECT workdept, sex, SUM(salary) FROM employee GROUP BY GROUPING SETS ((workdept, sex), (workdept), ());
SELECT workdept, job, SUM(salary), GROUPING(workdept), GROUPING(job) FROM employee GROUP BY ROLLUP (workdept, job);
SELECT workdept, job, sex, COUNT(*) FROM employee GROUP BY CUBE (workdept, job, sex);
SELECT a, b, c, SUM(x) FROM t GROUP BY a, ROLLUP (b, c);
SELECT a, b, SUM(x) FROM t GROUP BY GROUPING SETS (ROLLUP (a, b), CUBE (a), (a, b));
SELECT YEAR(sale_date) AS yr, MONTH(sale_date) AS mon, SUM(amount) FROM sales GROUP BY YEAR(sale_date), MONTH(sale_date);
SELECT empno, salary,
       ROW_NUMBER() OVER (ORDER BY salary DESC) AS rn,
       RANK() OVER (PARTITION BY workdept ORDER BY salary DESC) AS rnk,
       DENSE_RANK() OVER (PARTITION BY workdept ORDER BY salary DESC NULLS LAST) AS drnk,
       ROWNUMBER() OVER () AS rn2
FROM employee;
SELECT sale_date, amount,
       SUM(amount) OVER (ORDER BY sale_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running,
       AVG(amount) OVER (ORDER BY sale_date ROWS BETWEEN 2 PRECEDING AND 2 FOLLOWING) AS moving,
       SUM(amount) OVER (PARTITION BY region) AS region_total,
       SUM(amount) OVER (ORDER BY sale_date RANGE BETWEEN 7 PRECEDING AND CURRENT ROW) AS week_total,
       COUNT(*) OVER (ORDER BY sale_date ROWS UNBOUNDED PRECEDING) AS cnt,
       MAX(amount) OVER (ORDER BY sale_date RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS mx
FROM sales;
SELECT empno,
       LAG(salary, 1, 0) OVER (PARTITION BY workdept ORDER BY hiredate) AS prev_sal,
       LEAD(salary) OVER (ORDER BY hiredate) AS next_sal,
       FIRST_VALUE(salary) OVER (PARTITION BY workdept ORDER BY salary DESC) AS top_sal,
       LAST_VALUE(salary, 'IGNORE NULLS') OVER (PARTITION BY workdept ORDER BY salary) AS last_sal,
       NTILE(4) OVER (ORDER BY salary) AS quartile,
       PERCENT_RANK() OVER (ORDER BY salary) AS pr,
       CUME_DIST() OVER (ORDER BY salary) AS cd,
       RATIO_TO_REPORT(salary) OVER (PARTITION BY workdept) AS ratio
FROM employee;
SELECT workdept, LISTAGG(lastname, ', ') WITHIN GROUP (ORDER BY lastname) AS names FROM employee GROUP BY workdept;
SELECT workdept, LISTAGG(DISTINCT job, ';') WITHIN GROUP (ORDER BY job DESC) FROM employee GROUP BY workdept;
SELECT LISTAGG(name) FROM t;
SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY salary), MEDIAN(salary), PERCENTILE_DISC(0.9) WITHIN GROUP (ORDER BY bonus DESC) FROM employee;
SELECT workdept, ARRAY_AGG(empno ORDER BY salary DESC) FROM employee GROUP BY workdept;
SELECT COUNT(DISTINCT workdept), COUNT_BIG(*), COUNT(ALL job), STDDEV(salary), VARIANCE(salary), CORRELATION(salary, bonus) FROM employee;
SELECT workdept, SUM(salary) FROM employee GROUP BY workdept HAVING SUM(salary) > (SELECT AVG(salary) * 5 FROM employee) ORDER BY 2 DESC;
