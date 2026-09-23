package com.pharmquest.pharmquest.domain.medicine.web.controller;

import com.pharmquest.pharmquest.domain.medicine.service.LocalMedicineImportService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@Profile("local")
@RequestMapping("/medicine/local")
@RequiredArgsConstructor
public class LocalMedicineImportController {
    private final LocalMedicineImportService service;

    @Operation(summary = "로컬 DB에 FDA 일반의약품 원문 저장 (번역·이미지 조회 없음)",
            description = "limit은 조회할 원문 수(1~100)입니다. 중복/필수값 누락으로 실제 저장 수는 적을 수 있습니다. 카테고리는 기존 규칙의 임시값입니다.")
    @PostMapping("/import/fda")
    public LocalMedicineImportService.ImportResult importFda(
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(defaultValue = "0") int skip) throws IOException {
        return service.importLabels(limit, skip);
    }
}
