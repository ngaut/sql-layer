SELECT x, (SELECT MAX(x) FROM t1) FROM t1
