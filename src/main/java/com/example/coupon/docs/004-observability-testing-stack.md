ADR-002: Observability & Testing Stack for High Traffic Coupon System

DATE: 2026-01-XX

## Status
Accepted

## Decision Drivers
- 고트래픽 쿠폰 발급 시스템의 상태를 실시간으로 관측해야 함
- 배포 전후로 동일한 조건에서 회귀/부하 테스트를 반복 가능하게 해야 함
- Redis/Kafka 장애 시나리오를 사전에 실험하여 복원력(Resilience)을 검증해야 함
- CI/CD 파이프라인에 자동 검증 단계를 녹여야 함

## Context
쿠폰 발급 시스템은 짧은 시간 동안 트래픽이 집중되고, Redis/Kafka/DB에 대한 의존도가 높다.
이 과정에서 성능 저하, 부분 장애, 네트워크 이슈가 발생해도 사용자는 최대한 안정적인 경험을
제공받아야 한다.

이를 위해 운영 환경의 메트릭/로그를 수집·시각화하고, 테스트 환경에서는 격리된 인프라에서
통합/부하/장애 주입 테스트를 수행할 수 있는 도구 스택을 정의할 필요가 있다.

## Considered Options
1. 기본 애플리케이션 로그 및 APM 만으로 관측 최소화
2. 자체 스크립트 기반 간단 부하 테스트만 수행
3. Prometheus + Grafana + Testcontainers + k6 + 장애 주입 도구(Toxiproxy, Chaos Mesh 등) 조합 사용

## Decision
고트래픽 쿠폰 발급 시스템의 관측·테스트·장애 주입을 위해 다음 스택을 사용한다.

- 관측(모니터링 및 대시보드)
  - Prometheus: 애플리케이션/Redis/Kafka/시스템 메트릭 수집
  - Grafana: 쿠폰 발급 성공률, 대기열 길이, 에러율, 지연 시간 등 대시보드 구성
- 통합 테스트/환경 격리
  - Testcontainers: Redis/Kafka/DB 등을 컨테이너로 올려 통합 테스트 시 인프라 의존성 격리
- 부하/시나리오 테스트
  - k6: 쿠폰 발급 트래픽 패턴(버스트, 장기 부하 등)을 스크립트로 정의하고 CI 파이프라인에 연동
- 장애 주입(특히 Redis/Kafka 네트워크 이슈)
  - Toxiproxy: Redis/Kafka 대상 네트워크 지연, 패킷 드롭, 연결 단절 등 네트워크 장애 시뮬레이션 (Redis RTO/정합성 검증 상세는 ADR-005 참조)
  - Chaos Mesh(선택): 쿠버네티스 환경에서 노드/파드 수준 장애 및 리소스 압박 실험 수행

## Consequences

### Pros
- 표준화된 관측 스택(Prometheus + Grafana)으로 메트릭 기반 운영 가능
- Testcontainers로 로컬/CI에서 동일한 통합 테스트 환경을 재현 가능
- k6로 실제 트래픽 패턴에 가까운 부하/시나리오 테스트 자동화
- Toxiproxy 및 Chaos Mesh로 Redis/Kafka 네트워크 장애/노드 장애를 사전에 검증 가능 (Toxiproxy 기반 Redis Failover/RTO/정합성 검증은 ADR-003에서 상세 기술)

### Cons / Trade-offs
- 도구 스택이 늘어나면서 초기 셋업 및 운영 복잡도가 증가함
- k6/Chaos Mesh 등은 팀 내 학습 비용이 필요함
- CI 파이프라인 시간이 길어질 수 있어 단계적/선택적 실행 전략이 필요함

## Follow-up Decisions
- k6 스크립트 표준 시나리오(피크 트래픽, 장기 부하, 장애 복구 등) 정의
- Prometheus/Grafana 대시보드 템플릿 및 알람 룰 표준화
- Toxiproxy/Chaos Mesh 실험을 정기적으로 실행할지 여부와 스케줄링 전략 (구체적인 Redis 장애 시나리오는 ADR-003에서 관리)
