package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.PaymentTransaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PaymentTransactionJpaRepository : JpaRepository<PaymentTransaction, Long>
