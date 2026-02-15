-- users 시드 CSV 적재 (컨테이너 내 경로 /seed/data/users.csv)
-- coupon 유저 권한으로 실행 가능하도록 SET SESSION 제거
LOAD DATA INFILE '/seed/data/users.csv'
INTO TABLE users
FIELDS TERMINATED BY '\t'
LINES TERMINATED BY '\n'
(id, user_id, username, password, email);
