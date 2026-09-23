package com.pharmquest.pharmquest.domain.medicine.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Collection;
import java.util.Optional;

public interface ClassificationHistoryRepository extends JpaRepository<ClassificationHistory, Long> {
    Optional<ClassificationHistory> findFirstByMedicineIdAndCacheKeyAndStatusInOrderByIdDesc(
            Long medicineId, String cacheKey, Collection<ClassificationHistory.Status> statuses);
    Page<ClassificationHistory> findByMedicineId(Long medicineId, Pageable pageable);
}
