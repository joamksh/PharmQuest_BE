package com.pharmquest.pharmquest.domain.medicine.evaluation;

import com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed-label metrics shared by rule and future AI evaluation runners. */
final class CategoryMetrics {
    static final List<MedicineCategory> LABELS = Arrays.stream(MedicineCategory.values())
            .filter(category -> category != MedicineCategory.ALL).toList();

    record Prediction(String id, MedicineCategory expected, MedicineCategory predicted) {}
    record PerClass(int support, double precision, double recall, double f1) {}
    record Report(int count, double accuracy, double macroF1,
                  Map<MedicineCategory, PerClass> perClass,
                  Map<MedicineCategory, Map<MedicineCategory, Integer>> confusionMatrix,
                  List<Prediction> errors) {}

    static Report calculate(List<Prediction> predictions) {
        if (predictions.isEmpty()) throw new IllegalArgumentException("Empty evaluation dataset");
        Map<MedicineCategory, Map<MedicineCategory, Integer>> matrix = new LinkedHashMap<>();
        for (MedicineCategory expected : LABELS) {
            Map<MedicineCategory, Integer> row = new LinkedHashMap<>();
            LABELS.forEach(predicted -> row.put(predicted, 0));
            matrix.put(expected, row);
        }
        for (Prediction prediction : predictions) {
            if (!LABELS.contains(prediction.expected()) || !LABELS.contains(prediction.predicted())) {
                throw new IllegalArgumentException("Invalid category for " + prediction.id());
            }
            matrix.get(prediction.expected()).merge(prediction.predicted(), 1, Integer::sum);
        }
        Map<MedicineCategory, PerClass> scores = new LinkedHashMap<>();
        int correct = 0;
        double f1Sum = 0;
        for (MedicineCategory category : LABELS) {
            int tp = matrix.get(category).get(category);
            int support = matrix.get(category).values().stream().mapToInt(Integer::intValue).sum();
            int predictedCount = matrix.values().stream().mapToInt(row -> row.get(category)).sum();
            double precision = ratio(tp, predictedCount);
            double recall = ratio(tp, support);
            double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
            scores.put(category, new PerClass(support, precision, recall, f1));
            correct += tp;
            f1Sum += f1;
        }
        return new Report(predictions.size(), ratio(correct, predictions.size()), f1Sum / LABELS.size(),
                scores, matrix, predictions.stream().filter(p -> p.expected() != p.predicted()).toList());
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
