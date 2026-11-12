package com.company.payment.domain.repository;

import com.company.payment.domain.entity.BankApiInteraction;
import com.company.payment.domain.enums.ApiOperation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankApiInteractionRepository extends JpaRepository<BankApiInteraction, Long> {
    long countByUniqueIdAndApiOperation(String uniqueId, ApiOperation apiOperation);
}
