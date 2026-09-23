# PharmQuest 의약품 AI 카테고리 작업 인수인계

작성일: 2026-09-23 (Asia/Seoul)
작업 브랜치: `codex/ai-medicine-category`
작업 폴더: `C:\backend\PharmQuest_BE`

## 1. 목표

기존에는 의약품 카테고리가 문자열 키워드와 요청 파라미터에 의해 사실상 하드코딩되어 있었다. 이를 다음 구조로 개선하는 것이 목표다.

```text
의약품 원문 수집
→ AI가 효능·사용 목적을 읽고 카테고리 후보와 원문 근거 생성
→ 서버가 응답 형식과 근거를 재검증
→ 별도 이력 테이블에 저장
→ 기존 규칙과 AI 결과 비교 및 사람 검수
→ 검증 후에만 실제 medicine.category 반영
```

현재까지는 **데이터 수집, 로컬 실행 환경, 평가 기반, AI 분류 후보 저장 구조**를 구현했다. 실제 OpenAI 호출은 API 키가 없어 아직 수행하지 않았다. AI 결과가 기존 카테고리를 자동으로 덮어쓰는 기능도 의도적으로 만들지 않았다.

## 2. 확인한 기존 코드 문제

- `MedicineCategoryMapper.getCategory(...)`는 대소문자를 구분하는 `contains()` 규칙이며 첫 번째 일치 결과를 반환한다.
- 이 규칙은 효능 설명 전체인 `indicationsAndUsage`를 사용하지 않는다.
- `MedicineServiceImpl.saveMedicinesByCategory(...)`는 실제 내용에서 분류한 결과 대신 요청받은 카테고리를 저장한다.
- 국내 의약품 경로도 카테고리 키워드로 먼저 검색한 후 요청 카테고리를 DTO에 그대로 넣는다.
- 국내 변환기는 `indicationsAndUsage`에 효능이 아닌 보관 방법을 넣고, `itemSeq`도 DTO에 채우지 않는 문제가 있다. 이 국내 경로는 아직 수정하지 않았다.
- 목록 DTO에는 효능 설명이 없어서 Swagger 목록만 보면 설명이 저장되지 않은 것처럼 보인다. 상세 API에는 `purpose`, `indicationsAndUsage`, `activeIngredient`, `dosageAndAdministration`, `warnings`가 포함된다.

## 3. Git과 로컬 실행 환경

`dev`에서 다음 브랜치를 만들었다.

```text
codex/ai-medicine-category
```

현재 변경사항은 아직 커밋되지 않았다. 집에서 다른 PC로 이어서 하려면 출발 전에 반드시 커밋하고 원격 저장소에 push하거나 작업 폴더 전체를 옮겨야 한다.

MySQL 8.4를 위한 [compose.yaml](../compose.yaml)을 추가했다.

```powershell
docker compose up -d --wait mysql
```

기본 연결 정보:

```text
host: 127.0.0.1
port: 3307
database: pharmquest
username: pharmquest
password: pharmquest-local
```

DB는 `pharmquest-local_mysql-data` named volume에 보존된다. `docker compose down -v`는 볼륨을 삭제하므로 사용하지 않는다.

로컬 프로필은 [application-local.yml](../src/main/resources/application-local.yml)에 있다. OAuth, S3 실제 업로드, Google 번역은 로컬 기본값에서 비활성화했다. 서버는 루프백 `127.0.0.1:8080`에만 바인딩된다.

이 PC의 기본 `java`는 Java 8이므로 JDK 17을 명시했다.

