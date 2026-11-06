# 테스트 환경 현황과 `contextLoads` 실패 (backlog B-005)

- 관련: [backlog](backlog.md) · [P-001-retry-duplicate](problems/P-001-retry-duplicate.md) · [AGENTS.md](../../AGENTS.md)
- 상태: **Phase 1 구현·검증 완료 (Testcontainers MySQL, 로컬 실행)**. 아래 "B-005 Phase 1 결과"가 현재 상태이고, 그 뒤의 "현재 확인된 사실" 이하는 **구현 전(변경 전) 기록**이다.

## B-005 Phase 1 결과 [확인: 로컬 실행]

### 구성

| 항목 | 내용 |
|---|---|
| DB | Testcontainers `mysql:8.0` 컨테이너. DataSource는 `@ServiceConnection`으로 이 컨테이너에서만 연결 정보를 받는다 (`support/MySqlContainerTestConfig`) |
| 설정 | `src/test/resources/application.yml`: 더미 값(`.invalid` 주소, 가짜 키)만. **`spring.datasource.url`은 정의하지 않는다** (컨테이너 없이는 DataSource가 만들어지지 않아 원격 DB로 우회 불가). 테스트 클래스패스에서 `src/main/resources/application.yml`보다 먼저 발견되어 대체된다 [가설: 이 PC에는 main 쪽 파일이 없어 섞이는 경우를 직접 확인하지 못함] |
| 프로파일 | `@ActiveProfiles("test")` |
| 안전장치 | `PharmquestApplicationTests.dataSourceUsesTestcontainerOnly`: Hikari JDBC URL이 컨테이너 URL과 같은지 검사 |
| 스키마 | `ddl-auto: create-drop` (엔티티 기준 임시 스키마) |
| 외부 호출 | 컨텍스트 로드 중 S3·번역·FDA·OAuth 호출 없음 [확인: 로그에서 외부 도메인 0건] |

### 버전

- Spring Boot 3.3.7, Testcontainers **1.21.4**, docker-java 3.4.2, MySQL 이미지 `mysql:8.0`.
- **MySQL 이미지 버전은 운영 버전을 모르는 채 임의로 고른 값이다.** 운영 버전이 확인되면 맞춘다.

### Docker 29 호환 문제와 해결

- 증상 [확인]: Docker Desktop 29.8.2(API 1.56, 최소 1.40, 실제 거부 기준은 1.44)에서 Spring Boot 3.3.7 BOM 기본값 Testcontainers 1.19.8(docker-java 3.3.6)이 `BadRequestException (Status 400 ...)`으로 Docker 환경을 찾지 못했다. 통합 테스트가 **SKIPPED**로 보고되어 성공으로 오인될 수 있었다.
- 해결 [확인]: `build.gradle`에 `ext['testcontainers.version'] = '1.21.4'`로 Testcontainers만 올렸다. 환경변수·`~/.docker-java.properties` 없이 통과한다.
- 선택하지 않은 방법: Testcontainers 2.x는 `org.testcontainers:mysql`이 `testcontainers-mysql`로, 컨테이너 클래스가 `org.testcontainers.mysql` 패키지로 바뀌어 Boot 3.3.x와 맞지 않는다고 판단했다 [확인: 2.0.0 릴리스 노트, 적용은 시도하지 않음]. `api.version` 강제(`JAVA_TOOL_OPTIONS`, `.docker-java.properties`)는 JVM·사용자 전역 설정이라 채택하지 않았다.
- 1.21.4가 Docker 29를 지원한다는 근거는 릴리스 노트의 "recent Docker Engine changes" 문구와 위 실제 실행이다. 공식 문서에서 정확한 수정 내용은 확인하지 못했다.

### SKIPPED 오인 방지 (`@RequiresDocker`)

