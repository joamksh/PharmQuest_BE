package com.pharmquest.pharmquest.domain.medicine.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmquest.pharmquest.domain.medicine.data.Medicine;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClassificationContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> input = Map.of(
            "purpose", "Purpose First aid Antiseptic",
            "indicationsAndUsage", "Helps prevent infection in minor cuts",
            "activeIngredient", "Povidone iodine",
            "route", "TOPICAL");

    @Test
    void acceptsGroundedClassificationAndReviewResult() throws Exception {
        assertDoesNotThrow(() -> ClassificationContract.validate(mapper.readTree("""
                {"category":"ANTISEPTIC","evidence":[{"field":"purpose","quote":"First aid Antiseptic"}],
                 "needsReview":false,"reviewReason":null}
                """), input));
        assertDoesNotThrow(() -> ClassificationContract.validate(mapper.readTree("""
                {"category":null,"evidence":[],"needsReview":true,"reviewReason":"Multiple principal uses"}
                """), input));
    }

    @Test
    void rejectsUngroundedOrInconsistentResponses() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ClassificationContract.validate(mapper.readTree("""
                {"category":"ANTISEPTIC","evidence":[{"field":"purpose","quote":"invented"}],
                 "needsReview":false,"reviewReason":null}
                """), input));
        assertThrows(IllegalArgumentException.class, () -> ClassificationContract.validate(mapper.readTree("""
                {"category":"OTHER","evidence":[],"needsReview":true,"reviewReason":"unclear"}
                """), input));
        assertThrows(IllegalArgumentException.class, () -> ClassificationContract.validate(mapper.readTree("""
                {"category":"ALL","evidence":[{"field":"purpose","quote":"Purpose"}],
                 "needsReview":false,"reviewReason":null}
                """), input));
    }

    @Test
    void buildsInputWithoutExistingCategory() {
        Medicine medicine = new Medicine();
        medicine.setPurpose("pain relief");
        medicine.setIndicationsAndUsage("headache");
        medicine.setActiveIngredient("ingredient");
        medicine.setRoute("ORAL");
        var result = ClassificationContract.input(medicine);
        assertEquals(4, result.size());
        assertFalse(result.containsKey("category"));
    }
}
