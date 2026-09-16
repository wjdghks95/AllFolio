package com.allfolio.domain.repository;

import com.allfolio.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    /** 기기 토큰 등록 시 이미 저장된 토큰인지 확인(uk_device_tokens_token). */
    Optional<DeviceToken> findByToken(String token);

    /** 타 유저 소유 토큰 접근은 404로 응답하기 위한 소유권 포함 단건 조회(domain/CLAUDE.md 컨벤션). */
    Optional<DeviceToken> findByIdAndUser_Id(UUID id, UUID userId);

    /** 발송 대상 조회 — idx_device_tokens_active 부분 인덱스를 그대로 탄다. */
    List<DeviceToken> findByUser_IdAndRevokedAtIsNull(UUID userId);
}
