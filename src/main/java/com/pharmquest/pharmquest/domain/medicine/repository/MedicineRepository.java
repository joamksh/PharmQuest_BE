package com.pharmquest.pharmquest.domain.medicine.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;

@Repository
public class MedicineRepository {

    private final WebClient webClient;

    @Value("${fda.api.api-key}")
    private String apiKey;
    // 100개부터 동기에서 client크기 1mb넘는 문제 발생해서 읨의로 테스트를 위해 용량 늘리기
    public MedicineRepository(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl("https://api.fda.gov")
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)
                )
                .build();
    }

    // FDA API를 호출하여 약물 데이터를 가져옵니다.
    public String fetchMedicineData(String query, int limit) {
        return fetchMedicineData(query, limit, 0);
    }

    public String fetchMedicineData(String query, int limit, int skip) {
        return webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/drug/label.json").queryParam("limit", limit).queryParam("skip", skip);
                    if (query != null && !query.isBlank()) uriBuilder.queryParam("search", query);
                    if (apiKey != null && !apiKey.isBlank()) uriBuilder.queryParam("api_key", apiKey);
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    // DailyMed API를 호출하여 주어진 SPL Set ID에 대한 이미지 데이터를 가져옵니다.
    public String fetchImageData(String splSetId) {
        return webClient.get()
                .uri("https://dailymed.nlm.nih.gov/dailymed/services/v2/spls/" + splSetId + "/media.json")
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
