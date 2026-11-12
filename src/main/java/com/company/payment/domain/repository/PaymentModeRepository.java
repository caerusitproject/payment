package com.company.payment.domain.repository;

import com.company.payment.domain.entity.PaymentMode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentModeRepository extends JpaRepository<PaymentMode, Long> {
    List<PaymentMode> findByActiveStatusCdidTrue();
}
