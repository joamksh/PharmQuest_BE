# 로컬 개발 환경

JDK 17, Docker Desktop(Linux containers)을 사용한다. 작업 브랜치는 `codex/ai-medicine-category`이며 이전 단계의 평가 코드도 같은 작업 폴더에 있다.

## 실행

프로젝트 루트에서:

```powershell
docker compose up -d --wait mysql
# 설치한 JDK 17 경로로 지정. 이 PC의 기본 java는 Java 8이다.
$env:JAVA_HOME = 'C:\Users\SSAFY\.jdks\ms-17.0.20.1'
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

- 서버: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui/index.html
- 상태: http://localhost:8080/actuator/health
- 의약품: http://localhost:8080/medicine/lists?category=ALL&page=1&size=10
- MySQL: `127.0.0.1:3307`, DB/사용자 `pharmquest`, 비밀번호 `pharmquest-local`

DB와 서버는 루프백에 바인딩한다. 기본 암호와 JWT 키는 로컬 개발 전용이다. 테이블은 local 프로필의 `ddl-auto: update`로 생성한다. 실행 시 `local` 프로필을 명시해야 한다. 기존 배포 설정은 별도로 유지한다.

DB 데이터는 Docker named volume `pharmquest-local_mysql-data`에 보존된다. `docker compose stop mysql`로 중지하고 같은 up 명령으로 재시작한다. 일반 `docker compose down`도 볼륨을 보존한다. `down -v`는 데이터를 삭제하므로 사용하지 않는다.

## 외부 API 키

`config/application-local-secrets.properties.example`을 `config/application-local-secrets.properties`로 복사하고 실제 값을 입력한다. 실제 파일은 Git에서 제외하며 서버 재시작 때 반영된다. 키를 채팅이나 커밋에 넣지 않는다.

- 국내 수집: `openapi.medicine.api-key`에 공공데이터포털 디코딩 키 필요.
- 해외 수집: `fda.api.api-key`, Google 번역 키를 설정하고 `google.cloud.translate.enabled=true`로 변경. 기존 해외 저장 파이프라인은 번역·이미지 조회에도 의존한다.
- OAuth는 local에서 비활성화한다. S3는 외부 요청을 하지 않는 미지원 클라이언트를 사용하므로 업로드는 동작하지 않는다.
- 번역은 기본 비활성화이며 호출 시 설정 필요 오류를 낸다. 번역 성공이나 데이터 수집 성공으로 가장하지 않는다.

API 키 없이도 DB 연결, Swagger, 저장 데이터 조회를 확인할 수 있다. 실행 성공과 실제 의약품 적재 성공은 별개다.

## 데이터 적재 전 남은 작업

### 키 없이 FDA 원문부터 적재하기

local 프로필 전용 API를 추가했다. 공개 FDA 일반의약품 라벨을 영어 원문으로 저장하며 번역과 이미지 조회를 호출하지 않는다.

```powershell
Invoke-RestMethod -Method Post 'http://localhost:8080/medicine/local/import/fda?limit=30&skip=0'
```

`limit`은 가져올 라벨 수(1~100), `skip`은 시작 위치(0~25000)다. 응답의 `saved`가 실제 저장 건수이며 `updated`, `duplicates`, `invalid`를 별도로 확인한다. 같은 라벨 식별자가 DB 또는 현재 배치에 있으면 건너뛴다. 기존 데이터에 투여 경로가 비어 있으면 `updated`로 보강한다. 이 동시 실행 방지는 단일 local 서버에만 적용하며 DB 전역 unique 제약은 아니다. 운영 수집기로 사용하지 않는다.

제품명·식별자·효능 원문이 없거나 TEXT 용량을 초과한 라벨은 제외한다. 이미지 URL은 비워 두고 카테고리는 기존 규칙으로 계산한다. 원문 배열은 줄바꿈으로 연결하며 번역하거나 자르지 않는다. 긴 원문 저장을 위해 제품명·일반명·성분명·유효성분·목적 필드도 TEXT로 확장했다. 운영 DB에서는 별도의 스키마 마이그레이션 검토가 필요하다.

이미 생성된 VARCHAR(255) 컬럼은 Hibernate update가 확장하지 않을 수 있다. 이번 로컬 DB에는 [컬럼 확장 SQL](sql/medicine-source-text.sql)을 적용했다. 기존 데이터를 삭제하거나 재생성하지 않는 TEXT 확장이다.

원본 FDA 응답은 Git에서 제외된 `.local/fda-snapshots/`에 저장한다. DB의 `splSetId`와 원본의 `set_id`로 연결할 수 있다. 이 표본은 원문 기반 개발 데이터이며, 사람이 검수한 평가 정답이 아니다. 요청 카테고리별 균형 표본도 아니다.

### 국내/번역 저장 경로

국내 저장 경로의 `itemSeq` 누락과 효능/보관 방법 필드 매핑을 수정하고 중복 저장을 확인해야 한다. 기존 카테고리는 정답으로 간주하지 않는다. 외부 API 키가 준비되면 소량 POST로 저장 건수와 내용을 검증한 뒤 수집량을 늘린다.

```text
POST /medicine/save?category=PAIN_RELIEF&limit=10
POST /api/medicine/save/by-category?category=PAIN_RELIEF
```

## 검증

```powershell
.\gradlew.bat test --tests '*CategoryEvaluationTest' --tests '*CategoryMetricsTest'
Invoke-RestMethod http://localhost:8080/actuator/health
```

기본 `contextLoads` 테스트는 별도 서버 설정과 DB가 필요하므로 위 명령은 평가 테스트만 선택한다.

Docker MySQL이 실행 중이면 local 프로필로 전체 테스트도 실행할 수 있다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
.\gradlew.bat test
Remove-Item Env:SPRING_PROFILES_ACTIVE
```

이번 초기 설정에서는 서버를 백그라운드 JAR 프로세스로 실행했다. 로그는 `.local/server.log`, 오류 로그는 `.local/server-error.log`, PID는 `.local/server.pid`에 있다. IDE나 `bootRun`으로 실행 방식을 바꾸기 전에 해당 PID가 이 프로젝트의 Java 서버인지 확인하고 종료한다. 같은 8080 포트로 서버를 중복 실행하지 않는다.
