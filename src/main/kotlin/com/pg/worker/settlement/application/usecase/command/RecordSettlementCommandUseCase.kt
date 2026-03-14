package com.pg.worker.settlement.application.usecase.command

import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementResult

interface RecordSettlementCommandUseCase {
    fun record(command: RecordSettlementCommand): RecordSettlementResult
}