- `support/RequiresDocker` + `DockerAvailableCondition`: Docker를 쓸 수 없으면 기본은 **SKIPPED** (사유 `NOT RUN: ...`), 환경변수 `REQUIRE_DOCKER=true`이면 **FAILED**.
- 실제 확인 [확인]: Docker와 클라이언트 API를 비호환(`-Dapi.version=1.32`)으로 만들었을 때, 환경변수 없음 → `skipped=2`·빌드 성공(종료코드 0), `REQUIRE_DOCKER=true` → 실패(종료코드 1).
- 주의: 빌드가 성공(종료코드 0)이어도 통합 테스트가 SKIPPED일 수 있다. 테스트 리포트의 skipped 수를 확인해야 한다.

### 실행 명령

| 목적 | 명령 (JDK 17 필요) |
|---|---|
| 전체 (Docker 있으면 통합 포함, 없으면 통합 SKIPPED) | `./gradlew test` |
| 통합 테스트만 | `./gradlew test --tests "*PharmquestApplicationTests"` |
| CI(통합 필수) | `REQUIRE_DOCKER=true ./gradlew test` |
| 단위 테스트만 | 현재 태그 필터가 없어 별도 명령 없음. `@Tag("integration")`은 붙어 있다 (필터 추가는 build.gradle 변경) |

### 결과

- 전체 **59개 PASS** (단위 57 + 통합 2: `contextLoads`, `dataSourceUsesTestcontainerOnly`), 환경변수 없이 실행.
- 기존 단위 57개의 결과는 변경 전과 같다. 컨텍스트 생성은 컨테이너 기동 포함 약 1분.
- 이전의 "전체 45개" 기록은 당시 기준이다. 현재는 59개다.

### CI 통합 테스트 강제와 설정 격리 (B-008, B-009) [로컬 검증만, 실제 CI 미실행]

**`dev_deploy.yml` 변경 (build job):**

| 이전 | 이후 |
|---|---|
| `application.yml` 생성 → `build` 한 단계 | `chmod` → **`test`**(`REQUIRE_DOCKER=true`) → **통합 테스트 실행 확인** → `application.yml` 생성 → `build -x test` |
| 테스트가 운영 설정 파일(시크릿)과 같은 클래스패스에서 실행 | 테스트 단계에는 `src/main/resources/application.yml`이 아직 없다 (jar에 넣을 파일은 테스트 뒤에 생성) |
| Docker 불가 시 통합 테스트 SKIPPED, 빌드 성공 | `REQUIRE_DOCKER=true`로 FAILED. 추가로 "통합 테스트 실행 확인" 단계가 **`PharmquestApplicationTests`와 `TestConfigIsolationTests` 두 클래스** 각각의 리포트 누락, 실행 0개, `skipped`≥1이면 실패시키고, 전체 리포트에서도 `skipped`≥1을 찾는다 (테스트 개수는 고정하지 않는다) |
| 테스트 실패 시 배포 | `deploy`는 `needs: build`이므로 test 단계 실패 시 실행되지 않는다 [확인: 워크플로 구조] |

**설정 우선순위 재현 [확인: 로컬 실행]** — 실제 시크릿이 아닌 SENTINEL 값으로 임시 `src/main/resources/application.yml`을 만들어 실행했다 (실행 후 삭제, 작업 트리 정리 확인):

- 클래스패스 순서: `build/resources/test/application.yml`이 `build/resources/main/application.yml`보다 먼저다.
- 같은 키(`jwt.secret`, `fda.api.api-key` 등)는 테스트 더미 값이 선택되었다.
- main에만 있는 키(`spring.datasource.url/username/password`, `server.port`, 임의 키)는 모두 **null** — 같은 이름의 파일이라 main 쪽은 로드되지 않는다.
- 위험한 main 값(`ddl-auto: validate`, 별도 OAuth 등록)도 영향을 주지 않았다. 테스트 로그에 SENTINEL 문자열은 0건.
- 새 상시 테스트 `TestConfigIsolationTests`(4개): `test` 프로파일 명시 활성화, 더미 값 15개 일치, `application-test.*` 파일 부재, OAuth 등록이 `test` 하나뿐이며 `localhost.invalid`. (`PharmquestApplicationTests`의 DataSource가 컨테이너 URL인지 검사는 기존대로.)

