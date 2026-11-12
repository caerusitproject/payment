package com.company.payment.domain.repository;

import com.company.payment.domain.entity.Payer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayerRepository extends JpaRepository<Payer, Long> {
    Optional<Payer> findFirstByCurrencyAndActiveStatusCdidTrueOrderByPayerIdAsc(String currency);
}
