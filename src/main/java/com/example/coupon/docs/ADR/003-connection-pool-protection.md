# ADR-003: Kafka를 통한 DB 커넥션 풀 보호

DATE: 2026-01-XX

## Status
Accepted

## Decision Drivers
- DB 커넥션 풀 고갈 방지
- 비관적 락 사용 시에도 안정적인 동작 보장
- 대량 트래픽 처리 시 시스템 안정성

## Context
비관적 락은 DB 커넥션을 "길게 쥐고 있는" 방식이다.
트랜잭션이 시작되면 락을 획득하고, 커밋/롤백할 때까지 커넥션을 점유한다.

만약 커넥션 풀 허용치(예: 20개)보다 많은 트랜잭션(예: 100개)이 동시에 실행되면:
- 커넥션 풀 고갈
- 새로운 요청 처리 불가
- 시스템 마비

## Problem
**"DB 커넥션을 길게 쥐는 문제"를 시간적으로 해결하려는 접근:**
- 락 타임아웃 설정
- 트랜잭션 시간 단축
- 락 범위 최소화

**하지만 근본적인 해결책은:**
- **"애초에 동시에 들어가는 트랜잭션 수를 줄이자"**

## Decision
**Kafka를 사이에 두어 동시 DB 트랜잭션 수를 제한**

### 아키텍처
```
API 요청
  → Redis countReq (선차단)
  → Kafka 발행 (비동기 큐)
  → Consumer (제한된 개수, 예: 10개)
  → DB 트랜잭션 (비관적/낙관적 락)
```

### 핵심 원리
1. **API 서버**: 요청을 Kafka에 밀어넣고 즉시 응답 (202 Accepted)
   - DB 커넥션을 전혀 사용하지 않음
   - 빠른 응답 시간

2. **Kafka**: 요청을 큐에 쌓아서 완충(Buffer)
   - 순차/배치 처리 가능
   - 시스템 부하 분산

3. **Consumer**: 제한된 개수만 동시 실행
   - `concurrency = "10"` → 최대 10개 동시 처리
   - 커넥션 풀 허용치(20)보다 낮게 설정
   - **비관적 락을 써도 풀 고갈 불가능**

## Implementation

### Consumer 설정
```java
@KafkaListener(
    topics = "coupon-issue",
    concurrency = "10"  // 최대 10개 동시 처리
)
public void consume(CouponIssueEvent event) {
    // 비관적 락 사용해도 안전
    // 동시에 최대 10개만 실행되므로 커넥션 풀 고갈 불가능
    CouponPolicy policy = repository.findByIdWithLock(event.getCouponId());
    
    if (policy.canIssue()) {
        policy.incrementIssuedQuantity();
        repository.save(policy);
        saveCouponIssue(event);
    }
}
```

### 커넥션 풀 설정 예시
```properties
# application.properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
```

### Consumer 개수 결정
- **권장**: 커넥션 풀 크기의 50~70%
- 예: 커넥션 풀 20개 → Consumer 10개
- 이유: 다른 작업(조회 등)도 커넥션을 사용할 수 있도록 여유 확보

## 시나리오 비교

### Kafka 없이 동기 처리 (문제 상황)
```
10,000개 동시 요청
  → 모두 DB 트랜잭션 시작
  → 커넥션 풀 20개 초과
  → 9,980개 요청은 커넥션 대기
  → 타임아웃 발생
  → 시스템 마비
```

### Kafka + Consumer 제한 (해결)
```
10,000개 동시 요청
  → Redis: 100개만 통과
  → Kafka: 100개 메시지
  → Consumer: 최대 10개 동시 처리
  → DB: 최대 10개 트랜잭션만 동시 실행
  → 커넥션 풀 안전 (20개 중 10개만 사용)
  → 나머지 90개는 큐에서 대기
  → 순차 처리
```

## 장점

### 1. 커넥션 풀 보호
- 동시 트랜잭션 수를 강제로 제한
- 풀 고갈 불가능

### 2. 비관적 락 안전 사용
- 비관적 락을 써도 안전
- 락 대기 시간이 길어져도 시스템 안정

### 3. 시스템 안정성
- 대량 트래픽도 큐에 쌓여서 처리
- 시스템 부하 분산

### 4. 빠른 API 응답
- API 서버는 즉시 응답 (202 Accepted)
- 사용자 경험 향상

## Trade-offs

### Pros
- 커넥션 풀 고갈 방지
- 비관적 락 안전 사용 가능
- 시스템 안정성 확보
- 빠른 API 응답

### Cons
- 처리 지연 발생 (비동기)
- Kafka 운영 복잡도 증가
- 최종 일관성 (Eventual Consistency)
- 메시지 손실 가능성 (DLQ 처리 필요)

## Best Practices

### 1. Consumer 개수 설정
- 커넥션 풀 크기의 50~70%
- 모니터링 후 조정

### 2. 에러 처리
- 재시도 로직 구현
- DLQ(Dead Letter Queue) 설정
- 알림 시스템 연동

### 3. 모니터링
- Kafka lag 모니터링
- Consumer 처리 속도 추적
- 커넥션 풀 사용률 모니터링

### 4. 확장성
- Consumer 개수 동적 조정 가능
- 트래픽 증가 시 Consumer 수 증가
- 하지만 커넥션 풀 크기 고려 필수

## 결론
**Kafka를 사이에 두는 것은 "DB 커넥션을 길게 쥐는 문제"를 구조적으로 해결하는 최선의 방법이다.**

- 시간적 해결: 락 타임아웃, 트랜잭션 시간 단축
- 구조적 해결: **Kafka + Consumer 제한** ← 더 근본적

이 구조를 통해:
- 비관적 락을 안전하게 사용 가능
- 커넥션 풀 고갈 방지
- 대량 트래픽 처리 가능
- 시스템 안정성 확보