**로컬 검증 결과 [확인]:**

| 조건 | 결과 |
|---|---|
| Docker 실행, 환경변수 없음 | 전체 **63 PASS**, 0 FAIL, 0 SKIPPED (단위 57 + 통합 6) |
| `REQUIRE_DOCKER=true`, 통합만 | 6 PASS |
| 비호환 시뮬레이션(`-Dapi.version=1.32`), 환경변수 없음 | 통합 6 SKIPPED, 종료코드 0 → "실행 확인" 스크립트가 종료코드 1로 막음 |
| 비호환 시뮬레이션 + `REQUIRE_DOCKER=true` | 통합 FAILED, 종료코드 1 |
| 워크플로 YAML | SnakeYAML로 파싱 성공. build job 단계 순서: `test`(5) → 확인(6) → `application.yml` 생성(8) → `build -x test`(9). 어떤 단계에도 `if:`/`continue-on-error`가 없어 앞 단계 실패 시 뒤 단계(빌드·배포)는 실행되지 않는다 |
| 확인 스크립트 변형 (워크플로의 `run` 블록을 파싱해 그대로 실행, 정상 결과 복사본) | 정상 → 통과, Isolation 리포트 누락 → 실패, Isolation 0개 → 실패, Isolation skipped=1 → 실패, Application 리포트 누락 → 실패 (모두 종료코드 1) |

**확인하지 못한 CI 환경 사항 [가설]:**

- `ubuntu-latest`에서 Docker 사용 및 Testcontainers 1.21.4 동작, `mysql:8.0` 이미지 받기.
- 실제 `APPLICATION_YML` 시크릿 내용과의 상호작용 (테스트 단계에서는 파일이 없고, 우선순위는 위 재현으로 확인).
- `gradle/gradle-build-action@v2`가 `env`를 테스트 JVM에 전달하는지, 결과 경로(`build/test-results/test`)가 CI에서도 같은지.
- 환경변수(`SPRING_*` 등)가 CI 러너에 설정되어 있을 가능성 (DataSource는 `@ServiceConnection`이 우선하지만 다른 키는 환경변수가 yml보다 우선한다).
- 트리거가 `main` push뿐이라 `dev`/PR에서는 테스트가 돌지 않는다 (변경하지 않음).

### PR 테스트 워크플로와 공용 검증 스크립트 (B-013) [로컬 검증만, 실제 GitHub Actions 미실행]

**구조:**

| 파일 | 역할 |
|---|---|
| `scripts/verify-tests.sh` | 테스트 리포트(JUnit XML) 검사. `bash scripts/verify-tests.sh [결과 디렉터리]`. 실패 원인을 모두 출력하고 `exit 1` |
| `.github/workflows/pr_test.yml` (신규) | `pull_request`(대상 `main`, `dev`) → checkout → JDK 17 → `./gradlew test`(`REQUIRE_DOCKER=true`) → `verify-tests.sh` → 실패 시 리포트 업로드. 배포·시크릿 없음, `permissions: contents: read`, `concurrency`(PR 번호별, 이전 실행 취소), `timeout-minutes: 20` |
| `.github/workflows/dev_deploy.yml` | 확인 단계의 인라인 스크립트를 `bash scripts/verify-tests.sh` 호출로 교체. 트리거(`push` `main`), deploy job, 단계 순서는 그대로 |
| `.gitattributes` | `*.sh text eol=lf` 추가 (Windows 체크아웃에서도 스크립트가 LF 유지) |

**`verify-tests.sh` 검사 (이전 인라인 스크립트와 같거나 더 강하다):** 필수 클래스 `PharmquestApplicationTests`, `TestConfigIsolationTests` 각각 (1) 리포트 존재 (2) 실행 ≥ 1 (3) `skipped=0` (이전과 동일), 전체 리포트 `skipped=0` (이전과 동일), 추가로 (4) 필수 클래스의 failures/errors=0 (5) 리포트 형식 읽기 실패 감지 (6) 결과 디렉터리 없음/빈 디렉터리 감지. 누락 시 원인 후보와 존재하는 리포트 목록을 출력한다. 테스트 개수는 고정하지 않는다.