```powershell
$env:JAVA_HOME = 'C:\Users\SSAFY\.jdks\ms-17.0.20.1'
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

다른 PC에서는 설치된 JDK 17 경로로 바꾼다.

자세한 실행 방법은 [local-development.md](local-development.md)에 있다.

## 4. 실제 의약품 데이터 적재

openFDA 일반의약품 라벨을 영어 원문 그대로 가져오는 local 전용 API를 추가했다.

```http
POST /medicine/local/import/fda?limit=30&skip=0
```

구현 파일:

- `LocalMedicineImportController.java`
- `LocalMedicineImportService.java`
- `MedicineRepository.java`

동작:

- 번역 API와 이미지 API를 호출하지 않는다.
- `set_id`를 `splSetId`로 저장한다.
- `purpose`, `indicationsAndUsage`, `activeIngredient`, `dosageAndAdministration`, `warnings` 원문을 저장한다.
- 원문 배열은 줄바꿈으로 연결하며 임의로 자르지 않는다.
- 필수 식별자·제품명·효능 원문이 없는 건은 제외한다.
- 같은 `splSetId`는 중복 저장하지 않는다.
- FDA 원본 응답은 Git에서 제외된 `.local/fda-snapshots/`에 보관한다.
- 기존 행에 `route`가 없으면 동일 데이터를 다시 수집할 때 경로만 보강하도록 구현했다.

처음 30건 적재 결과:

```text
fetched: 30
saved: 30
duplicates: 0
invalid: 0
```

같은 요청 재실행 결과:

```text
saved: 0
duplicates: 30
```

DB 확인 당시 결과:

```text
total: 30
unique spl_set_id: 30
purpose populated: 30/30
indications_and_usage populated: 30/30
active_ingredient populated: 30/30
dosage_and_administration populated: 30/30
warnings populated: 30/30
```

기존 VARCHAR(255)가 실제 성분 원문을 담지 못해 관련 컬럼을 TEXT로 변경했다. 이미 생성된 DB에는 [medicine-source-text.sql](sql/medicine-source-text.sql)을 적용했다.

현재 30건의 임시 규칙 카테고리 분포는 다음과 같았다.

```text
OTHER: 20
ANTISEPTIC: 3
PAIN_RELIEF: 3
EYE_DROPS: 3
DIGESTIVE: 1
```

이 분포는 무작위 첫 30건의 기존 규칙 결과다. AI 정확도 평가 데이터나 정답으로 사용하면 안 된다.

## 5. AI 모델 선정

기본 후보는 `gpt-5.4-nano-2026-03-17`, 비교 후보는 `gpt-5.4-mini-2026-03-17`로 고정했다.

선정 근거:

- OpenAI 공식 문서가 GPT-5.4 nano의 주요 용도로 분류와 데이터 추출을 명시한다.
- 두 모델 모두 Structured Outputs를 지원한다.
- nano는 반복 분류 비용이 낮다.
- nano가 최종 모델이라는 결론은 아직 아니다. 같은 검수 데이터에서 nano와 mini의 Macro-F1, 카테고리별 Recall, 보류율, 근거 오류율, 비용, p95 지연을 비교한 후 결정한다.

공식 문서:

- https://developers.openai.com/api/docs/models/gpt-5.4-nano
- https://developers.openai.com/api/docs/models/gpt-5.4-mini
- https://developers.openai.com/api/docs/guides/structured-outputs

별도 번역 없이 FDA 영어 원문을 그대로 모델에 전달한다. 번역 오류와 번역 API 비용을 분류 평가에서 제거하기 위한 선택이다.

## 6. 분류 정책

[policy-v1.md](ai-category/policy-v1.md)에 분류 정책 초안을 작성했다.

모델이 선택할 수 있는 값:

```text
PAIN_RELIEF
DIGESTIVE
COLD
ALLERGY
ANTISEPTIC
MOTION_SICKNESS
EYE_DROPS
OTHER
```

`ALL`은 조회 옵션이므로 모델 출력에서 제외한다.

주요 원칙:

- `purpose`, `indicationsAndUsage`, `route`의 명시된 문구로 판단한다.
- 성분만 보고 외부 지식으로 효능을 추정하지 않는다.
- 경고, 부작용, 금기에서 발견한 증상을 치료 효능으로 판단하지 않는다.
- 설명이 충분하지만 기존 8개 범주에 해당하지 않을 때만 OTHER를 사용한다.
- 정보 부족, 모순, 대표 카테고리 결정 불가는 `category=null`, `needsReview=true`로 반환한다.
- 눈에 직접 투여하는 제품은 EYE_DROPS를 우선한다.
- 복합 감기 증상 완화가 명시되면 포함된 진통 성분보다 COLD를 우선한다.

## 7. AI 분류 구현 구조

구현 디렉터리:

```text
src/main/java/com/pharmquest/pharmquest/domain/medicine/ai/
```

주요 파일:

- `ClassificationContract.java`: 프롬프트, JSON Schema, 모델 ID, 입력 생성, 근거 검증
- `OpenAiClassificationClient.java`: OpenAI Chat Completions 호출, 타임아웃, 제한된 재시도
- `MedicineClassificationService.java`: 대상 조회, 입력 해시, 캐시, 호출, 결과 검증, 이력 저장
- `ClassificationHistory.java`: 분류 실행 이력 엔티티
- `ClassificationHistoryRepository.java`: 성공/검토 결과 캐시 및 이력 조회
- `MedicineClassificationController.java`: 상태, 입력 미리보기, 분류 실행, 이력 조회 API

모델 입력:

```json
{
  "purpose": "...",
  "indicationsAndUsage": "...",
  "activeIngredient": "...",
  "route": "..."
}
```

기존 `medicine.category`는 모델 입력에서 제외한다. 기존 분류에 모델이 끌리는 것을 막기 위함이다.

모델 출력 계약:

```json
{
  "category": "ANTISEPTIC",
  "evidence": [
    {"field": "purpose", "quote": "First aid Antiseptic"}
  ],
  "needsReview": false,
  "reviewReason": null
}
```

서버는 Structured Outputs만 믿지 않고 다음을 다시 검사한다.

- 허용된 카테고리인지
- `ALL`이 아닌지
- 성공 결과에 근거가 1개 이상 있는지
- 근거 필드가 `purpose`, `indicationsAndUsage`, `route` 중 하나인지
- 근거 문구가 실제 입력에 존재하는 정확한 연속 문자열인지
- 검토 상태와 `category`, `reviewReason` 조합이 일관적인지
- 응답이 거부되거나 토큰 제한으로 잘리지 않았는지

상태 값:

```text
SUCCESS
REVIEW_REQUIRED
INPUT_INVALID
INVALID_RESPONSE
API_ERROR
```

API 오류의 상세 메시지나 HTTP 헤더는 DB에 저장하지 않는다. API 키나 요청 원문이 예외에 섞여 기록되는 것을 막기 위해 정규화한 오류 코드만 저장한다.

같은 의약품 입력, 모델, 프롬프트 버전, 정책 버전, 스키마, 추론 설정이면 SHA-256 캐시 키가 같다. 성공 또는 검토 필요 결과는 재사용하며 API 오류와 잘못된 응답은 재사용하지 않는다.

## 8. 추가한 API

API 키 설정 여부와 버전 확인:

```http
GET /medicine/local/ai/status
```

비용 없이 실제 모델 입력 확인:

```http
GET /medicine/local/ai/preview?medicineId=7
```

한 건 nano 분류:

```http
POST /medicine/local/ai/classifications
Content-Type: application/json

