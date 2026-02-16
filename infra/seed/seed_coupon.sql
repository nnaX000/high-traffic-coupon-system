-- 부하 테스트용 쿠폰 id=1 및 정책 시드 (docker compose down 후 DB 비어 있을 때 사용)
-- 실행: docker compose exec -T mysql mysql -uroot -prootpass coupon < infra/seed/seed_coupon.sql

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE coupon_issue;
TRUNCATE TABLE coupon;
TRUNCATE TABLE coupon_policy;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO coupon_policy (id, total_quantity, start_at, end_at, active, issued_quantity)
VALUES (1, 500, '2020-01-01 00:00:00', '2030-12-31 23:59:59', 1, 0);

INSERT INTO coupon (id, name, discount_amount, policy_id)
VALUES (1, '부하테스트 쿠폰', 1000, 1);
