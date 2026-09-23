package com.pharmquest.pharmquest.domain.medicine.service;

import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.translate.Translation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class TranslationService {

    private final Translate translate;

    public TranslationService(@Value("${google.cloud.translate.api-key}") String apiKey,
                              @Value("${google.cloud.translate.enabled:true}") boolean enabled) {
        if (!enabled) {
            this.translate = null;
            return;
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Google Cloud Translation API 키가 비어 있습니다!");
        }

        // API 키로 TranslateOptions 초기화
        this.translate = TranslateOptions.newBuilder()
                .setApiKey(apiKey)
                .build()
                .getService();
    }

    public String translateText(String text, String targetLanguage) {
        if (translate == null) {
            throw new IllegalStateException("Translation is disabled. Configure google.cloud.translate.api-key and enable translation.");
        }
        Translation translation = this.translate.translate(
                text,
                Translate.TranslateOption.targetLanguage(targetLanguage)
        );
        return translation.getTranslatedText();
    }
}
