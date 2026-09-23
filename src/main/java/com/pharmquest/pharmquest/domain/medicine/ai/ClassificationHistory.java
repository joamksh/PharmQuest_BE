package com.pharmquest.pharmquest.domain.medicine.ai;

import com.pharmquest.pharmquest.domain.medicine.data.enums.MedicineCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Getter
@Setter
@Table(indexes = @Index(name = "idx_classification_lookup", columnList = "medicineId,cacheKey"))
public class ClassificationHistory {
    public enum Status { SUCCESS, REVIEW_REQUIRED, INPUT_INVALID, INVALID_RESPONSE, API_ERROR }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long medicineId;
    private String cacheKey;
    private String model;
    private String actualModel;
    private String promptVersion;
    private String policyVersion;
    @Enumerated(EnumType.STRING)
    private MedicineCategory previousCategory;
    @Enumerated(EnumType.STRING)
    private MedicineCategory proposedCategory;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Column(columnDefinition = "LONGTEXT")
    private String inputJson;
    @Column(columnDefinition = "LONGTEXT")
    private String responseJson;
    @Column(columnDefinition = "TEXT")
    private String evidenceJson;
    @Column(columnDefinition = "TEXT")
    private String reviewReason;
    private String errorCode;
    private String providerResponseId;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cachedInputTokens;
    private long durationMs;
    private Instant createdAt;
}
