# ADR-002: Redis 이중 카운터 전략 및 커넥션 풀 보호

DATE: 2026-01-XX

## Status
Accepted

## Decision Drivers
- DB 커넥션 풀 고갈 방지
- 대량 트래픽 사전 차단
- 정합성 보장과 성능의 균형
- 쿠폰 취소 시 재발급 가능 여부

## Context
대규모 쿠폰 발급 시스템에서 수만~수십만 동시 요청이 발생할 수 있다.
모든 요청이 DB까지 도달하면 커넥션 풀 고갈로 시스템이 마비될 수 있다.

또한 비관적 락을 사용하더라도, 커넥션 풀 허용치 이상의 트랜잭션이 동시에 실행되면
데드락이나 커넥션 풀 고갈이 발생할 수 있다.

## Considered Options

### 1. 단일 카운터 (countReq만 사용)
- Redis에서 countReq만 증가
- 발급 가능 여부 판단

**문제점:**
- 단일 카운터만 있을 경우, 여러 사용자가 거의 동시에 진입해 DB까지 도달하는 사이의 time gap 동안 서로를 보지 못해 **서버 진입 시점의 쿠폰 재고와 DB 커밋 시점의 쿠폰 재고가 달라질 수 있고, 그에 따라 초과 발급 가능성**이 존재함
- 취소/재발급, 장애 복구 등 복합 시나리리오에서 "현재 재고"와 "DB에 커밋된 발급 이력 수"를 분리해서 관리하기 어려움

### 2. 이중 카운터 (countReq + count)
- countReq: 선차감 (요청 시 증가)
- count: 실제 차감 (발급 성공 시 증가)

**장점:**
- 역할 분리 가능
- 취소 시 count만 감소하여 재발급 가능

### 3. 단일 재고 카운터 (stock)
- Redis에서 stock만 관리
- DECR로 재고 차감

**장점:**
- 단순한 구조
- 취소 시 INCR로 재고 복구

## Decision
**Redis 이중 카운터 전략(count + stock) + Kafka를 통한 커넥션 풀 보호**

### 아키텍처 (최신 설계 반영)
```
API 요청 
  → Redis stock 선차감 (DECR, 재고 확인)
  → Kafka 발행
  → Consumer (제한된 개수)
  → DB 비관적 락 (정합성 보장)
```

### 역할 분리

#### 1. stock (재고 카운터)
- **목적**: 선차감 기반 재고 관리 및 초과 발급 방지
- **증가 시점**: 초기 재고 세팅, 취소 시 재고 복구
- **감소 시점**: API 요청 시 선차감(DECR)
- **용도**: "지금 시점에 남은 발급 가능 수량" 판단

#### 2. count (현재 발급 카운터)
- **목적**: 현재까지 발급되어 살아있는 쿠폰 수 관리
- **증가 시점**: Consumer에서 DB 저장 성공 시
- **감소 시점**: 쿠폰 취소 시
- **용도**: 재발급 가능 여부 및 현재 발급 수 확인

#### 3. issued_total (누적 발급 카운터)
- **목적**: 취소 여부와 무관한 "역사적 총 발급 횟수" 추적 및 모니터링
- **증가 시점**: Consumer에서 DB 저장 성공 시
- **감소 시점**: 없음 (단조 증가)
- **용도**: DB `coupon_issue` 이력 수와 비교하여 Redis-DB 정합성 모니터링

### 핵심 원칙
1. **countReq는 "게이트" 역할만**: 정확한 재고가 아님
2. **정합성의 최종 방어선은 DB**: Redis는 성능/커넥션 풀 보호용
3. **Kafka Consumer 수 제한**: 커넥션 풀 허용치 이하로 설정

## Implementation

### API 레벨 (선차단: stock 기반)
```java
public void issueCoupon(String username, Long couponId) {
    // stock 선차감으로 재고 관리
    Long remain = redisTemplate.opsForValue()
        .decrement("coupon:" + couponId + ":stock");
    
    if (remain == null || remain < 0) {
        // 재고 부족 시 복구 후 SOLD OUT 처리
        redisTemplate.opsForValue()
            .increment("coupon:" + couponId + ":stock");
        throw new CouponSoldOutException();
    }
    
    // Kafka 발행 (비동기)
    CouponIssueEvent event = new CouponIssueEvent(couponId, username);
    kafkaTemplate.send("coupon-issue", event);
}
```

