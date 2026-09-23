package com.pharmquest.pharmquest.domain.medicine.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmquest.pharmquest.domain.medicine.data.Medicine;
import com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory;
import com.pharmquest.pharmquest.domain.medicine.repository.MedRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@Profile("local")
@RequiredArgsConstructor
public class MedicineClassificationService {
    private final MedRepository medicines;
    private final ClassificationHistoryRepository histories;
    private final OpenAiClassificationClient client;
    private final ObjectMapper mapper;
    public record Result(Long medicineId, String brandName, MedicineCategory currentCategory,
                         boolean reused, ClassificationHistory classification) {}
    public record Preview(Long medicineId, String brandName, MedicineCategory currentCategory,
                          Map<String, String> modelInput, String promptVersion, String policyVersion) {}

    public Preview preview(long medicineId) {
        if (medicineId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "medicineId must be positive");
        Medicine medicine = medicines.findById(medicineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Medicine not found: " + medicineId));
        return new Preview(medicine.getId(), medicine.getBrandName(), medicine.getCategory(),
                ClassificationContract.input(medicine), ClassificationContract.PROMPT_VERSION,
                ClassificationContract.POLICY_VERSION);
    }

    // Single local instance only. Each history save commits independently, with no transaction during HTTP.
    public synchronized List<Result> classify(List<Long> ids, ClassificationContract.Model model) {
        if (ids == null || ids.isEmpty() || ids.size() > 30 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size() || model == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 1..30 unique positive medicineIds and NANO or MINI");
        }
        List<Medicine> selected = ids.stream().map(id -> medicines.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Medicine not found: " + id))).toList();
        if (!client.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Set openai.api-key in config/application-local-secrets.properties and restart the server");
        List<Result> results = new ArrayList<>();
        for (Medicine medicine : selected) results.add(classifyOne(medicine, model));
        return results;
    }

    private Result classifyOne(Medicine medicine, ClassificationContract.Model model) {
        Map<String, String> input = ClassificationContract.input(medicine);
        String inputJson = json(input);
        String key = hash(inputJson + "\n" + model.id + "\n" + ClassificationContract.PROMPT_VERSION
                + "\n" + ClassificationContract.POLICY_VERSION + "\n" + ClassificationContract.PROMPT
                + "\n" + ClassificationContract.SCHEMA + "\nreasoning=none;maxOutput=1000");
        var cached = histories.findFirstByMedicineIdAndCacheKeyAndStatusInOrderByIdDesc(medicine.getId(), key,
                List.of(ClassificationHistory.Status.SUCCESS, ClassificationHistory.Status.REVIEW_REQUIRED));
        if (cached.isPresent()) return new Result(medicine.getId(), medicine.getBrandName(), medicine.getCategory(), true, cached.get());
        ClassificationHistory history = new ClassificationHistory();
        history.setMedicineId(medicine.getId());
        history.setPreviousCategory(medicine.getCategory());
        history.setCacheKey(key);
        history.setInputJson(inputJson);
        history.setModel(model.id);
        history.setPromptVersion(ClassificationContract.PROMPT_VERSION);
        history.setPolicyVersion(ClassificationContract.POLICY_VERSION);
        history.setCreatedAt(Instant.now());
        long start = System.nanoTime();
        if ((input.get("purpose").isBlank() && input.get("indicationsAndUsage").isBlank()) || inputJson.length() > 30000) {
            history.setStatus(ClassificationHistory.Status.INPUT_INVALID);
            history.setErrorCode("MISSING_USES_OR_INPUT_TOO_LARGE");
        } else {
            JsonNode response = null;
            try {
                response = client.classify(inputJson, model);
                if (response == null) throw new IllegalArgumentException("EMPTY_RESPONSE");
                history.setResponseJson(json(response));
                history.setProviderResponseId(response.path("id").asText(null));
                history.setActualModel(response.path("model").asText(null));
                history.setInputTokens(integer(response.at("/usage/prompt_tokens")));
                history.setOutputTokens(integer(response.at("/usage/completion_tokens")));
                history.setCachedInputTokens(integer(response.at("/usage/prompt_tokens_details/cached_tokens")));
                JsonNode choice = response.at("/choices/0");
                if (!choice.path("finish_reason").asText().equals("stop")
                        || !choice.at("/message/refusal").asText("").isBlank()) {
                    throw new IllegalArgumentException("REFUSED_OR_INCOMPLETE");
                }
                JsonNode result = mapper.readTree(choice.at("/message/content").asText());
                ClassificationContract.validate(result, input);
                boolean review = result.get("needsReview").booleanValue();
                history.setStatus(review ? ClassificationHistory.Status.REVIEW_REQUIRED : ClassificationHistory.Status.SUCCESS);
                history.setProposedCategory(review ? null : MedicineCategory.valueOf(result.get("category").asText()));
                history.setReviewReason(result.get("reviewReason").asText(null));
                history.setEvidenceJson(json(result.get("evidence")));
            } catch (Exception error) {
                history.setStatus(response == null ? ClassificationHistory.Status.API_ERROR : ClassificationHistory.Status.INVALID_RESPONSE);
                // Do not persist provider exception messages or headers; they may contain credentials/request bodies.
                history.setErrorCode(error instanceof WebClientResponseException e ? "HTTP_" + e.getStatusCode().value()
                        : response == null ? "PROVIDER_REQUEST_FAILED" : "RESPONSE_VALIDATION_FAILED");
            }
        }
        history.setDurationMs((System.nanoTime() - start) / 1_000_000);
        histories.saveAndFlush(history);
        return new Result(medicine.getId(), medicine.getBrandName(), medicine.getCategory(), false, history);
    }

    private static Integer integer(JsonNode node) { return node.isIntegralNumber() ? node.intValue() : null; }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("JSON_SERIALIZATION_FAILED", error); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
