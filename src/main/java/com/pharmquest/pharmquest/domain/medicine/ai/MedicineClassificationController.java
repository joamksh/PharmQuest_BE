package com.pharmquest.pharmquest.domain.medicine.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@Profile("local")
@RequestMapping("/medicine/local/ai")
@Tag(name = "의약품 AI 분류", description = "로컬 평가용: 기존 카테고리를 변경하지 않습니다.")
@RequiredArgsConstructor
public class MedicineClassificationController {
    private final MedicineClassificationService service;
    private final ClassificationHistoryRepository histories;
    private final OpenAiClassificationClient client;
    public record Request(List<Long> medicineIds, ClassificationContract.Model model) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("configured", client.configured(), "defaultModel", ClassificationContract.Model.NANO.id,
                "comparisonModel", ClassificationContract.Model.MINI.id, "promptVersion", ClassificationContract.PROMPT_VERSION,
                "policyVersion", ClassificationContract.POLICY_VERSION, "maxBatchSize", 30);
    }

    @PostMapping("/classifications")
    @Operation(summary = "의약품 AI 분류 및 이력 저장", description = "1~30개 ID를 순차 처리합니다. model 생략 시 NANO. 동일 입력의 성공/검토 결과는 재사용합니다. 실제 API 비용이 발생하며 카테고리는 자동 반영하지 않습니다.")
    public List<MedicineClassificationService.Result> classify(@RequestBody Request request) {
        return service.classify(request.medicineIds(), request.model() == null ? ClassificationContract.Model.NANO : request.model());
    }

    @GetMapping("/preview")
    @Operation(summary = "AI에 전달될 입력 미리보기", description = "API 호출과 비용 없이 입력을 확인합니다. 기존 카테고리는 참고용으로만 표시되며 modelInput에는 포함되지 않습니다.")
    public MedicineClassificationService.Preview preview(@RequestParam long medicineId) {
        return service.preview(medicineId);
    }

    @GetMapping("/classifications")
    @Operation(summary = "분류 이력 조회 (nano/mini 결과 비교)")
    public Page<ClassificationHistory> history(@RequestParam(required = false) Long medicineId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "30") int size) {
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return medicineId == null ? histories.findAll(pageable) : histories.findByMedicineId(medicineId, pageable);
    }
}
