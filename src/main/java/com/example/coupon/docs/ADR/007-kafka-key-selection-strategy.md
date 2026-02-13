# ADR-007: Kafka Key 선정 전략 (couponId 기반 파티셔닝)

DATE: 2026-02-13

## Status
Proposed

## Decision Drivers

- 쿠폰별 발급 처리 순서를 가능한 한 **요청 순서에 가깝게** 유지하고 싶음
- Kafka Consumer를 수평 확장했을 때도 **쿠폰별로 정합성 있는 처리 순서**를 보장하고 싶음
- 파티션 단위로 부하를 분산시키되, **동일 쿠폰에 대한 이벤트는 항상 같은 파티션**으로 보내고 싶음

## Context

쿠폰 발급 시스템은 다음과 같은 플로우를 가진다.

- API 서버에서 Redis stock 선차감
- Kafka 토픽(`coupon-issue`)으로 발급 이벤트 전송
- Consumer(`CouponIssueService.consume`)에서 DB 비관적 락 + 발급 처리

Kafka는 동일 토픽 내에서:

- **같은 파티션 안에서는 레코드 순서를 보장**하고,
- 기본 파티셔너는 `partition = hash(key) % partitionCount` 로 파티션을 결정한다.

이를 활용하면, **같은 key를 가진 메시지들은 항상 같은 파티션으로 흐르며, 그 파티션 내에서 순서가 보장**된다.

## Considered Options

1. key를 사용하지 않고(=null key) round-robin 파티셔닝에만 의존
2. key를 `username`(userId)로 사용
3. key를 `couponId`로 사용

### Option 1: key = null

- 장점:
  - 설정이 단순하다.
  - 전체적으로 고르게 파티션 분산이 이루어질 수 있다.
- 단점:
  - **쿠폰별/유저별 순서 보장을 할 수 없음** (같은 쿠폰에 대한 발급 이벤트가 여러 파티션으로 흩어질 수 있음)
  - 특정 쿠폰에 대한 처리 순서를 추적/보장하기 어렵다.

### Option 2: key = username (userId)

- 장점:
  - **유저별 이벤트 순서**는 보장 가능 (동일 유저는 항상 같은 파티션으로 감)
  - 특정 유저에 대한 로그인/활동 이벤트를 단일 파티션에서 처리하기 좋음
- 단점:
  - 이 시스템에서는 "쿠폰별 발급 순서"가 더 중요한 관심사이며,
  - 같은 쿠폰에 대해 여러 유저가 동시에 발급 시도할 때, **쿠폰 단위의 순서를 직접적으로 보장하지 못함**.

### Option 3: key = couponId (선택)

- 장점:
  - 동일 `couponId`에 대한 모든 발급 이벤트가 항상 **같은 파티션**으로 감
  - Kafka + Consumer 쪽에서 **쿠폰 단위로 선착 순서에 가까운 처리 순서**를 보장할 수 있음
  - 쿠폰이 여러 개일수록 파티션/Consumer 인스턴스에 **부하가 자연스럽게 분산**
- 단점:
  - 유저별 순서는 별도로 고려되지 않음 (그러나 발급 비즈니스에서는 쿠폰 단위 순서가 더 중요)

## Decision

이 시스템에서는 **쿠폰별 발급 순서 보장**이 더 중요한 목표이므로,  
Kafka Producer에서 **`couponId`를 key로 사용**하기로 한다.

- Producer:

```java
CouponIssueEvent event = new CouponIssueEvent(couponId, username);
// key = couponId, value = CouponIssueEvent(JSON)
kafkaTemplate.send("coupon-issue", String.valueOf(couponId), event);
```

- Consumer:
  - 기존과 동일하게 `@KafkaListener` 기반 `consume(CouponIssueEvent event)` 사용
  - 단, Consumer 그룹의 인스턴스 수(`concurrency` 또는 replica 수)에 따라:
    - **같은 `couponId`는 항상 같은 파티션 → 같은 Consumer 인스턴스**에서 처리되므로
    - 쿠폰 단위로는 **메시지 처리 순서가 Kafka 파티션 순서와 일치**한다.

## Consequences

### Pros

- **쿠폰별 처리 순서 보장**:
  - 같은 쿠폰에 대한 발급 이벤트들이 하나의 파티션/Consumer 인스턴스로 모이므로,
  - DB 비관적 락 + Redis 카운터와 함께 사용했을 때, 쿠폰 단위로 더 직관적인 처리 순서를 유지할 수 있다.
- **부하 분산**:
  - 쿠폰이 여러 개일 경우, 서로 다른 `couponId`에 대한 이벤트는 여러 파티션/Consumer로 분산되어,
  - Kafka Consumer 인스턴스 간 부하가 보다 균등하게 분배된다.
- **운영/트러블슈팅 관점**:
  - 특정 쿠폰에 대한 이슈가 있을 때, 해당 key에 매핑된 파티션/Consumer만 집중적으로 살펴보면 되므로,
  - 로그/메트릭 분석 범위를 좁히기 쉽다.

### Cons / Trade-offs

- 유저 단위 순서는 별도로 보장되지 않는다.
- 파티션 수에 비해 `couponId`의 종류가 매우 많을 경우:
  - 여러 쿠폰이 같은 파티션을 공유하게 되며,
  - 해당 파티션의 Consumer 인스턴스에 상대적으로 더 많은 부하가 갈 수 있다.
- key 설계가 고정되므로, 나중에 다른 기준(예: userId)으로 파티셔닝 전략을 바꾸려면  
  - 토픽 재생성 또는 리파티셔닝 전략을 별도로 고민해야 한다.

## Follow-up Decisions

- 파티션 개수와 Consumer 인스턴스 수를 어느 정도로 둘지 (쿠폰 이벤트 볼륨/패턴 기반 튜닝)
- 향후 특정 use-case(예: 유저별 이벤트 처리)가 중요해질 경우,
  - 별도의 토픽에서 `key = userId` 전략을 병행할지 여부
  - 혹은 멀티 토픽/멀티 Consumer 구조로 분리할지 여부

