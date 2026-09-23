# OpenAI 의약품 분류 구현

local 프로필 전용 1차 구현이다. 기본 후보는 `gpt-5.4-nano-2026-03-17`, 비교 후보는 `gpt-5.4-mini-2026-03-17`로 고정했다. 기존 `medicine.category`를 변경하지 않고 모든 결과를 `classification_history`에 기록한다.

## 설정

`config/application-local-secrets.properties`에 아래 값을 추가하고 서버를 재시작한다. 이 파일은 Git에서 제외된다.

```properties
openai.api-key=실제_API_키
```

## 실행 순서

상태 확인:

```text
GET /medicine/local/ai/status
```

API 키나 비용 없이 한 의약품의 실제 모델 입력을 먼저 확인할 수 있다. 응답의 `currentCategory`는 비교용이고 `modelInput`에는 포함되지 않는다.

```text
GET /medicine/local/ai/preview?medicineId=7
```

먼저 한 건만 nano로 실행한다. 유료 API 호출이다.

```http
POST /medicine/local/ai/classifications
Content-Type: application/json

{"medicineIds":[7],"model":"NANO"}
```

결과의 `status`, `proposedCategory`, `evidenceJson`, `actualModel`, 토큰 수를 확인한다. 같은 입력·모델·프롬프트·정책으로 다시 요청하면 성공 또는 검토 필요 결과를 재사용한다. API 오류나 잘못된 응답은 재사용하지 않는다.

한 건 검증 후 최대 30개 ID를 한 요청으로 순차 처리할 수 있다. mini 비교 시 같은 ID를 사용하고 `model`만 `MINI`로 바꾼다.

```text
GET /medicine/local/ai/classifications?medicineId=7&page=0&size=30
```

## 검증과 실패 처리

- 기존 카테고리는 모델 입력에서 제외한다.
- Structured Outputs 스키마로 카테고리·근거·검토 상태를 제한한다.
- 서버는 근거 문구가 실제 `purpose`, `indicationsAndUsage`, `route`의 연속 문자열인지 다시 확인한다.
- 거부, 잘린 응답, 스키마 불일치, 근거 불일치는 실패 이력으로 저장한다.
- 제공자 오류 메시지와 HTTP 헤더는 DB에 저장하지 않는다.
- API 호출 중 DB 트랜잭션을 열지 않는다. 각 결과를 독립적으로 저장해 일부 실패가 전체 결과를 지우지 않게 한다.
- API 키가 없으면 503으로 종료하며 호출이나 이력 생성을 하지 않는다.
- 성공 결과도 자동 반영하지 않는다. 사람 검수와 평가가 끝난 뒤 별도 반영 기능을 만든다.

`route`가 없는 기존 30건은 FDA import API를 같은 `skip=0`으로 다시 호출하면 중복 행을 만들지 않고 경로만 보강한다.