### Consumer 레벨 (정합성 보장 + 카운터 업데이트)
```java
@KafkaListener(topics = "coupon-issue", concurrency = "10") // 최대 10개 동시 처리
public void consume(CouponIssueEvent event) {
    // DB 비관적 락으로 정합성 보장
    // Consumer 개수가 제한되어 있어 커넥션 풀 고갈 불가능
    CouponPolicy policy = repository.findByIdWithLock(event.getCouponId());
    
    if (policy.canIssue()) {
        policy.incrementIssuedQuantity();
        repository.save(policy);
        saveCouponIssue(event); // DB coupon_issue 이력 생성
        
        // 실제 발급 성공 시 count 증가 (현재 발급 카운터)
        redisTemplate.opsForValue()
            .increment("coupon:" + event.getCouponId() + ":count");

        // 누적 발급 카운터 증가 (취소와 무관한 total)
        redisTemplate.opsForValue()
            .increment("coupon:" + event.getCouponId() + ":issued_total");
    } else {
        throw new CouponSoldOutException();
    }
}
```

### 쿠폰 취소 처리 (튜플 보존 + 플래그 변경)
```java
public void cancelCoupon(Long couponId, Long userId) {
    // 1. DB에서 쿠폰 발급 이력을 조회하고, 삭제 대신 canceled 플래그로 관리
    CouponIssue issue = couponIssueRepository.findByUserIdAndCouponId(userId, couponId)
        .orElseThrow(() -> new RuntimeException("CouponIssue not found"));

    if (issue.isUsed()) {
        throw new RuntimeException("Cannot cancel used coupon");
    }

    issue.cancel(); // 튜플은 유지, 취소 여부만 표시
    
    // 2. Redis count만 감소 (현재 발급 수 감소)
    redisTemplate.opsForValue()
        .decrement("coupon:" + couponId + ":count");
    
    // 3. Redis stock 증가 (재고 복구)
    redisTemplate.opsForValue()
        .increment(String.format("coupon:%d:stock", couponId));
}
```

## 커넥션 풀 보호 전략

### 1. Redis 선차단
- countReq로 대량 트래픽을 API 레벨에서 차단
- DB까지 가지 않도록 방어

### 2. Kafka 버퍼링
- 요청을 큐에 쌓아서 완충
- API 서버는 즉시 응답 (202 Accepted)

### 3. Consumer 개수 제한
- `concurrency = "10"` → 최대 10개 동시 DB 트랜잭션
- 커넥션 풀 허용치(예: 20)보다 낮게 설정
- 비관적 락을 써도 풀 고갈 불가능

### 4. DB 락 전략
- Consumer 내부에서 비관적 락 사용
- 동시 트랜잭션 수가 제한되어 있어 안전
- 비관적 락을 써도 커넥션 풀 고갈 불가능

## 시나리오 예시

### 정상 케이스
```
10,000개 동시 요청
  → Redis: 100개만 통과 (9,900개 즉시 차단)
  → Kafka: 100개 메시지
  → Consumer: 10개 동시 처리
  → DB: 최대 10개 트랜잭션만 동시 실행
```

### 취소 케이스
```
초기: countReq=100, count=100, LIMIT=100
사용자 A 취소
  → count: 99 (감소)
  → countReq: 100 (그대로)
  
새로운 사용자 B 요청
  → countReq: 101 (증가)
  → countReq(101) > LIMIT(100) → 차단 ❌
  
문제: 재고는 1개 남았는데 새 사용자가 못 들어옴
```

### 해결 방법
**옵션 1**: countReq를 "게이트"로만 사용하고, 실제 재고는 stock으로 관리
```java
// stock으로 재고 관리
Long remain = redisTemplate.opsForValue()
    .decrement("coupon:" + couponId + ":stock");
if (remain < 0) {
    redisTemplate.opsForValue().increment("coupon:" + couponId + ":stock");
    throw new CouponSoldOutException();
}
```

**옵션 2**: countReq에 버퍼 허용
```java
// LIMIT보다 조금 더 허용 (예: LIMIT * 1.1)
if (reqCount > LIMIT * 1.1) {
    throw new CouponSoldOutException();
}
// 실제 초과 발급은 DB에서 차단
```

## Consequences

### Pros
- 커넥션 풀 고갈 방지
- 대량 트래픽 사전 차단
- 정합성과 성능의 균형
- Kafka로 시스템 안정성 확보

### Cons / Trade-offs
- Redis와 DB 간 불일치 가능성
- 주기적 동기화 필요 (보정 작업)
- 취소 시 재발급 로직 복잡도 증가

## Follow-up Decisions
- Redis stock 카운터 vs countReq/count 이중 카운터 선택
- 취소 시 재발급 허용 여부 결정
- 주기적 동기화 작업 (Scheduler) 구현