{"medicineIds":[7],"model":"NANO"}
```

mini 비교:

```http
POST /medicine/local/ai/classifications
Content-Type: application/json

{"medicineIds":[7],"model":"MINI"}
```

이력 조회:

```http
GET /medicine/local/ai/classifications?medicineId=7&page=0&size=30
```

한 요청에 1~30개의 고유한 양수 ID만 허용한다. 순차 처리하며 각 결과를 독립적으로 저장한다. 성공해도 `medicine.category`는 변경하지 않는다.

## 9. OpenAI API 키 설정

예제 파일:

```text
config/application-local-secrets.properties.example
```

아래 실제 파일은 `.gitignore`에 포함되어 있다.

```text
config/application-local-secrets.properties
```

다음 한 줄을 실제 파일에 추가한다.

```properties
openai.api-key=실제_API_키
```

또는 `OPENAI_API_KEY` 환경변수를 사용할 수 있다. 키를 코드, 문서, 채팅, Git 커밋에 넣지 않는다. 키 설정 후 서버를 재시작해야 한다.

## 10. 평가 기반

다음 문서와 테스트를 추가했다.

- [AI 카테고리 작업 개요](ai-category/README.md)
- [분류 정책](ai-category/policy-v1.md)
- `CategoryEvaluationTest.java`
- `CategoryMetricsTest.java`
- `src/test/resources/medicine-category/synthetic-v1.json`

평가 도구는 정확도, 클래스별 precision/recall/F1/support, Macro-F1, 혼동행렬, 오분류 ID를 생성한다.

가상 사례 16건에서 기존 규칙은 다음 결과가 나왔다.

```text
accuracy: 0.50
macro-F1: 0.58125
```

이 데이터는 기존 규칙의 실패 사례를 재현하기 위해 직접 작성한 가상 데이터다. 실제 정확도나 이력서 수치로 사용하면 안 된다.

## 11. 테스트 상태

AI 구현 전 로컬 MySQL이 실행 중일 때 전체 테스트 12개가 통과했다.

AI 구현 후 DB가 필요 없는 다음 단위 테스트 묶음은 통과했다.

- `ClassificationContractTest`
- `MedicineClassificationServiceTest` (입력 미리보기 테스트를 추가하기 전 상태)
- `LocalMedicineImportServiceTest`
- `CategoryEvaluationTest`
- `CategoryMetricsTest`

이후 Docker와 MySQL을 다시 시작하여 최종 전체 테스트를 실행했다. 총 19개 테스트가 모두 통과했고 실행 JAR도 생성됐다.

```text
ClassificationContractTest: 3
MedicineClassificationServiceTest: 4
CategoryEvaluationTest: 4
CategoryMetricsTest: 3
LocalMedicineImportServiceTest: 4
PharmquestApplicationTests: 1
합계: 19, 실패: 0
```

```powershell
$env:JAVA_HOME = '설치된_JDK_17_경로'
docker compose up -d --wait mysql
$env:SPRING_PROFILES_ACTIVE = 'local'
.\gradlew.bat clean test bootJar --no-daemon
Remove-Item Env:SPRING_PROFILES_ACTIVE
```

## 12. 현재 실행 상태

마지막 확인 시점에:

```text
Docker MySQL: healthy, 127.0.0.1:3307
Spring 서버: UP, 127.0.0.1:8080
서버 PID: .local/server.pid 안의 19388
OpenAI API 키: 미설정
```

기존 30건에 대해 FDA import를 재실행했고 다음과 같이 투여 경로를 보강했다.

```text
fetched: 30
saved: 0
updated: 30
duplicates: 30
invalid: 0
```

AI 상태와 입력 미리보기 API를 실제 서버에서 확인했다. 키가 없는 상태에서 분류 요청은 HTTP 503을 반환했고 분류 이력 건수는 요청 전후 모두 0이었다.

DB의 30건은 이 PC의 Docker named volume에만 들어 있다. Git에는 DB 데이터가 포함되지 않는다. 다른 PC에서는 FDA import API로 다시 30건을 적재한다.

## 13. 집에서 바로 이어갈 순서

1. 브랜치와 변경사항을 가져온다.
2. JDK 17과 Docker Desktop을 실행한다.
3. `docker compose up -d --wait mysql`을 실행한다.
4. 전체 테스트와 `bootJar`를 실행한다.
5. 서버를 local 프로필로 실행한다.
6. 새 PC라면 `POST /medicine/local/import/fda?limit=30&skip=0`으로 데이터를 적재한다.
7. 같은 import를 한 번 더 실행해 `saved=0`, `duplicates=30`인지 확인하고 `route`를 보강한다.
8. `GET /medicine/local/ai/status`에서 `configured=false`인지 먼저 확인한다.
9. `GET /medicine/local/ai/preview?medicineId={실제 ID}`로 모델 입력을 확인한다.
10. OpenAI API 키를 ignored secrets 파일에 넣고 서버를 재시작한다.
11. 의약품 한 건만 NANO로 분류한다.
12. 이력에서 근거, 모델 ID, 토큰 수, 상태를 확인한다.
13. 같은 요청을 다시 보내 `reused=true`인지 확인한다.
14. 같은 의약품을 MINI로 실행해 nano와 비교한다.
15. 문제가 없으면 30건을 처리한다.

새 DB에서는 ID가 반드시 6부터 시작한다는 보장이 없다. `/medicine/lists` 응답의 실제 `medicineTableId`를 사용한다.

## 14. 다음 개발 과제

우선순위 순서:

1. Docker와 MySQL을 다시 시작하고 최종 전체 테스트 통과 확인
2. 기존 30건의 `route` 보강 확인
3. OpenAI 키 설정 후 한 건 NANO 실호출
4. 응답 파싱과 이력 저장 확인
5. 같은 건 재호출 시 캐시 동작 확인
6. MINI 비교 호출
7. 30건 전체 실행
8. 사람 검수 UI 또는 검수용 CSV/JSON export 추가
9. 카테고리별 균형이 있는 실제 검수 데이터 200~300건 구축
10. 규칙/nano/mini 성능과 비용 비교
11. 검수 완료 결과만 `medicine.category`에 반영하는 별도 명령 추가
12. 운영 적용 전 Flyway/Liquibase 등 명시적 DB migration 도입과 DB unique 제약 검토

현재 30건은 동작 검증용이다. 카테고리 분포가 불균형하고 기존 규칙 결과가 정답이 아니므로, 이 데이터만으로 모델 성능이나 개선율을 이력서에 적으면 안 된다.

## 15. 커밋 전 확인

현재 작업 트리는 의도적으로 dirty 상태이며 커밋되지 않았다.

```powershell
git status --short
git diff --check
```

실제 비밀 파일이 추적되지 않는지 확인한다.

```powershell
git check-ignore config/application-local-secrets.properties
git check-ignore .local/server.log
```

최종 테스트가 통과하면 변경 파일을 검토한 후 커밋한다. `.local/`, 실제 secrets 파일, Docker volume 데이터는 커밋하지 않는다.
