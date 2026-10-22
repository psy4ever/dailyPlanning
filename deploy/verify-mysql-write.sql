-- Isolated deployment test. CREATE deliberately fails if the table exists.
-- On reruns, inspect the previous test before removing its table.
SELECT VERSION() AS mysql_version, DATABASE() AS database_name, CURRENT_USER() AS account;

CREATE TABLE _deploy_verify_20261021 (
    id INT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;
INSERT INTO _deploy_verify_20261021 VALUES (1, '云端存储测试', 12.34);
COMMIT;

START TRANSACTION;
INSERT INTO _deploy_verify_20261021 VALUES (2, '这条应该回滚', 99.00);
ROLLBACK;

SELECT id, title, amount FROM _deploy_verify_20261021 ORDER BY id;
SELECT COUNT(*) = 1 AS committed_ok FROM _deploy_verify_20261021
WHERE id = 1 AND title = '云端存储测试' AND amount = 12.34;
SELECT COUNT(*) = 0 AS rollback_ok FROM _deploy_verify_20261021 WHERE id = 2;
