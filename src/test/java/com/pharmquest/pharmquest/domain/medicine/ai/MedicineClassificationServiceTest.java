package com.pharmquest.pharmquest.domain.medicine.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmquest.pharmquest.domain.medicine.data.Medicine;
import com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory;
import com.pharmquest.pharmquest.domain.medicine.repository.MedRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MedicineClassificationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void savesGroundedResultWithoutChangingMedicineCategory() throws Exception {
        MedRepository medicines = mock(MedRepository.class);
        ClassificationHistoryRepository histories = mock(ClassificationHistoryRepository.class);
        OpenAiClassificationClient client = mock(OpenAiClassificationClient.class);
        Medicine medicine = medicine();
        when(medicines.findById(7L)).thenReturn(Optional.of(medicine));
        when(client.configured()).thenReturn(true);
        when(histories.findFirstByMedicineIdAndCacheKeyAndStatusInOrderByIdDesc(eq(7L), anyString(), anyCollection()))
                .thenReturn(Optional.empty());
        when(client.classify(anyString(), eq(ClassificationContract.Model.NANO))).thenReturn(mapper.readTree("""
                {"id":"response-1","model":"gpt-5.4-nano-2026-03-17",
                 "choices":[{"finish_reason":"stop","message":{"content":"{\\"category\\":\\"ANTISEPTIC\\",\\"evidence\\":[{\\"field\\":\\"purpose\\",\\"quote\\":\\"First aid Antiseptic\\"}],\\"needsReview\\":false,\\"reviewReason\\":null}"}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":10}}}
                """));
        when(histories.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = new MedicineClassificationService(medicines, histories, client, mapper)
                .classify(List.of(7L), ClassificationContract.Model.NANO).get(0);

        assertFalse(result.reused());
        assertEquals(MedicineCategory.ANTISEPTIC, result.classification().getProposedCategory());
        assertEquals(ClassificationHistory.Status.SUCCESS, result.classification().getStatus());
        assertEquals(100, result.classification().getInputTokens());
        assertEquals(10, result.classification().getCachedInputTokens());
        assertEquals(MedicineCategory.OTHER, medicine.getCategory());
        verify(medicines, never()).save(any());
    }

    @Test
    void reusesSuccessfulResultWithoutProviderCall() throws Exception {
        MedRepository medicines = mock(MedRepository.class);
        ClassificationHistoryRepository histories = mock(ClassificationHistoryRepository.class);
        OpenAiClassificationClient client = mock(OpenAiClassificationClient.class);
        Medicine medicine = medicine();
        ClassificationHistory cached = new ClassificationHistory();
        cached.setStatus(ClassificationHistory.Status.SUCCESS);
        when(medicines.findById(7L)).thenReturn(Optional.of(medicine));
        when(client.configured()).thenReturn(true);
        when(histories.findFirstByMedicineIdAndCacheKeyAndStatusInOrderByIdDesc(eq(7L), anyString(), anyCollection()))
                .thenReturn(Optional.of(cached));

        var result = new MedicineClassificationService(medicines, histories, client, mapper)
                .classify(List.of(7L), ClassificationContract.Model.NANO).get(0);
        assertTrue(result.reused());
        assertSame(cached, result.classification());
        verify(client, never()).classify(anyString(), any());
    }

    @Test
    void recordsInvalidModelEvidenceAsInvalidResponse() throws Exception {
        MedRepository medicines = mock(MedRepository.class);
        ClassificationHistoryRepository histories = mock(ClassificationHistoryRepository.class);
        OpenAiClassificationClient client = mock(OpenAiClassificationClient.class);
        when(medicines.findById(7L)).thenReturn(Optional.of(medicine()));
        when(client.configured()).thenReturn(true);
        when(histories.findFirstByMedicineIdAndCacheKeyAndStatusInOrderByIdDesc(anyLong(), anyString(), anyCollection()))
                .thenReturn(Optional.empty());
        when(client.classify(anyString(), any())).thenReturn(mapper.readTree("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"category\\":\\"ANTISEPTIC\\",\\"evidence\\":[{\\"field\\":\\"purpose\\",\\"quote\\":\\"hallucinated\\"}],\\"needsReview\\":false,\\"reviewReason\\":null}"}}]}
                """));
        when(histories.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var history = new MedicineClassificationService(medicines, histories, client, mapper)
                .classify(List.of(7L), ClassificationContract.Model.NANO).get(0).classification();
        assertEquals(ClassificationHistory.Status.INVALID_RESPONSE, history.getStatus());
        assertEquals("RESPONSE_VALIDATION_FAILED", history.getErrorCode());
        assertNull(history.getProposedCategory());
    }

    @Test
    void previewExcludesExistingCategoryFromModelInput() {
        MedRepository medicines = mock(MedRepository.class);
        when(medicines.findById(7L)).thenReturn(Optional.of(medicine()));
        var preview = new MedicineClassificationService(medicines, mock(ClassificationHistoryRepository.class),
                mock(OpenAiClassificationClient.class), mapper).preview(7L);
        assertEquals(MedicineCategory.OTHER, preview.currentCategory());
        assertFalse(preview.modelInput().containsKey("category"));
        assertEquals("Purpose First aid Antiseptic", preview.modelInput().get("purpose"));
    }

    private Medicine medicine() {
        Medicine medicine = new Medicine();
        medicine.setId(7L);
        medicine.setBrandName("Betadine");
        medicine.setPurpose("Purpose First aid Antiseptic");
        medicine.setIndicationsAndUsage("Helps prevent infection in minor cuts");
        medicine.setActiveIngredient("Povidone iodine");
        medicine.setRoute("TOPICAL");
        medicine.setCategory(MedicineCategory.OTHER);
        return medicine;
    }
}
