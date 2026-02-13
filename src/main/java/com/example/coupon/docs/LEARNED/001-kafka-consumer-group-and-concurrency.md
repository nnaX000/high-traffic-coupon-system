# LEARNED-001: Kafka Consumer 그룹 / concurrency / 파티션 분배 이해

DATE: 2026-02-13

## 1. 개념 정리

- **Consumer Group (`group.id`)**
  - 같은 `group.id`를 사용하는 모든 Consumer 인스턴스는 **하나의 컨슈머 그룹**으로 묶인다.
  - Kafka 입장에선 “이 그룹이 토픽의 파티션들을 나눠서 읽는다”는 의미.
  - **중요**: 하나의 파티션은 한 시점에 **한 그룹 내 하나의 Consumer**에게만 할당된다.

- **Consumer 인스턴스**
  - 실제로 메시지를 읽는 단위 (프로세스/스레드).
  - 스프링 카프카에선 `@KafkaListener` + `concurrency` 조합으로 여러 Consumer 쓰레드를 쉽게 띄울 수 있다.

- **`concurrency` 파라미터**
  - 예: `@KafkaListener(topics = "coupon-issue", concurrency = "10")`
  - → 해당 리스너 컨테이너 안에 **동일한 Consumer 인스턴스(쓰레드) 10개**를 생성한다.
  - 모두 같은 `group.id`를 사용하므로, Kafka는 이 10개 인스턴스에게 파티션을 분배한다.

## 2. 파티션 분배 방식

- 토픽 `T`에 파티션이 `N`개, 같은 `group.id`를 가진 Consumer 인스턴스가 `M`개라면:
  - Kafka는 `N`개의 파티션을 `M`개 인스턴스에 **최대한 균등하게 분배**한다.
  - 원칙:
    - **한 파티션은 한 시점에 그룹 내 단 하나의 Consumer에게만 할당**된다.
    - Consumer 수(`M`)가 파티션 수(`N`)보다 많으면, 일부 Consumer는 파티션을 배정받지 못할 수 있다.
    - Consumer 수가 파티션 수보다 적으면, 한 Consumer가 여러 파티션을 담당한다.

## 3. 여러 @KafkaListener 와 group.id

- **같은 `group.id`를 공유하는 @KafkaListener 들**
  - 예:

    ```java
    @KafkaListener(topics = "coupon-issue", concurrency = "10")
    public void consumeA(CouponIssueEvent event) { ... }

    @KafkaListener(topics = "coupon-issue", concurrency = "3")
    public void consumeB(CouponIssueEvent event) { ... }
    ```

  - 둘 다 `spring.kafka.consumer.group-id=coupon-issue-consumer` 를 사용한다면:
    - 그룹 전체 Consumer 인스턴스 수 = 10 + 3 = **13개**
    - Kafka는 `coupon-issue` 토픽의 파티션들을 이 13개 인스턴스에 나눠서 배정한다.
    - **같은 파티션을 동시에 읽는 인스턴스는 여전히 1개뿐**이다.

- **다른 `group.id`를 가진 @KafkaListener**
  - 서로 다른 컨슈머 그룹이 되어, **같은 토픽/파티션을 각자 독립적으로 소비**한다.
  - 예:
    - `group-id = coupon-issue-consumer` (실제 발급 처리용)
    - `group-id = coupon-issue-analytics` (통계/로그 적재용)

## 4. 이 프로젝트에 적용된 패턴

- `spring.kafka.consumer.group-id: coupon-issue-consumer`
- `CouponIssueService`:

```java
@KafkaListener(topics = "coupon-issue", concurrency = "10")
@Transactional
public void consume(CouponIssueEvent event) {
    // ...
}
```

- 의미:
  - `coupon-issue` 토픽의 파티션들을 **coupon-issue-consumer 그룹** 내 최대 10개 Consumer 쓰레드가 나눠서 처리한다.
  - 같은 파티션에서 오는 이벤트는 항상 **동일 Consumer 쓰레드**를 통해 순서대로 처리된다.