**액션 버전 검토:**

- `pr_test.yml`은 `actions/checkout@v4`, `actions/setup-java@v4`, `actions/upload-artifact@v4`를 쓰고 Gradle은 `./gradlew`를 직접 실행한다(별도 Gradle 액션 없음).
- 릴리스 페이지에서 확인한 것: checkout은 v7.0.1, setup-java는 v6.2.0이 최신이며 setup-java v4는 "deprecation warning 추가" 상태. 따라서 v4는 **동작은 하지만 최신이 아니다** [확인: 릴리스 페이지]. 각 버전의 Node 런타임 요구사항은 확인하지 못했다 [가설].
- `gradle/gradle-build-action`은 `gradle/actions/setup-gradle`로 대체되었다고 문서에 명시되어 있다 [확인: 공식 저장소]. `dev_deploy.yml`의 `checkout@v3`, `setup-java@v3`, `gradle-build-action@v2`는 이번에 바꾸지 않았다. 갱신은 별도 작업이다.

**로컬 검증 결과 [확인]:**

| 항목 | 결과 |
|---|---|
| 두 워크플로 YAML | SnakeYAML 파싱 성공. `dev_deploy.yml`의 `on`(`push`→`main`), `deploy` job(`needs: build`), 시크릿 사용, 단계 순서 변화 없음. `git diff`상 변경은 확인 단계 한 곳 |
| `REQUIRE_DOCKER=true` 전체 테스트 | 63 PASS, 0 FAIL, 0 SKIPPED (종료코드 0) |
| 공통 스크립트, 실제 결과 | 종료코드 0 |
| 스크립트 변형 | Isolation 리포트 누락·0개·skipped=1·failures=1, 다른 클래스 skipped=2, 형식 깨짐, 디렉터리 없음, 빈 디렉터리 → 모두 종료코드 1과 원인 메시지 |
| 비호환 시뮬레이션(`-Dapi.version=1.32`), 환경변수 없음 | Gradle 종료코드 0, 통합 SKIPPED → 스크립트 종료코드 1 |
| 비호환 시뮬레이션 + `REQUIRE_DOCKER=true` | Gradle 종료코드 1 |

**실제 GitHub Actions 실행 후 확인할 항목 [미확인]:**

1. PR 생성 시 `PR Test`가 실행되는지, `dev_deploy.yml`(push `main`만)이 실행되지 않는지.
2. `ubuntu-latest`에서 Docker 사용, Testcontainers 1.21.4 연결, `mysql:8.0` 이미지 받기, 통합 테스트 6개 실행.
3. `verify-tests.sh` 통과 (`ubuntu`의 `grep`/`sed`/`bash`에서 같은 결과인지), 리포트 경로 `build/test-results/test`.
4. `setup-java@v4`, `upload-artifact@v4`, `checkout@v4`의 경고 여부, 소요 시간(컨테이너 기동 포함).
5. 같은 PR에 새 커밋을 올리면 이전 실행이 취소되는지.
6. 실패 시 `test-reports` 아티팩트가 올라오는지.
7. 포크 PR에서의 동작(시크릿 없음).
8. `main` 푸시 시 `dev_deploy.yml`의 새 확인 단계(스크립트 호출) 동작.

### 아직 검증하지 않은 것

- JPA 저장·조회, flush, 커밋·롤백: Phase 1 범위 밖이었고 **B-010에서 테스트 DB 기준으로 검증함** (아래 섹션).
- **운영 DB와 운영 DDL**: 임시 스키마(`create-drop`)는 운영 DDL과 같다는 보장이 없다. `Medicine.splSetId`의 unique 제약(B-004), collation(B-007)은 운영 DDL이 필요하다.
- 운영 MySQL 버전.
- CI(`ubuntu-latest`)에서의 실행. `dev_deploy.yml`은 수정하지 않았다.

