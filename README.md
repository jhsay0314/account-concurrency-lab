# Account Concurrency Lab

동일한 계정을 여러 요청이 동시에 가져갈 때와 신규 로그인 ID가 충돌할 때,
데이터 일관성과 실패 복구를 MySQL에서 검증하는 개인 실험 프로젝트입니다.
실무에서 접한 문제를 가상의 요구사항으로 새로 구현했으며 회사 코드·데이터를 포함하지 않습니다.

## 검증하려는 규칙

- 하나의 사용 가능한 계정은 한 요청만 가져갈 수 있습니다.
- 성공 응답을 받은 요청의 소유자가 실제 저장된 소유자와 일치해야 합니다.
- 로그인 ID는 데이터베이스 전체에서 유일해야 합니다.
- ID 충돌 재시도는 최대 3회이며, 각 시도는 독립 트랜잭션입니다.
- 기존 계정의 잠금과 신규 행의 유일성 제약은 서로 다른 문제를 해결합니다.

## 실험 구성

| 실험 | 조건 | 검증할 결과 |
|---|---|---|
| 잠금 없는 조회 후 갱신 | 두 요청이 갱신 전 모두 같은 행을 읽음 | 두 요청이 성공을 주장하지만 최종 소유자는 하나 |
| 낙관적 잠금 | 동일 버전을 읽도록 두 요청을 동기화 | 한 커밋 성공, 한 버전 충돌 |
| 비관적 잠금 | 같은 계정에 8개 동시 요청 | 한 요청 성공, 나머지는 사용 불가 |
| 신규 ID 충돌 | 8개 요청의 첫 후보 ID 동일 | 실패한 요청은 새 트랜잭션에서 다른 후보로 발급 |
| 같은 트랜잭션에서 예외 catch | 유일성 제약 위반 후 예외 처리 | rollback-only 상태가 복구되지 않음 |
| 재시도 한도 | 모든 후보가 기존 ID와 충돌 | 한도에서 종료, 추가 행 없음 |
| 다른 무결성 오류 | 필수 ID가 null | 재시도 없이 실패 |

경쟁 조건은 sleep으로 추측하지 않고 barrier로 읽기/요청 시점을 맞춥니다.
비관적 잠금을 얻은 뒤에는 barrier를 두지 않습니다. 다른 요청은 잠금을 기다리므로
그 위치의 barrier는 실험 자체를 교착시킬 수 있기 때문입니다.

## 설계 판단

### 기존 계정 재사용

낙관적 방식은 JPA `@Version`으로 갱신 충돌을 감지합니다. 읽기는 동시에 진행되며
패배한 요청은 충돌을 받습니다. 비관적 방식은 `SELECT ... FOR UPDATE`에 해당하는
`PESSIMISTIC_WRITE`로 같은 행의 조회와 상태 전환을 직렬화합니다.
두 방식 모두 성공한 요청 수뿐 아니라 저장된 소유자까지 검증합니다.

의도적으로 잘못된 baseline은 테스트의 JDBC 코드에만 있습니다. JPA의 `@Version`을
우회하여 보호 없는 read-then-write를 재현하며 API로 노출하지 않습니다.

### 신규 발급과 재시도

없는 행에는 기존 계정 행 잠금을 그대로 적용할 수 없습니다. ID 유일성은 DB 제약으로
보장하고, 이름이 확인된 로그인 ID 제약 위반만 재시도합니다.
트랜잭션 밖에서 실패를 받고 다음 `REQUIRES_NEW` 시도를 시작하므로 실패한 영속성
컨텍스트를 재사용하지 않습니다. 다른 무결성 오류를 중복 ID로 오인해 숨기지 않습니다.

기본 API는 UUID 후보를 사용합니다. 테스트에서는 후보 생성기를 주입해 희박한 충돌을
강제로 발생시킵니다. 충돌 재현을 위해 운영 API의 ID 생성 품질을 떨어뜨리지 않습니다.

## 실행

필요 환경: Java 21, Maven 3.9+, 실행 중인 Docker 엔진.
Spring Boot 3.5.7, MySQL 8.4, Testcontainers 1.21.3을 사용합니다.

```sh
mvn -B verify
```

현재 Windows 작업 환경에서는 IntelliJ에 포함된 JDK/Maven을 찾아 사용하는
`.\run-tests.ps1`로도 실행할 수 있습니다. 다른 환경에서는 표준 Maven 명령을 사용하세요.

Testcontainers가 별도 MySQL을 생성하므로 테스트에는 compose 실행이 필요 없습니다.
Docker가 없으면 테스트가 실패하며 자동 건너뛰기로 성공 처리하지 않습니다.
결과는 `target/surefire-reports/`에서 확인합니다.

API를 직접 실행하려면:

```sh
docker compose up -d
mvn spring-boot:run
```

MySQL 초기화가 끝난 뒤 앱을 시작합니다. `schema.sql`이 개발 DB를 초기화하며
앱은 스키마를 검증합니다. 로컬 개발용 계정만 포함되어 있습니다.

```sh
curl -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" -d '{"prefix":"demo"}'
curl -X POST http://localhost:8080/accounts/1/claim \
  -H "Content-Type: application/json" \
  -d '{"requestId":"request-a","strategy":"PESSIMISTIC"}'
```

발급 응답의 `accountId`를 claim 경로에 사용합니다. `OPTIMISTIC`도 선택할 수 있습니다.
이미 사용된 계정·경쟁 실패는 409, 없는 계정은 404입니다.
`requestId`는 실험용 소유자 표식이며 인증 정보나 멱등성 키가 아닙니다.
인증·실제 사용자 개인정보·회원 탈퇴 복구는 범위에 포함하지 않았습니다.
HTTP 서버와 개발 DB 포트는 로컬 인터페이스로 제한했습니다.

## 검증 상태

2026-10-21: 사용자 로컬 PowerShell에서 실행한 로그와 Surefire 결과 파일을 확인했습니다.

- MySQL 8.4.11 / Docker Desktop 엔진 25.0.3 / Java 21.0.3 / Spring Boot 3.5.7.
- `mvn verify`: **Tests run: 8, Failures: 0, Errors: 0, Skipped: 0**, **BUILD SUCCESS**.
- 위 실험 표의 시나리오와 없는 계정 예외 검증이 이번 실행에서 통과했습니다.
- 전체 실행 4분 1초에는 이미지 다운로드·컨테이너 시작·앱 초기화·종료 대기가
  포함됩니다. 처리량이나 API 응답 시간으로 해석하지 않습니다.
- 종료 시 닫힌 DB 연결 경고와 Surefire JVM 종료 시간 초과 메시지가 있었습니다.
  첫 후속 수정에서 컨텍스트를 직접 닫자 Spring의 afterTestClass 처리 중
  비활성 컨텍스트 오류가 발생했습니다. 이 실행은 BUILD FAILURE입니다.
  수동 종료와 JUnit 컨테이너 관리를 제거하고, Spring Boot가 관리하는
  `@Bean` + `@ServiceConnection` 컨테이너로 변경했습니다.
  최신 수정 후 17:35 재실행에서 **8개 통과, BUILD SUCCESS**를 확인했습니다.
  Hikari 연결 풀의 `Shutdown completed`가 기록되었으며, 이전의 비활성 컨텍스트
  오류·닫힌 연결 경고·Surefire JVM 강제 종료 메시지는 이번 로그에 없습니다.
  전체 빌드 시간은 41.828초이며 성능 비교 수치는 아닙니다.

의도한 중복 ID 및 null 제약 위반 로그는 실패 경로 검증 과정에서 발생합니다.
최신 코드의 결과는 정해진 시나리오의 1회 통과이며 부하 안정성이나 성능 우열을 증명하지 않습니다.
자세한 결과는 [검증 기록](docs/verification-2026-10-21.md)에 정리했습니다.

CI 구성은 포함했지만 저장소에 올리거나 CI 성공을 확인한 상태는 아닙니다.

## 다음 실험

반복 측정 코드를 추가했습니다. `.\run-tests.ps1 -Benchmark`로 실행하면
조건별 CSV를 생성합니다. [측정 조건과 해석 방법](docs/benchmark.md)을 먼저 확인하세요.
2026-10-21 실행에서 기능 테스트와 벤치마크 총 9개가 통과했습니다.
측정 요청 4,800개와 조건별 5회 반복 결과를 확인하고 원시 CSV를 보존했습니다.
[비교 결과와 해석](docs/benchmark-results-2026-10-21.md): 두 전략 모두 일관성 검증을
통과했으며, 성능은 조건별 편차가 커 일반적인 우열로 단정하지 않았습니다.

1. GitHub CI에서도 동일한 통합 테스트를 실행하고 결과를 남깁니다.
2. 동일한 요청 수·커넥션 풀·격리 수준에서 두 잠금 전략을 반복 측정합니다.
3. 처리량·p95·실패 종류·재시도 수를 기록합니다. 측정 전에는 성능 우열을 주장하지 않습니다.
4. 현재는 선택된 계정 하나를 claim합니다. 이후 후보 검색까지 포함하면 잠금 범위와
   빈 결과·기아 가능성 등 새로운 설계 문제가 생기므로 별도 실험으로 확장합니다.

참고: [Spring Boot 요구 환경](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[Testcontainers MySQL](https://java.testcontainers.org/modules/databases/mysql/).
