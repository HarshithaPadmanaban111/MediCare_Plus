mysql> SHOW INDEX FROM users;
+-------+------------+------------------+--------------+-------------+-----------+-------------+----------+--------+------+------------+---------+---------------+---------+------------+
| Table | Non_unique | Key_name         | Seq_in_index | Column_name | Collation | Cardinality | Sub_part | Packed | Null | Index_type | Comment | Index_comment | Visible | Expression |
+-------+------------+------------------+--------------+-------------+-----------+-------------+----------+--------+------+------------+---------+---------------+---------+------------+
| users |          0 | PRIMARY          |            1 | user_id     | A         |           1 |     NULL |   NULL |      | BTREE      |         |               | YES     | NULL       |
| users |          0 | username         |            1 | username    | A         |           1 |     NULL |   NULL |      | BTREE      |         |               | YES     | NULL       |
| users |          0 | email            |            1 | email       | A         |           1 |     NULL |   NULL |      | BTREE      |         |               | YES     | NULL       |
| users |          0 | uq_users_patient |            1 | patient_id  | A         |           1 |     NULL |   NULL | YES  | BTREE      |         |               | YES     | NULL       |
| users |          0 | uq_users_doctor  |            1 | doctor_id   | A         |           1 |     NULL |   NULL | YES  | BTREE      |         |               | YES     | NULL       |
+-------+------------+------------------+--------------+-------------+-----------+-------------+----------+--------+------+------------+---------+---------------+---------+------------+
5 rows in set (0.86 sec)

mysql>