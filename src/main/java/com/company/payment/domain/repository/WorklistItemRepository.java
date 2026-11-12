package com.company.payment.domain.repository;

import com.company.payment.domain.entity.WorklistItem;
import com.company.payment.domain.enums.WorklistItemStatus;
import com.company.payment.domain.enums.WorklistItemType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorklistItemRepository extends JpaRepository<WorklistItem, Long> {
    Optional<WorklistItem> findFirstByItemTypeAndStatusOrderByIdAsc(WorklistItemType type, WorklistItemStatus status);
    Optional<WorklistItem> findFirstByPymntTxnIdAndItemTypeOrderByIdDesc(Long pymntTxnId, WorklistItemType type);
    boolean existsByPymntReqIdAndItemTypeAndStatus(Long pymntReqId, WorklistItemType type, WorklistItemStatus status);
    List<WorklistItem> findByItemTypeAndStatusOrderByIdAsc(WorklistItemType type, WorklistItemStatus status);
}
