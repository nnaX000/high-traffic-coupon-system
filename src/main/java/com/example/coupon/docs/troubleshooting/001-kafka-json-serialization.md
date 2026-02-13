# Kafka JSON 직렬화/역직렬화 트러블슈팅 정리

DATE: 2026-02-13

## 1. 증상 정리

- **증상 1: Producer 직렬화 에러**
  - 메시지:  
    - `SerializationException: Can't convert value of class com.example.coupon.dto.CouponIssueEvent to class org.apache.kafka.common.serialization.StringSerializer`
    - `ClassCastException: CouponIssueEvent cannot be cast to java.lang.String`
  - 상황:
    - `KafkaTemplate<String, Object>` 를 사용하면서
    - `kafkaTemplate.send("coupon-issue", new CouponIssueEvent(...))` 로 DTO를 바로 전송
    - `value.serializer` 는 기본값인 `StringSerializer` 상태

- **증상 2: Consumer 변환 에러 (String → DTO)**
  - 메시지:
    - `MessageConversionException: Cannot convert from [java.lang.String] to [com.example.coupon.dto.CouponIssueEvent]`
  - 상황:
    - Kafka에 저장된 value는 JSON 문자열
    - `@KafkaListener` 메서드 시그니처는 `consume(CouponIssueEvent event)`
    - Consumer의 `value-deserializer` 는 여전히 `StringDeserializer` 또는 기본값

- **증상 3: JsonDeserializer 신뢰 패키지 오류**
  - 메시지:
    - `The class 'com.example.coupon.dto.CouponIssueEvent' is not in the trusted packages: [java.util, java.lang]`
  - 상황:
    - Consumer에서 `JsonDeserializer` 로 변경했지만,
    - `spring.json.trusted.packages` 설정을 안 해서, DTO 타입을 신뢰할 수 없다고 거부

## 2. 최종 해결 설정

### 2.1 Producer 설정 (JSON 직렬화)

- 목적: `CouponIssueEvent` DTO를 JSON으로 직렬화해서 Kafka에 적재
- `application.yml`:

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

- `CouponService`:
  - `KafkaTemplate` 제네릭 타입을 명확히 지정

```java
private final KafkaTemplate<String, CouponIssueEvent> kafkaTemplate;
```

### 2.2 Consumer 설정 (JSON 역직렬화 + 신뢰 패키지)

- 목적: Kafka에 저장된 JSON 문자열을 `CouponIssueEvent` 객체로 역직렬화
- `application.yml`:

```yaml
spring:
  kafka:
    consumer:
      group-id: coupon-issue-consumer
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.example.coupon.dto"
        spring.json.value.default.type: "com.example.coupon.dto.CouponIssueEvent"
```

- `CouponIssueService`:
  - 리스너 메서드 시그니처:

```java
@KafkaListener(topics = "coupon-issue", concurrency = "10")
@Transactional
public void consume(CouponIssueEvent event) {
    // ...
}
```

## 3. 추가로 겪었던 관련 이슈들

### 3.1 group.id 미설정

- 에러:
  - `IllegalStateException: No group.id found in consumer config, container properties, or @KafkaListener annotation`
- 해결:
  - `spring.kafka.consumer.group-id: coupon-issue-consumer` 설정 추가
  - 또는 `@KafkaListener(groupId = "coupon-issue-consumer")` 로 직접 지정

### 3.2 Kafka 브로커 연결 실패

- 에러:
  - `Connection to node -1 (localhost/127.0.0.1:9092) could not be established. Node may not be available.`
- 원인:
  - Kafka 컨테이너가 떠 있지 않거나,
  - `KAFKA_ADVERTISED_LISTENERS` 설정과 실제 접속 주소가 맞지 않을 때 발생
- 기본 원칙:
  - 애플리케이션에서 바라보는 주소(`localhost:9092` 또는 도커 네트워크 상 서비스명)를  
    Kafka 컨테이너 설정(ADVERTISED_LISTENERS)과 일치시키는 것이 중요

### 3.3 파티션/키 전략 (couponId를 key로 사용)

- 추가로 논의/적용한 내용:
  - Kafka는 같은 key에 대해 **같은 파티션**에 레코드를 적재하고, 파티션 내 순서를 보장한다.
  - 쿠폰별 발급 순서를 보장하기 위해, Producer에서 **`couponId`를 key로 사용**하기로 결정했다.
- 적용 코드 (Producer):

```java
CouponIssueEvent event = new CouponIssueEvent(couponId, username);
// key = couponId, value = CouponIssueEvent(JSON)
kafkaTemplate.send("coupon-issue", String.valueOf(couponId), event);
```

- 효과:
  - 동일 `couponId`에 대한 모든 이벤트가 **동일 파티션**으로 들어가므로,
  - Consumer 측에서 해당 쿠폰에 대한 발급 처리 순서가 파티션 내 순서와 일치한다.
  - 여러 쿠폰이 있을 경우, 파티션 단위로 부하 분산에도 도움이 된다.

## 4. 요약

- **Producer**: `KafkaTemplate<String, CouponIssueEvent>` + `JsonSerializer`
- **Consumer**: `JsonDeserializer<CouponIssueEvent>` + `trusted.packages` / `default.type`
- **공통**:
  - `group.id` 필수
  - 브로커 주소(Kafka 컨테이너 설정 vs 애플리케이션 설정) 일치 확인

