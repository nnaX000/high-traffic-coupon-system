#!/usr/bin/env bash
# 부하테스트 전: coupon_issue 비우기, Kafka·Redis 초기화. 서버 재시작은 수동.
# 사용: ./infra/reset-for-loadtest.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

echo "1) coupon_issue 비우기 + 정책 발급 카운트 초기화 (재테스트 시 Consumer가 INSERT 하도록)"
docker compose exec -T mysql mysql -uroot -prootpass coupon -e "
  TRUNCATE TABLE coupon_issue;
  UPDATE coupon_policy SET issued_quantity = 0 WHERE id = 1;
"

echo "2) Kafka 초기화"
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic coupon-issue 2>/dev/null || true

echo "3) Redis 초기화"
docker compose exec redis redis-cli FLUSHDB

echo "4) 서버를 loadtest 프로파일로 다시 실행하세요. (예: ./gradlew bootRun --args='--spring.profiles.active=loadtest')"
