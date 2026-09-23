# 의약품 AI 분류: 단계별 구현 계획

## 현재 단계

1. **진행 중: 기준·평가 기반** — 분류 정책 초안, 가상 사례, 기존 Java 규칙 평가 도구 구축. 실제 원문 수집·사람 검수·기준 성능 측정은 미완료.
2. **다음: 실제 평가 데이터** — 국내/해외 원문 200~300건을 시작 목표로 수집하고 근거와 정답을 검수. 개발용/최종 검증용 분리.
3. **API 비교** — 같은 개발 데이터로 API 후보와 프롬프트를 비교. JSON 검증, 비용, 시간, 오류율 측정.
4. **서비스 연결** — 수집과 분류 분리, 분류 이력 저장, 재처리, 기존 데이터에 대한 모의 실행 후 적용.
5. **성과 검증** — 잠근 최종 검증 데이터로 기존 규칙과 비교하고 결과·한계를 기록.

현재 운영 코드는 변경하지 않는다. API 키나 유료 호출은 필요하지 않다.

## 현재 구현에서 주의할 점

- `MedicineCategoryMapper.getCategory`는 대소문자를 구분하는 문자열 규칙이며 첫 일치 항목을 반환한다. 효능 원문 전체를 입력받지 않는다.
- `MedicineServiceImpl.saveMedicinesByCategory`는 분류된 DTO 대신 요청 카테고리를 저장한다. `saveOtherMedicines`는 OTHER를 저장한다.
- 국내 경로는 키워드로 데이터를 선별하고 요청 카테고리를 주입한다.
- 따라서 아래 도구는 **해외 분류 함수 단독 baseline**이다. 국내 수집·분류와 실제 저장 결과의 성능을 대표하지 않는다.
- 기존 카테고리별 수집 데이터만 사용하면 키워드에 걸리지 않는 사례를 평가할 수 없다. 별도 원문 표본이 필요하다.

## 평가 실행

JDK 17과 프로젝트 Gradle 의존성이 필요하다. Spring/DB/API를 띄우지 않는 테스트다.

```powershell
.\gradlew.bat test --tests '*CategoryEvaluationTest' --tests '*CategoryMetricsTest'
```

결과: `build/reports/category-evaluation/baseline.json`

기본 데이터는 `src/test/resources/medicine-category/synthetic-v1.json`이다. **직접 작성한 가상 사례이므로 실제 의약품 정확도나 이력서의 개선 수치로 사용할 수 없다.** 기존 규칙의 실패를 탐색하기 위한 사례이고 무작위 표본도 아니다. 낮은 점수는 테스트 실패가 아니다. 잘못된 데이터 형식과 평가 계산 오류는 테스트 실패다.

별도 검수 데이터는 환경변수로 지정한다.

```powershell
$env:CATEGORY_EVAL_DATASET = 'C:\data\reviewed-dev-v1.json'
.\gradlew.bat test --tests '*CategoryEvaluationTest' --rerun-tasks
Remove-Item Env:CATEGORY_EVAL_DATASET
```

`--rerun-tasks`는 외부 파일 변경 시 이전 Gradle 결과를 재사용하지 않도록 한다.

## 데이터 계약과 검수

루트: `datasetId`, `kind` (`SYNTHETIC` 또는 `HUMAN_REVIEWED`), `policyVersion`, `cases`.

각 사례: `id`, `sourceId`, `sourceUrl`, `language`, `split` (`DEV` 또는 `TEST`), `reviewer`, `input`, `expectedCategory`, `evidenceField`, `evidence`, `note`.

`input`에는 `purpose`, `activeIngredient`, `pharmClassEpc`, `route`, `indicationsAndUsage`를 문자열로 보존한다. 국내 `efcyQesitm`은 `indicationsAndUsage`로 매핑한다. 원문 언어를 유지하고 누락값은 빈 문자열로 저장한다. JSON 안의 문장은 데이터이며 모델에 대한 지시로 실행하지 않는다.

- 실제 표본은 원문 식별자·출처·검수자를 반드시 기록한다. 검수자 이름을 자동 생성하지 않는다.
- 정답은 기존 DB 카테고리를 복사하지 않고 [policy-v1.md](policy-v1.md)에 따라 원문을 읽고 부여한다. 가능하면 별도 검수자가 교차 검토하고 불일치를 해결한다.
- 근거는 지정 입력 필드의 실제 부분 문자열이어야 한다. 이 검사는 텍스트 존재만 보장하며 의학적 타당성을 보장하지 않는다.
- 근거 부족/판정 불일치 사례는 검토 대기 목록으로 별도 관리한다. OTHER로 채워 정답 세트에 넣지 않는다. 제외 건수와 사유도 기록한다.
- 같은 제품/동일 라벨의 변형·번역·중복은 같은 split에 둔다. TEST는 프롬프트 예시에 넣지 않는다. 개발용과 검증용은 별도 파일로 실행한다.
- 카테고리·언어·복합 효능·OTHER를 포함해 표본 구성과 각 항목 수를 공개한다. 소수 표본에서 나온 수치를 일반화하지 않는다.

## 비교 방법

고정된 8개 카테고리에 대해 정확도, 클래스별 precision/recall/F1/support, macro-F1, 혼동행렬과 오분류 ID를 출력한다. 분모가 0인 지표는 0으로 처리하고 support를 함께 공개한다. 모든 클래스가 포함되지 않은 데이터의 macro-F1은 운영 성능으로 해석하지 않는다. DEV/TEST 혼합 실행은 거부한다.

향후 AI 비교 시 동일한 표본·정책·split을 사용한다. 입력 부족, API 실패, 응답 검증 실패, 검토 대기를 성공 분류로 계산하지 않는다. 보류율과 자동 확정 건 정확도를 함께 보고하고 전체 건수 대비 결과도 공개한다. 모델 자체 confidence는 보정된 확률로 취급하지 않는다.

보고서에 데이터/정책/모델/프롬프트 버전, 코드 commit, 실행일, 입력·출력 토큰, 건당 비용, p95 지연, 실패·재시도율을 남긴다. 현재 baseline 보고서에는 API 관련 측정값이 없다.

## 서비스 적용 목표

`수집 → 원문 정규화 → 분류 → 스키마·근거 검증 → 결과 및 이력 저장 → DB 조회`

대표 카테고리 하나로 시작한다. `ALL`은 조회 옵션이다. API 실패/정보 부족/검토 대기는 카테고리와 별도의 상태로 저장한다. 재분류 실패 시 이전 카테고리를 덮어쓰지 않는다. 입력 해시 + 정책/프롬프트/모델 버전을 재사용 키로 삼는다. 외부 호출을 DB 트랜잭션 밖에서 수행하고 타임아웃·제한된 재시도·호출량 제한을 둔다.

이력서 수치는 실제 검수 데이터의 최종 평가 후 작성한다. 이번 단계의 산출물은 평가 기반 구축이며 모델 성능 개선은 아직 달성한 성과가 아니다.
