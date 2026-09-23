package com.pharmquest.pharmquest.domain.medicine.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Profile("local")
public class OpenAiClassificationClient {
    private final String apiKey;
    private final WebClient client;
    private final ObjectMapper mapper;
    public OpenAiClassificationClient(@Value("${openai.api-key:}") String apiKey, WebClient.Builder builder, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.client = builder.clone().baseUrl("https://api.openai.com/v1")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024)).build();
    }
    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }

    public JsonNode classify(String inputJson, ClassificationContract.Model model) throws Exception {
        if (!configured()) throw new IllegalStateException("OPENAI_API_KEY_MISSING");
        var body = Map.of("model", model.id, "store", false, "reasoning_effort", "none", "max_completion_tokens", 1000,
                "messages", List.of(Map.of("role", "system", "content", ClassificationContract.PROMPT),
                        Map.of("role", "user", "content", inputJson)),
                "response_format", Map.of("type", "json_schema", "json_schema",
                        Map.of("name", "medicine_category", "strict", true, "schema", mapper.readTree(ClassificationContract.SCHEMA))));
        return client.post().uri("/chat/completions").headers(headers -> headers.setBearerAuth(apiKey))
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(1, Duration.ofSeconds(1)).filter(error ->
                        error instanceof WebClientResponseException e && (e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .block(Duration.ofSeconds(65));
    }
}
