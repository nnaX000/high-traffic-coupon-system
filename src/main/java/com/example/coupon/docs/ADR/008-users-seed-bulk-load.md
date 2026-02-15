# ADR-008: users 테이블 대량 시드(2000만 건) 적재 방식

DATE: 2026-02-15

## Status
Accepted

## Decision Drivers
- 고트래픽/부하 테스트를 위한 users 테이블 2000만 건 시드 필요
- 애플리케이션·DB 부하를 최소화하면서 단일 작업으로 적재 완료
- 재현 가능한 절차와 로컬/CI 환경에서 동일하게 실행 가능

## Context
쿠폰 발급 시나리오 검증을 위해 대량의 사용자 데이터가 필요하다.
JPA/애플리케이션으로 한 건씩 또는 배치 insert를 하면 2000만 건 기준 시간·리소스 비용이 크고,
트랜잭션·커넥션 풀 관리가 부담된다.

DB에 직접 대량 데이터를 넣는 방식 중에서 구현 복잡도·실행 시간·권한 이슈를 고려해 선택이 필요했다.

## Considered Options

### 1. 애플리케이션 배치 insert (JPA batch / JDBC batch)
- Spring JPA `saveAll()` 또는 JDBC batch insert로 수만 건씩 삽입
- **단점:** 2000만 건 시 수십 분~수 시간 소요, 메모리·커넥션 풀 부담, 장애 시 재시작 지점 관리 복잡

### 2. 다중 INSERT SQL 파일 생성 후 mysql 클라이언트 실행
- `INSERT INTO users VALUES (...),(...),...` 형태로 10만 건 단위 등 여러 문장 생성
- **단점:** 파일 용량·파싱 시간 증가, SQL 문 길이 제한 이슈 가능

### 3. CSV 생성 + MySQL LOAD DATA INFILE (채택)
- 스크립트로 탭 구분 CSV 생성 후 MySQL `LOAD DATA INFILE`로 일괄 적재
- **장점:** MySQL 네이티브 벌크 로드로 속도·리소스 효율 우수, 파일 하나로 재현 가능
- **단점:** FILE 권한 또는 root 사용 필요, `secure_file_priv` 등 서버 설정 필요

## Decision
**CSV 생성 + LOAD DATA INFILE** 방식으로 2000만 건 시드를 적재한다.

- **CSV 생성:** Python 스크립트(`infra/seed/generate_users_csv.py`)로 id(1~2000만), user_id, username, password, email을 탭 구분 파일로 출력. 10만 건 단위 버퍼링으로 디스크 I/O 효율화.
- **전달 경로:** Docker Compose에서 `./infra/seed/data`를 MySQL 컨테이너 `/seed/data`에 마운트하고, `--secure-file-priv=/seed`로 해당 경로만 파일 로드 허용.
- **적재:** `LOAD DATA INFILE '/seed/data/users.csv'`로 users 테이블에 매핑. PK(id)를 CSV에 포함해 AUTO_INCREMENT 기본값(0) 중복 이슈 방지.
- **기존 데이터 정리:** `coupon_issue`가 `users`를 FK로 참조하므로, 재적재 시 `SET FOREIGN_KEY_CHECKS=0` 후 `coupon_issue` → `users` 순 TRUNCATE 스크립트(`truncate_users.sql`)로 비운 뒤 적재.
- **권한:** LOAD DATA INFILE은 FILE 권한이 필요하므로 시드 적재만 root로 실행하고, README에 절차 명시.

## Consequences

### Pros
- 2000만 건 기준 수 분~십 수 분 내 적재 가능 (환경에 따라 상이)
- 애플리케이션/커넥션 풀에 부담 없이 DB만으로 처리
- CSV·SQL·스크립트만으로 동일 절차 재실행 가능
- id를 CSV에 포함해 PK 중복(0) 문제 회피

### Cons / Trade-offs
- 시드 적재 시 root 사용 필요 (운영 DB에는 동일 방식 비권장; 별도 마이그레이션/시드 전용 계정 고려)
- CSV 파일 약 1.5~2GB 수준으로 디스크 여유 필요
- TRUNCATE 시 `coupon_issue` 데이터도 함께 삭제됨

## 구현 요약 (파일 역할)

| 구분 | 파일 | 역할 |
|------|------|------|
| CSV 생성 | `infra/seed/generate_users_csv.py` | id, user_id, username, password, email 탭 구분 2000만 행 생성 |
| 적재 SQL | `infra/seed/load_users.sql` | LOAD DATA INFILE로 users 테이블 적재 |
| 비우기 SQL | `infra/seed/truncate_users.sql` | FK 체크 해제 후 coupon_issue, users 순 TRUNCATE |
| 인프라 | `docker-compose.yml` | MySQL volume `./infra/seed/data:/seed/data`, `--secure-file-priv=/seed` |
| 문서 | `infra/seed/README.md` | CSV 생성 → (선택) truncate → load 실행 순서 |
| 버전 관리 | `.gitignore` | `infra/seed/data/` 제외로 대용량 CSV 미커밋 |

## 실행 순서 (참고)
1. `docker compose up -d mysql`
2. `python3 infra/seed/generate_users_csv.py`
3. (재적재 시) `docker compose exec -T mysql mysql -uroot -prootpass coupon < infra/seed/truncate_users.sql`
4. `docker compose exec -T mysql mysql -uroot -prootpass coupon < infra/seed/load_users.sql`
