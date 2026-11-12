package com.company.payment.domain.repository;

import com.company.payment.domain.entity.BenefSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefSnapshotRepository extends JpaRepository<BenefSnapshot, Long> {}