## 현재 확인된 사실 (구현 전 기록)

| 항목 | 내용 | 근거 |
|---|---|---|
| 실패 테스트 | `PharmquestApplicationTests.contextLoads` | 전체 테스트 실행 [확인] |
| 직접 원인 | `DataSourceBeanCreationException: Failed to determine a suitable driver class` (데이터소스 설정 없음) | 스택 트레이스 [확인] |
| 변경 전에도 실패 | P-001 변경 전 HEAD `a43e57b`를 별도 worktree에서 실행해도 같은 원인으로 실패 | P-001 [확인] |
| 설정 파일 | 저장소에 `src/main/resources`, `src/test/resources`가 없다. 코드가 요구하는 `@Value` 속성도 16개 모두 없다 | 파일 조사·코드 열람 [확인] |
| DB 연결 시도 | 일어나지 않음 (드라이버 결정 단계에서 실패) | 스택 트레이스 [확인] |
| 드라이버 | 런타임 의존성은 MySQL(`mysql-connector-j`)뿐이고 인메모리 DB 의존성은 없다 | `build.gradle` [확인] |

전체 45개 중 `contextLoads`를 제외한 44개는 Spring 컨텍스트 없이 실행되어 통과했다 (P-001 관련 30개 + P-002 14개를 포함, 2026-11 기준 로컬 실행).

## CI와의 관계

- `.github/workflows/dev_deploy.yml`은 `main`에 push될 때 `./gradlew build`를 실행하고, 그 전에 `secrets.APPLICATION_YML`로 `src/main/resources/application.yml`을 생성한다 [확인: 워크플로 파일].
- 따라서 CI에서 `contextLoads`는 **로컬과 다른 조건**으로 실행된다. 그 결과(통과 여부, 시크릿이 가리키는 DB 종류와 접근 가능 여부)는 **확인하지 못했다 [가설]**.
- CI가 시크릿 DB에 연결해 통과하는 구조라면 테스트가 외부 상태에 의존하는 것이므로, 환경 개선 시 이를 대체할지 판단이 필요하다.

## 해결 대안 (모두 미검증, 환경 변경은 승인 필요)

| 대안 | 새 의존성 | DB 구성 | 장단점 |
|---|---|---|---|
| A. H2 인메모리 (MySQL이 아닌 다른 DB 엔진을 흉내 냄) + `src/test/resources/application.yml` | H2 (테스트 전용) | 불필요 | 가장 간단. MySQL 전용 문법·`columnDefinition = "TEXT"` 호환 확인 필요. 16개 속성은 더미 값 |
| B. Testcontainers MySQL (Docker로 실제 MySQL을 띄움) | Testcontainers | Docker 필요 | 운영과 동일한 DB. 느리고 Docker가 없는 환경(일부 CI)에서는 불가 |
| C. 슬라이스 테스트로 대체 (`@WebMvcTest` 등) | 없음 | 불필요 | 전체 컨텍스트 로딩 검증은 사라진다 |
| D. 더미 프로퍼티만 추가 | 없음 | 불필요 | DataSource가 없어 JPA 리포지토리 때문에 실패할 가능성이 높다 |

`contextLoads`를 `@Disabled`로 숨기는 것은 대안이 아니다 (실패 은폐).

## 향후 CI 적용 시 고려

1. 현재 CI의 시크릿 기반 `application.yml`과 테스트용 설정을 **분리**한다 (`src/test/resources`는 테스트 전용).
2. 테스트가 운영·개발 DB와 외부 API(FDA, 번역, 이미지)에 닿지 않게 한다.
3. 번역·이미지용 `Executor` 빈과 `@Value` 속성 16개를 테스트에서 어떻게 공급할지 정한다.
4. 새 의존성을 추가하면 CI 캐시 삭제 단계(`rm -rf ~/.gradle/caches`) 때문에 매번 다운로드된다. 빌드 시간 영향을 확인한다.
5. 전체 `build`가 이 테스트 하나 때문에 항상 실패하면, 새 회귀를 실패로 구분할 수 없다. 해결 전까지는 기존 실패 이름을 보고서에 명시한다.

