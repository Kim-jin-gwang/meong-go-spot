package com.meonggo.backend.ingestion.controller;

import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.ingestion.dto.IngestionResponse;
import com.meonggo.backend.ingestion.dto.InsightsResponse;
import com.meonggo.backend.ingestion.service.HomeInsightsService;
import com.meonggo.backend.ingestion.service.IngestionStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data-sources/shelter-animals")
public class IngestionStatusController {
    private final IngestionStatusService service;
    private final HomeInsightsService insights;

    public IngestionStatusController(IngestionStatusService service, HomeInsightsService insights) {
        this.service = service;
        this.insights = insights;
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<IngestionResponse.Status>> status() {
        return ResponseEntity.ok(ApiResponse.success("공공데이터 갱신 상태를 조회했습니다.", service.status()));
    }

    @GetMapping("/daily-summary")
    public ResponseEntity<ApiResponse<IngestionResponse.Daily>> dailySummary() {
        return ResponseEntity.ok(ApiResponse.success("일일 입소 요약을 조회했습니다.", service.dailySummary()));
    }

    /** D3. 홈 카드 5장 — D2 요약 + 지역 공고 마감·주간 입소·결과 통계·실종 신고. 인증 없음. */
    @GetMapping("/insights")
    public ResponseEntity<ApiResponse<InsightsResponse.Insights>> insights(
            @RequestParam(required = false) String regionCode) {
        return ResponseEntity.ok(
                ApiResponse.success("홈 인사이트를 조회했습니다.", insights.insights(regionCode)));
    }
}
