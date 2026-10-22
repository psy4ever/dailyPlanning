-- Run after restarting MySQL and waiting for the health check.
SELECT id, title, amount FROM _deploy_verify_20261021 ORDER BY id;
SELECT COUNT(*) = 1 AS persisted_ok FROM _deploy_verify_20261021
WHERE id = 1 AND title = '云端存储测试' AND amount = 12.34;
SELECT COUNT(*) = 0 AS rollback_still_ok FROM _deploy_verify_20261021 WHERE id = 2;
