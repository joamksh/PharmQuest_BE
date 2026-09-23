package com.pharmquest.pharmquest.domain.medicine.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmquest.pharmquest.domain.medicine.data.MedicineCategoryMapper;
import com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CategoryEvaluationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    record Input(String purpose, String activeIngredient, String pharmClassEpc, String route,
                 String indicationsAndUsage) {
        String field(String name) {
            return switch (name) {
                case "purpose" -> purpose;
                case "activeIngredient" -> activeIngredient;
                case "pharmClassEpc" -> pharmClassEpc;
                case "route" -> route;
                case "indicationsAndUsage" -> indicationsAndUsage;
                default -> throw new IllegalArgumentException("Unknown evidence field: " + name);
            };
        }
    }
    record Example(String id, String sourceId, String sourceUrl, String language, String split,
                   String reviewer, Input input, MedicineCategory expectedCategory,
                   String evidenceField, String evidence, String note) {}
    record Dataset(String datasetId, String kind, String policyVersion, List<Example> cases) {}

    @Test
    void evaluateExistingRuleOnSelectedDataset() throws Exception {
        String externalPath = System.getenv("CATEGORY_EVAL_DATASET");
        byte[] datasetBytes;
        if (externalPath == null || externalPath.isBlank()) {
            try (var stream = getClass().getResourceAsStream("/medicine-category/synthetic-v1.json")) {
                assertNotNull(stream, "Bundled synthetic dataset is missing");
                datasetBytes = stream.readAllBytes();
            }
        } else {
            datasetBytes = Files.readAllBytes(Path.of(externalPath));
        }
        Dataset dataset = mapper.readValue(datasetBytes, Dataset.class);
        validate(dataset);
        var predictions = dataset.cases().stream().map(example -> {
            Input input = example.input();
            return new CategoryMetrics.Prediction(example.id(), example.expectedCategory(),
                    MedicineCategoryMapper.getCategory(input.purpose(), input.activeIngredient(),
                            input.pharmClassEpc(), input.route()));
        }).toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetId", dataset.datasetId());
        report.put("datasetSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datasetBytes)));
        report.put("kind", dataset.kind());
        report.put("policyVersion", dataset.policyVersion());
        report.put("split", dataset.cases().get(0).split());
        report.put("evaluatedAt", Instant.now().toString());
        report.put("classifier", "MedicineCategoryMapper.getCategory (FDA helper only)");
        report.put("warning", "SYNTHETIC scores are not real-world accuracy. Rows=expected, columns=predicted. "
                + "Macro-F1 includes all 8 labels; zero denominators produce zero.");
        report.put("metrics", CategoryMetrics.calculate(predictions));
        Path output = Path.of("build/reports/category-evaluation/baseline.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Category evaluation report: " + output.toAbsolutePath());
    }

    @Test
    void rejectsEvidenceNotPresentInInput() {
        Example invalid = example("a", "DEV", "missing text", "");
        assertThrows(AssertionError.class, () -> validate(
                new Dataset("invalid", "SYNTHETIC", "v1-draft", List.of(invalid))));
    }

    @Test
    void rejectsMixedSplitsAndDuplicateSources() {
        assertThrows(AssertionError.class, () -> validate(new Dataset("mixed", "SYNTHETIC", "v1-draft",
                List.of(example("a", "DEV", "Pain reliever", ""), example("b", "TEST", "Pain reliever", "")))));
        assertThrows(AssertionError.class, () -> validate(new Dataset("duplicates", "SYNTHETIC", "v1-draft",
                List.of(example("a", "DEV", "Pain reliever", ""), example("a", "DEV", "Pain reliever", "")))));
    }

    @Test
    void reviewedDatasetRequiresReviewer() {
        assertThrows(AssertionError.class, () -> validate(new Dataset("reviewed", "HUMAN_REVIEWED", "v1-draft",
                List.of(example("a", "DEV", "Pain reliever", "")))));
        assertDoesNotThrow(() -> validate(new Dataset("reviewed", "HUMAN_REVIEWED", "v1-draft",
                List.of(example("a", "DEV", "Pain reliever", "test-reviewer")))));
    }

    private static Example example(String id, String split, String evidence, String reviewer) {
        return new Example(id, "source:" + id, "https://example.com/fixture", "en", split, reviewer,
                new Input("Pain reliever", "", "", "ORAL", ""), MedicineCategory.PAIN_RELIEF,
                "purpose", evidence, "Validation fixture only");
    }

    static void validate(Dataset dataset) {
        assertNotNull(dataset);
        required(dataset.datasetId(), "datasetId");
        required(dataset.policyVersion(), "policyVersion");
        assertTrue("SYNTHETIC".equals(dataset.kind()) || "HUMAN_REVIEWED".equals(dataset.kind()), "Invalid kind");
        assertNotNull(dataset.cases());
        assertFalse(dataset.cases().isEmpty(), "Empty cases");
        Set<String> ids = new HashSet<>();
        Set<String> sources = new HashSet<>();
        Set<String> splits = new HashSet<>();
        for (Example example : dataset.cases()) {
            assertNotNull(example);
            required(example.id(), "id");
            assertTrue(ids.add(example.id()), "Duplicate id: " + example.id());
            required(example.sourceId(), "sourceId");
            assertTrue(sources.add(example.sourceId()), "Duplicate sourceId: " + example.sourceId());
            required(example.language(), "language");
            assertTrue("DEV".equals(example.split()) || "TEST".equals(example.split()), "Invalid split");
            splits.add(example.split());
            assertTrue(CategoryMetrics.LABELS.contains(example.expectedCategory()), "Invalid expected category");
            assertNotNull(example.input(), "Missing input: " + example.id());
            Input input = example.input();
            assertNotNull(input.purpose());
            assertNotNull(input.activeIngredient());
            assertNotNull(input.pharmClassEpc());
            assertNotNull(input.route());
            assertNotNull(input.indicationsAndUsage());
            required(example.evidenceField(), "evidenceField");
            required(example.evidence(), "evidence");
            assertTrue(input.field(example.evidenceField()).contains(example.evidence()),
                    "Evidence not found in source: " + example.id());
            if ("HUMAN_REVIEWED".equals(dataset.kind())) {
                required(example.sourceUrl(), "sourceUrl");
                required(example.reviewer(), "reviewer");
            }
        }
        assertEquals(1, splits.size(), "Do not mix DEV and TEST in one evaluation");
    }

    private static void required(String value, String name) {
        assertNotNull(value, name);
        assertFalse(value.isBlank(), "Blank " + name);
    }
}
