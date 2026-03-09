package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.PaymentTransaction
import java.time.LocalDate

interface PaymentTransactionRepository {
    fun findById(id: Long): PaymentTransaction?
    fun findAllByIdIn(ids: List<Long>): List<PaymentTransaction>
    fun findSuccessfulTransactions(date: LocalDate): List<PaymentTransaction>
}
