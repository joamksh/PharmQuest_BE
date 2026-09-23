package com.pharmquest.pharmquest.domain.medicine.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory.*;
import static org.junit.jupiter.api.Assertions.*;

class CategoryMetricsTest {
    @Test
    void calculatesKnownConfusionMatrixIncludingAbsentClasses() {
        var report = CategoryMetrics.calculate(List.of(
                new CategoryMetrics.Prediction("a", PAIN_RELIEF, PAIN_RELIEF),
                new CategoryMetrics.Prediction("b", PAIN_RELIEF, OTHER),
                new CategoryMetrics.Prediction("c", OTHER, OTHER)));
        assertEquals(2.0 / 3, report.accuracy(), 1e-9);
        assertEquals(1.0 / 6, report.macroF1(), 1e-9);
        var pain = report.perClass().get(PAIN_RELIEF);
        assertEquals(2, pain.support());
        assertEquals(1, pain.precision(), 1e-9);
        assertEquals(0.5, pain.recall(), 1e-9);
        assertEquals(2.0 / 3, pain.f1(), 1e-9);
        assertEquals(0, report.perClass().get(COLD).f1());
        assertEquals(1, report.confusionMatrix().get(PAIN_RELIEF).get(OTHER));
        assertEquals(List.of("b"), report.errors().stream().map(CategoryMetrics.Prediction::id).toList());
    }

    @Test
    void perfectPredictionsAcrossAllLabelsScoreOne() {
        var report = CategoryMetrics.calculate(CategoryMetrics.LABELS.stream()
                .map(label -> new CategoryMetrics.Prediction(label.name(), label, label)).toList());
        assertEquals(1, report.accuracy());
        assertEquals(1, report.macroF1());
        assertTrue(report.errors().isEmpty());
    }

    @Test
    void rejectsEmptyOrNonClassificationLabels() {
        assertThrows(IllegalArgumentException.class, () -> CategoryMetrics.calculate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> CategoryMetrics.calculate(List.of(
                new CategoryMetrics.Prediction("bad", ALL, OTHER))));
        assertThrows(IllegalArgumentException.class, () -> CategoryMetrics.calculate(List.of(
                new CategoryMetrics.Prediction("bad", OTHER, null))));
    }
}
