# P-002 `saveOtherMedicines`의 식별자 추출 오류와 요청 내 중복 저장

- 상태: 구현·테스트 완료, 로컬 커밋됨 (코드 `fbd33cc`). 문서 커밋의 해시는 자기 참조를 피하려고 기록하지 않는다.
- 관련: [P-001-retry-duplicate](P-001-retry-duplicate.md) · [jsonnode-id-extraction](../concepts/jsonnode-id-extraction.md) · [request-deduplication](../concepts/request-deduplication.md) · [P-001-decision](../decisions/P-001-decision.md) · [backlog](../backlog.md) (B-001)
- 대상 코드: `MedicineServiceImpl.saveOtherMedicines` (`POST /medicine/save/other`)
- 테스트: `MedicineServiceImplSaveOtherTest` (14개)

## 흐름: P-001 지식 → P-002 분석 → 설계 → 테스트 → 결과

### 1. P-001에서 그대로 재사용한 것

| 지식 | 적용 |
|---|---|
| 배열 노드의 `asText(default)`는 `""` ([jsonnode-id-extraction](../concepts/jsonnode-id-extraction.md)) | 같은 표현식이 이 메서드에도 있어 `MedicineConverter.extractSplSetId`로 교체 |
| 무효 ID는 항목만 건너뛰고 `medicine.raw.invalid.count` 증가 ([P-001-decision](../decisions/P-001-decision.md)) | 같은 정책과 같은 지표 |
| Set은 처리 성공 후에만 기록 ([request-deduplication](../concepts/request-deduplication.md)) | `save()`가 정상 반환된 뒤에만 기록 |
| 확인 순서: Set → DB → 변환 | 동일 |
| ID별 검증, 실행 전 PASS/FAIL 단정 금지 | 테스트 설계에 적용 |

### 2. P-002의 저장 방식 때문에 달라진 판단

| P-001 | P-002 |
|---|---|
| 후보 목록에 모았다가 `saveAll` | 건별 `medRepository.save()` 즉시 저장 |
| "후보 추가 후" 기록 | "`save()` 정상 반환 후" 기록. **Set 기록은 DB 커밋 완료를 의미하지 않는다** (메서드 전체가 `@Transactional`이라 이후 롤백될 수 있고, 롤백되면 요청 전체가 실패하므로 Set도 함께 버려진다). |
| `hasRequiredRawFields` 사전 검사 있음 | 사전 검사 없음. 무효 ID는 변환(번역·이미지 외부 호출) **이전에** 걸러야 비용이 낭비되지 않는다. |
| 테스트: `TransactionTemplate` 모사와 `saveAll` 검증 | 해당 없음. `save()` 호출을 검증 |

### 3. 구현 전 확인 [확인: 코드 열람]

- `save()` 예외는 안쪽에서 잡히지 않고 바깥 `catch`에서 `RuntimeException`으로 감싸져 전파된다. 반복문은 계속되지 않는다.
- `isValidSaveMedicineDetail`은 `splSetId`를 검사하지 않는다 (`brandName`~`warnings` 9개 필드). 그래서 ID 검증은 별도로 필요했다.
- 저장되는 `splSetId`는 `dto.getSplSetId()`(컨버터의 `getFirstValue`)이고 추출 ID와 규칙이 같다. 단 서비스 테스트에서 컨버터가 Mock이므로 자동 테스트가 일치를 증명하지는 않는다 ([backlog](../backlog.md) B-006과 같은 한계).

### 4. 수정 [확인: diff]

`saveOtherMedicines` 한 곳, +12/-1줄: `extractSplSetId` 교체, null이면 지표 증가 후 `continue`, 요청 단위 `Set<String> savedSplSetIds`, `save()` 직후 Set 기록. 트랜잭션 구조, 건별 `save`, 페이지네이션, 예외 처리는 변경하지 않았다.

## 테스트 결과

### 수정 전 [확인]

14개 중 12개 FAIL, 2개(T5 정상 회귀, T6b 전부 무효 베이스라인) PASS.

### 수정 후 [확인]

14개 모두 PASS. P-001 관련 30개도 PASS. 전체 45개 중 44 PASS, `contextLoads` 1 FAIL (P-001 때와 같은 기존 실패, [backlog](../backlog.md) B-005).

### 수정 요소별 기여 [확인: Set만 임시 제거 후 실행]

| 테스트 | A(추출·무효 제외)만 | A + Set |
|---|---|---|
| T1 배열 ID가 검사에 전달 | PASS | PASS |
| T2 DB에 있는 ID 제외 | PASS | PASS |
| T4 무효 ID 6종 제외 | PASS | PASS |
| T5, T6b 회귀 | PASS | PASS |
| T3b 저장분을 DB가 즉시 본다는 **가정** 하의 중복 | PASS | PASS |
| T3a 중복 방지 (DB 항상 false) | **FAIL** | PASS |
| T3c 한 응답 내 중복 | **FAIL** | PASS |
| T6a 서로 다른 약 부족 시 예외 | **FAIL** | PASS |

## 가설 (미검증)

- **[B-010에서 테스트 DB 기준 확인됨: [test-environment](../test-environment.md#b-010-jpa-저장조회롤백-검증) J1·J4·S4]** (아래는 확인 전 가설 기록) IDENTITY 전략에서 `save()`가 즉시 INSERT를 실행하고 같은 트랜잭션의 `existsBySplSetId`가 이를 보는지(auto flush). T3b는 이를 Mock으로 **모사**한 것이라 증거가 아니다. 실제 확인에는 JPA와 DB(H2/Testcontainers 등, 새 의존성)가 필요하다. 이 가설이 맞더라도 Set은 DB 왕복을 줄이고 DB 동작에 의존하지 않게 하므로 유지한다.
- **[가설]** 실제 컨버터가 빈 배열에서 NPE를 던지는 경로는 이제 `extractSplSetId`가 먼저 걸러 `saveOtherMedicines`에서는 변환에 도달하지 않는다. 다만 서비스 테스트의 컨버터 Mock은 이를 검증하지 못한다.
- **[가설]** 운영 DB에 이미 `""` 키로 저장된 중복 행이 있는지.

## 남은 위험

- 동시 요청, DB unique 제약 없음 ([backlog](../backlog.md) B-004).
- 재시도가 같은 10건을 다시 가져옴 (B-003). 유효 항목이 limit에 못 미치면 3회 반복 후 실패한다.
- 메서드 전체 `@Transactional` 안에서 외부 호출을 하고, 한도 미달 시 이미 저장한 행이 롤백된다 (이번 범위 제외).
- 무효 ID 지표는 재시도 때마다 같은 항목에서 다시 증가할 수 있다.
- `medicine.raw.invalid.count`는 두 저장 API가 같은 이름으로 공유하고 태그로 구분하지 않는다. `saveMedicinesByCategory`에서는 "필수 원본 필드 또는 ID 불량", 여기서는 "ID 불량"만 센다. 의미는 같은 계열이지만 범위가 달라 API별 비교에는 쓸 수 없다.
- 저장된 `Medicine.splSetId`와 추출 ID의 일치는 코드 열람으로만 확인했다 (컨버터 Mock, B-006).
- JPA flush, DB 커밋, 롤백, unique 제약 동작은 **검증하지 않았다** (실제 DB 미사용).
