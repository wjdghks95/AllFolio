package com.allfolio.web;

import com.allfolio.domain.AssetType;
import com.allfolio.domain.service.SearchService;
import com.allfolio.web.dto.SearchResultResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * GET /v1/assets/search — 통합 종목 검색(docs/ROADMAP.md Task 026).
 * 클래스 레벨 @Validated 금지(web/CLAUDE.md) — Spring 7 내장 파라미터 검증 경로를 유지해
 * GlobalExceptionHandler가 400으로 잡을 수 있게 한다.
 */
@RestController
@RequestMapping("/v1/assets")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public List<SearchResultResponse> search(
            Authentication authentication,
            @RequestParam AssetType assetType,
            @RequestParam String currency,
            @RequestParam @NotBlank String q) {
        UUID userId = UUID.fromString(authentication.getName());
        return searchService.search(userId, assetType, currency, q)
                .stream()
                .map(SearchResultResponse::from)
                .toList();
    }
}
