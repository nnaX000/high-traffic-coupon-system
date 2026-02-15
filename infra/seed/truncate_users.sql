-- users 시드 재적재 전 테이블 비우기 (coupon_issue가 users FK 참조하므로 FK 체크 해제 후 truncate)
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE coupon_issue;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;
