# ADR-001: DB 락 전략 선택 (낙관적 락 vs 비관적 락)

DATE: 2026-01-XX

## Status
Accepted

## Decision Drivers
- 대규모 쿠폰 발급 시스템에서 DB 레벨 동시성 제어 필요
- 쿠폰 초과 발급 방지 (정합성 보장)
- 높은 처리량 요구사항
- DB 커넥션 풀 고갈 방지

## Context
현재 시스템은 Redis + Kafka 비동기 처리 아키텍처를 사용하고 있다.
Redis에서 사전 필터링을 통해 대부분의 요청을 차단하고,
Kafka Consumer에서 실제 DB 저장을 수행한다.

이 구조에서 DB 저장 시점에 어떤 락 전략을 사용할지 결정해야 한다.

## Considered Options

### 1. 비관적 락 (Pessimistic Lock)
- `@Lock(LockModeType.PESSIMISTIC_WRITE)` 사용
- SELECT ... FOR UPDATE로 락 획득
- 다른 트랜잭션은 락이 풀릴 때까지 대기

**장점:**
- 데이터 일관성 보장
- 동시 수정 충돌 방지
- 구현 단순

**단점:**
- 성능 저하: 대량 동시 요청 시 대기 증가
- 데드락 위험
- 확장성 제한: DB 커넥션 고갈 가능

### 2. 낙관적 락 (Optimistic Lock)
- `@Version` 필드 사용
- UPDATE ... WHERE id=? AND version=? 형태
- 버전 불일치 시 OptimisticLockException 발생

**장점:**
- 성능 우수: 락 대기 없음
- 높은 처리량: 동시 읽기 가능
- 확장성 좋음

**단점:**
- 충돌 시 재시도 로직 필요
- 재시도 로직 복잡도 증가
- 충돌 빈도가 높으면 성능 저하 가능

## Decision
**비관적 락을 사용하고, Kafka + Consumer 제한으로 커넥션 풀 보호**

### 이유
1. **정합성 보장의 확실성**: 비관적 락은 구현이 단순하고 확실한 정합성 보장
   - 재시도 로직 불필요
   - 충돌 시 자동으로 순차 처리

2. **Kafka로 커넥션 풀 보호**: 비관적 락의 단점(커넥션 풀 고갈)을 구조적으로 해결
   - Consumer 개수를 제한하여 동시 DB 트랜잭션 수 제한
   - 커넥션 풀 허용치 이하로 설정하여 풀 고갈 불가능

3. **Redis로 사전 차단**: 대량 트래픽을 API 레벨에서 차단
   - countReq로 쓸데없는 요청을 DB까지 가지 않도록 방어
   - Kafka로 전달되는 메시지는 최대 100개 (LIMIT)

4. **원자성 보장**: 비관적 락으로 확실한 원자성 보장
   - 락으로 보호하여 동시 수정 충돌 방지
   - 트랜잭션 단위로 원자성 보장

## Implementation

### 비관적 락 구현 예시
```java
@Entity
@Table(name = "coupon_policy")
public class CouponPolicy {
    private int issuedQuantity;  // 현재 발급 수량
    
    public boolean canIssue() {
        return issuedQuantity < totalQuantity;
    }
    
    public void incrementIssuedQuantity() {
        if (!canIssue()) {
            throw new CouponSoldOutException();
        }
        this.issuedQuantity++;
    }
}
```

### Repository에 비관적 락 메서드 추가
```java
public interface CouponPolicyRepository extends JpaRepository<CouponPolicy, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cp FROM CouponPolicy cp WHERE cp.id = :id")
    Optional<CouponPolicy> findByIdWithLock(@Param("id") Long id);
}
```

### Consumer에서 비관적 락 사용
```java
@KafkaListener(topics = "coupon-issue", concurrency = "10") // 최대 10개 동시 처리
public void consume(CouponIssueEvent event) {
    // 비관적 락으로 정합성 보장
    // Consumer 개수가 제한되어 있어 커넥션 풀 고갈 불가능
    CouponPolicy policy = repository.findByIdWithLock(event.getCouponId())
        .orElseThrow();
    
    if (policy.canIssue()) {
        policy.incrementIssuedQuantity();
        repository.save(policy);
        saveCouponIssue(event);
        
        // 실제 발급 성공 시 Redis count 증가
        redisTemplate.opsForValue()
            .increment("coupon:" + event.getCouponId() + ":count");
    } else {
        throw new CouponSoldOutException();
    }
}
```

## Consequences

### Pros
- **확실한 정합성 보장**: 비관적 락으로 동시 수정 충돌 방지
- **구현 단순**: 재시도 로직 불필요
- **커넥션 풀 보호**: Kafka + Consumer 제한으로 풀 고갈 방지
- **Redis 사전 차단**: 대량 트래픽을 DB까지 가지 않도록 방어

### Cons / Trade-offs
- **처리 지연**: 비동기 처리로 인한 지연 발생
- **Kafka 운영 복잡도**: 메시징 시스템 운영 필요
- **Consumer 개수 제한**: 커넥션 풀 크기 고려 필요
- **최종 일관성**: Eventual Consistency (비동기 처리)

## Notes
- **비관적 락의 단점(커넥션 풀 고갈)을 Kafka + Consumer 제한으로 구조적으로 해결**
- **Redis countReq는 "게이트" 역할**: 정확한 재고가 아닌 사전 차단용
- **정합성의 최종 방어선은 DB 비관적 락**: Redis는 성능/커넥션 풀 보호용
- **Consumer 개수는 커넥션 풀 크기의 50~70% 권장**: 다른 작업을 위한 여유 확보
