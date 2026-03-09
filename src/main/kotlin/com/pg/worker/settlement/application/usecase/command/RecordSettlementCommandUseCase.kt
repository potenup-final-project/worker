package com.pg.worker.settlement.application.usecase.command

import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand

interface RecordSettlementCommandUseCase {
    fun record(command: RecordSettlementCommand)
}
