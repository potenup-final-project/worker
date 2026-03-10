package com.pg.worker.settlement.presentation.controller

import com.pg.worker.settlement.presentation.dto.SettlementProgressResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping

@Tag(name = "Settlement Progress", description = "정산 진행 단계 조회 API")
@RequestMapping("/api/v1/settlements/progress")
interface SettlementProgressApi {

    @Operation(
        summary = "거래별 정산 진행 단계 조회",
        description = "transactionId 기준으로 정산이 어느 단계까지 진행됐는지 조회합니다. " +
                "내부 대사(Internal Reconciliation)에서 OPEN 상태 불일치가 있을 경우 isBlocked=true로 표시됩니다."
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "정산 진행 단계 조회 성공",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = SettlementProgressResponse::class)
            )]
        ),
        ApiResponse(responseCode = "404", description = "해당 transactionId에 대한 결제 거래를 찾을 수 없음"),
    )
    @GetMapping("/transactions/{transactionId}")
    fun getTransactionProgress(
        @Parameter(description = "조회할 거래 ID", required = true, example = "1001")
        @PathVariable transactionId: Long
    ): SettlementProgressResponse
}
