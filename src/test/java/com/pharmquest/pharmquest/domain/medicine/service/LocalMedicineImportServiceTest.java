package com.pharmquest.pharmquest.domain.medicine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmquest.pharmquest.domain.medicine.data.Medicine;
import com.pharmquest.pharmquest.domain.medicine.repository.MedRepository;
import com.pharmquest.pharmquest.domain.medicine.repository.MedicineRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalMedicineImportServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String LABEL = """
            {"set_id":"example-id","openfda":{"brand_name":["Example"],"route":["ORAL"]},
             "purpose":["Pain reliever"],"indications_and_usage":["First indication","Second indication"]}
            """;

    @Test
    void preservesOriginalArrayValuesAndIdentifierWithoutImageOrTranslation() throws Exception {
        Medicine medicine = LocalMedicineImportService.fromLabel(mapper.readTree(LABEL));
        assertNotNull(medicine);
        assertEquals("example-id", medicine.getSplSetId());
        assertEquals("First indication\nSecond indication", medicine.getIndicationsAndUsage());
        assertEquals("Pain reliever", medicine.getPurpose());
        assertEquals("USA", medicine.getCountry());
        assertNull(medicine.getImgUrl());
        assertEquals("", medicine.getGenericName());
    }

    @Test
    void rejectsMissingIndicationsAndOversizedTextWithoutTruncation() throws Exception {
        assertNull(LocalMedicineImportService.fromLabel(mapper.readTree("{}")));
        var label = mapper.readTree(LABEL);
        ((com.fasterxml.jackson.databind.node.ObjectNode) label).put("indications_and_usage", "가".repeat(22000));
        assertNull(LocalMedicineImportService.fromLabel(label));
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsDuplicatesAndWritesOnlyNewLabels() throws Exception {
        var source = mock(MedicineRepository.class);
        var repository = mock(MedRepository.class);
        var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        String existing = LABEL.replace("example-id", "already-saved");
        when(source.fetchMedicineData(anyString(), eq(4), eq(0)))
                .thenReturn("{\"results\":[" + LABEL + "," + LABEL + "," + existing + ",{}]}");
        Medicine alreadySaved = new Medicine();
        alreadySaved.setId(99L);
        alreadySaved.setSplSetId("already-saved");
        when(repository.findBySplSetId("already-saved")).thenReturn(java.util.Optional.of(alreadySaved));
        when(repository.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            List<Medicine> values = invocation.getArgument(0);
            assertEquals(1, values.size());
            values.get(0).setId(123L);
            return values;
        });
        var result = new LocalMedicineImportService(source, repository, mapper, transaction).importLabels(4, 0);
        try {
            assertEquals(4, result.fetched());
            assertEquals(1, result.saved());
            assertEquals(1, result.updated());
            assertEquals(2, result.duplicates());
            assertEquals(1, result.invalid());
            assertEquals(List.of(123L), result.savedIds());
            assertEquals("ORAL", alreadySaved.getRoute());
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(result.snapshot()));
        }
    }

    @Test
    void rejectsInvalidLimitsBeforeCallingSource() {
        var source = mock(MedicineRepository.class);
        var service = new LocalMedicineImportService(source, mock(MedRepository.class), mapper,
                mock(TransactionTemplate.class));
        assertThrows(ResponseStatusException.class, () -> service.importLabels(0, 0));
        assertThrows(ResponseStatusException.class, () -> service.importLabels(101, 0));
        assertThrows(ResponseStatusException.class, () -> service.importLabels(10, -1));
        verifyNoInteractions(source);
    }
}