## 승인이 필요한 사항

- 새 테스트 의존성 (A, B)
- `src/test/resources` 설정 추가
- CI 워크플로 변경

## B-010: JPA 저장·조회·롤백 검증

**[확인]** 로컬 실행: `mysql:8.0`(서버 8.0.46), 격리 수준 REPEATABLE-READ, `sql_mode`에 `STRICT_TRANS_TABLES` 포함. 테스트 스키마는 `create-drop`(엔티티 기준)이며 **운영 DDL과 같다는 보장이 없다**.

코드 커밋: `9d9e852`.

방법: 테스트 메서드에 `@Transactional`을 쓰지 않고 `TransactionTemplate`으로 경계를 직접 제어한다. 커밋 여부는 `DbTestSupport`가 여는 **별도 JDBC 연결**(커밋된 데이터만 보임)로, INSERT/UPDATE 실행 시점은 Hibernate `Statistics`로 본다.

| ID | 확인한 내용 (테스트 DB 기준) |
|---|---|
| J1 | IDENTITY: `save()` 직후 INSERT 1회·id 채워짐, 커밋 전 다른 연결에는 0행 |
| J2 | `flush()`는 INSERT를 다시 실행하지 않음 |
| J3 | 변경 감지 UPDATE는 flush 때 실행, 다른 연결에는 commit 후에야 보임 |
| J4 | 같은 트랜잭션의 `existsBySplSetId`가 미커밋 `save()`를 봄 (P-002 가설 확인) |
| J5, J6 | commit 후 가시, 예외 롤백 시 이미 실행된 INSERT도 취소 |
| J7 | 롤백해도 AUTO_INCREMENT는 되돌아가지 않음 |
| J8 | `saveAll` 중간 실패: 앞 INSERT는 실행됐으나 전체 롤백 |
| M1~M3 | 전 필드 왕복(한글·enum·TEXT), `varchar(255)`·`TEXT(65,535)` 경계와 `Data too long` 거부 |
| M4, M4b | **현행 동작 기록**: unique 없음 → 중복 2행 저장, 이후 `findBySplSetId`는 예외 (B-014). 올바른 요구사항으로 확정한 것이 아님 |
| M5 | 컬럼 collation `utf8mb4_0900_ai_ci` → `existsBySplSetId("ABC")`가 `abc`를 찾음 (Set은 대소문자 구분: B-007 근거, 운영 collation 미확인) |
| S1~S3, S10 | `saveOtherMedicines` 정상·응답 내 중복·기존 ID 건너뛰기·무효 ID 사전 필터 |
| S4~S6 | 한도 미달·제약 위반 시 **이미 `save()`한 행까지 롤백**, 재호출 정상 |
| S7~S9 | `saveMedicinesByCategory`: 정상, 한도 미달은 INSERT 없이 실패, `saveAll` 중 제약 위반은 롤백 |
| S11 | 저장된 ID는 재호출 시 모두 '이미 존재'로 판정 (추출 ID == 저장 ID를 실제 DB까지 확인, B-006 C안) |

서비스 테스트(S)의 Mock 범위: `MedicineRepository`(FDA·DailyMed 호출)와 `TranslationService`만 Mock. 서비스·컨버터·JPA·MySQL은 실제. FDA 응답은 테스트가 만든 JSON이다.

**아직 검증하지 못한 것**: 운영 DDL(unique/index/charset/collation/컬럼 타입), 운영 MySQL 버전·`sql_mode`·격리 수준, 운영 데이터의 중복 행, 실제 동시 요청(B-004), 실제 openFDA 응답, CI 실행 시간(새 서비스 테스트는 `@MockBean`으로 별도 컨텍스트·컨테이너를 추가로 띄운다).
