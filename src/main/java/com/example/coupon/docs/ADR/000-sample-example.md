# ADR-000: Coupon Issuance Architecture for High Traffic

DATE: 2026-01-XX

## Status
Accepted

## Decision Drivers
- 쿠폰 초과 발급은 반드시 방지되어야 함
- 수만~수십만 동시 요청을 처리해야 함
- DB 락 기반 접근은 성능 병목 가능성 존재
- 장애 발생 시에도 시스템 전체가 멈추지 않아야 함

## Context
쿠폰 발급 이벤트는 특정 시점에 트래픽이 폭발적으로 증가하는 특성을 가진다.
기존 DB 기반 수량 관리 방식(비관적/낙관적 락)은 대량 동시 요청 환경에서
성능 저하 및 병목을 유발할 가능성이 높다.

또한 쿠폰 발급 로직은 단일 API 요청 내에서 즉시 처리하기보다,
비동기 이벤트 기반으로 분리함으로써 시스템 안정성과 확장성을 확보할 필요가 있다.

## Considered Options
1. DB 비관적 락을 통한 수량 제어
2. DB 낙관적 락(version column) 기반 수량 제어
3. Redis atomic counter 기반 수량 제어 + 비동기 처리

## Decision
Redis의 atomic INCR 연산을 활용하여 쿠폰 발급 요청 수를 제어하고,
비동기 메시징 시스템(Kafka)을 통해 실제 발급 처리를 수행한다.

DB는 최종 발급 결과를 저장하는 용도로만 사용하며,
동시성 제어는 DB 외부(Redis)에서 수행한다.

## Consequences

### Pros
- DB 락 제거로 높은 처리량 확보
- Redis atomic 연산으로 경합 상태 방지
- 비동기 처리로 API 응답 지연 최소화
- 장애 발생 시에도 요청 유입 차단 가능

### Cons / Trade-offs
- Redis 및 메시징 시스템 운영 복잡도 증가
- 분산 환경에서 보상 트랜잭션 필요
- 최종 발급 결과는 eventual consistency를 가짐

## Follow-up Decisions
- Redis 이중 카운터 전략 도입 여부
- 메시지 재처리 및 DLQ 적용 여부
- Idempotency 키를 통한 중복 요청 방지