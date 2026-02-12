#!/usr/bin/env bash

# 목적: 로컬 docker-compose 환경에서 Redis 앞단에 Toxiproxy 프록시를 생성한다.
# 입력: 없음 (toxiproxy 컨테이너가 localhost:8474, redis 컨테이너가 docker 네트워크에서 redis:6379 라고 가정)
# 출력: 생성된 프록시 정보(JSON)를 stdout 으로 출력한다.
# 핵심 로직: Toxiproxy HTTP API(/proxies)에 curl POST 요청을 보내 프록시를 등록한다.

set -euo pipefail

TOXIPROXY_API_URL="${TOXIPROXY_API_URL:-http://localhost:8474}"

echo "[setup-redis-proxy] Creating Redis proxy on Toxiproxy..."

curl -sS -X POST "${TOXIPROXY_API_URL}/proxies" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "redis",
    "listen": "0.0.0.0:8666",
    "upstream": "redis:6379",
    "enabled": true
  }' | jq '.'

echo
echo "[setup-redis-proxy] Done. Use host: toxiproxy:8666 (docker 네트워크 내부) 또는 localhost:8666 (호스트 기준) 로 Redis 접근."

