package com.pg.worker.settlement.presentation.controller

import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import com.pg.worker.settlement.presentation.dto.SettlementReconciliationResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate

@Tag(name = "Settlement Reconciliation", description = "외부 대사 결과 조회 API")
@RequestMapping("/api/v1/settlements/reconciliation-results")
interface SettlementReconciliationApi {

    @Operation(
        summary = "외부 대사 결과 목록 조회",
        description = "대사 기준일(date) 필수. merchantId, resultType, status 로 추가 필터링 가능."
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = SettlementReconciliationResponse::class)
            )]
        )
    )
    @GetMapping
    fun getReconciliationResults(
        @Parameter(description = "대사 기준일 (yyyy-MM-dd)", required = true, example = "2026-03-09")
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,

        @Parameter(description = "가맹점 ID 필터", example = "1001")
        @RequestParam(required = false) merchantId: Long?,

        @Parameter(description = "대사 결과 타입 필터", example = "AMOUNT_MISMATCH")
        @RequestParam(required = false) resultType: SettlementReconciliationResultType?,

        @Parameter(description = "처리 상태 필터", example = "OPEN")
        @RequestParam(required = false) status: ReconciliationStatus?,
    ): List<SettlementReconciliationResponse>

    @Operation(
        summary = "외부 대사 결과 상세 조회",
        description = "대사 결과 ID로 단건 상세 조회."
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = SettlementReconciliationResponse::class)
            )]
        ),
        ApiResponse(responseCode = "404", description = "해당 ID의 대사 결과를 찾을 수 없음")
    )
    @GetMapping("/{id}")
    fun getReconciliationResultById(
        @Parameter(description = "대사 결과 ID", required = true, example = "1")
        @PathVariable id: Long,
    ): SettlementReconciliationResponse
}
