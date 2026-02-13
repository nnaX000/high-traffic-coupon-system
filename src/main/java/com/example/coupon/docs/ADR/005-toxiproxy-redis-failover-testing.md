ADR-003: Toxiproxy 기반 Redis Failover / 정합성 검증 전략

DATE: 2026-01-XX

## Status
Accepted

## Decision Drivers
- Redis 장애 시 쿠폰 발급 시스템이 얼마나 빨리 복구(RTO)되는지 수치로 확인해야 함
- Failover 또는 Redis 재시작 이후, Redis 상태가 실제 발급 원장(MySQL)과 정확히 일치하는지 검증해야 함
- 운영 환경과 유사한 네트워크 장애(지연, 패킷 드롭, 연결 단절)를 테스트 환경에서 반복 재연 가능해야 함

## Context
고트래픽 쿠폰 발급 시스템은 Redis를 통해 발급 가능 수량 및 카운터를 관리한다.
Redis 장애 또는 네트워크 이슈가 발생할 경우,

- 발급 요청이 어느 정도 시간 동안 실패/대기 상태가 되는지,
- Failover 이후 Redis 카운터가 MySQL 쿠폰 발급 원장과 얼마나 정확히 맞아 떨어지는지

를 정량적으로 측정·검증해야 한다.

단순한 장애 주입(프로세스 kill 등)만으로는 네트워크 레벨의 미묘한 장애 상황을 재현하기 어렵기 때문에,
프록시 레벨에서 지연, 드롭, 연결 단절 등을 세밀하게 제어할 수 있는 도구가 필요하다.

## Considered Options
1. Redis 프로세스 직접 중지/재시작으로만 장애 실험 수행
2. 클라우드 네트워크 설정을 수동으로 변경하여 장애 상황 재현
3. Toxiproxy를 사용하여 Redis 앞단에 프록시를 두고 네트워크 장애를 제어

## Decision
Redis 장애/RTO/정합성 검증을 위해 Toxiproxy를 Redis 앞단에 두고 다음과 같이 사용한다.

- Redis 연결은 직접이 아닌 Toxiproxy 프록시 엔드포인트를 통해 이루어지도록 구성한다.
- 테스트 시나리오에서 Toxiproxy API를 사용해 다음과 같은 장애를 주입한다.
  - 지연(latency) 주입으로 응답 지연/RTO 영향을 측정
  - 패킷 드롭 또는 제한을 통한 부분 실패 상황 재현
  - 완전한 연결 단절을 통한 Failover 및 재시작 시나리오 검증
- 장애 주입 전/중/후에 다음을 수집·비교한다.
  - Redis 카운터 값
  - MySQL 쿠폰 발급 원장(실제 발급 수)
  - 애플리케이션 레벨 에러율/지연 시간

이로써 Redis 장애 상황에서의 RTO와 Redis-MySQL 정합성을 자동화된 테스트로 반복 검증할 수 있다.

## Consequences

### Pros
- Redis 장애 시 RTO를 수치로 측정하고, 목표 RTO와의 차이를 명확히 파악할 수 있음
- Failover/재시작 이후 Redis 상태가 MySQL 원장과 일치하는지 자동으로 검증 가능
- 다양한 네트워크 장애 패턴을 코드/스크립트 수준에서 반복적으로 재현 가능

### Cons / Trade-offs
- Toxiproxy 운영 및 설정에 대한 추가 학습 비용이 발생함
- 테스트 환경에서 Redis 접속 구성이 Toxiproxy 프록시를 거치도록 별도 관리가 필요함
- 잘못된 설정 시 실제 장애 상황과 동떨어진 시나리오를 만들 위험이 있음

## Follow-up Decisions
- 표준 Redis 장애 시나리오(RTO 측정, 정합성 검증 케이스) 목록 정의
- Toxiproxy 기반 테스트를 CI 파이프라인에 어느 수준까지 통합할지 결정
- 운영 환경 유사도를 높이기 위한 추가 실험 도구(예: Chaos Mesh)와의 조합 여부 검토
