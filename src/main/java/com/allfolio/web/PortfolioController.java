package com.allfolio.web;

import com.allfolio.domain.service.PortfolioService;
import com.allfolio.web.dto.PortfolioResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping
    public PortfolioResponse get(Authentication authentication) {
        return portfolioService.listPortfolio(userId(authentication));
    }

    /** JwtFilter가 principal에 userId.toString()을 심어둔다(infra/security/JwtFilter.java). */
    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
