package com.allfolio.domain.service;

import com.allfolio.domain.DevicePlatform;
import com.allfolio.domain.DeviceToken;
import com.allfolio.domain.User;
import com.allfolio.domain.exception.DeviceTokenNotFoundException;
import com.allfolio.domain.repository.DeviceTokenRepository;
import com.allfolio.domain.repository.UserRepository;
import com.allfolio.web.dto.DeviceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 기기 토큰 등록/해제 API 계층 (docs/ROADMAP.md Task 029). 저장 스키마(device_tokens,
 * DeviceToken 엔티티)와 발송 컴포넌트(FcmSender)는 이전 태스크 산출물을 그대로 재사용하고,
 * 이 서비스는 그 사이를 잇는 등록/해제만 담당한다 — 실제 발송 트리거는 후속 태스크.
 */
@Service
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository, UserRepository userRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.userRepository = userRepository;
    }

    /**
     * 같은 토큰이 이미 저장돼 있으면(같은 물리 기기의 재등록) 소유자에 따라 분기한다.
     *
     * <p><b>다른 유저 소유였던 경우(기기 공유·재로그인) 재소유 처리 방식 결정:</b> "기존 행을
     * revoke만 하고 같은 토큰으로 새 행을 insert"는 이 스키마에서 물리적으로 불가능하다 —
     * {@code uk_device_tokens_token}이 {@code revoked_at}과 무관하게 테이블 전체에 걸리는 풀
     * UNIQUE(token) 제약이라(V4__device_tokens.sql, 부분 인덱스 아님), 같은 토큰 문자열을 가진
     * 두 행은 revoked 여부와 무관하게 공존할 수 없다(flush 순서를 아무리 보장해도 두 번째
     * insert 시점에 첫 번째 행이 여전히 존재하면 제약 위반). "기존 행을 새 유저로 재소유
     * (reassign)"도 검토했지만 {@link DeviceToken#getUser()}가 매핑된 {@code user_id} 컬럼이
     * {@code @JoinColumn(updatable = false)}라 값을 바꿔도 UPDATE에 반영되지 않는데, 이 매핑을
     * 바꾸는 것은 엔티티 컬럼 매핑(CLAUDE.md 역할 경계상 database 에이전트 소관)이라 이번 태스크
     * 범위 밖이다. 따라서 기존 행을 삭제하고 flush한 뒤 새 유저 명의로 새 행을 생성하는 쪽을
     * 택했다 — "하나의 물리 기기(FCM 토큰)는 한 시점에 한 계정에만 연결된다"는 불변식을 지키면서
     * 스키마도 건드리지 않는 가장 단순한 방법이다. (과거 소유자 이력을 별도로 남길 필요가 생기면
     * uk_device_tokens_token을 부분 인덱스(WHERE revoked_at IS NULL)로 바꾸는 마이그레이션이
     * 필요하다 — database 에이전트 소관.)
     */
    @Transactional
    public DeviceResponse register(UUID userId, String token, DevicePlatform platform) {
        Optional<DeviceToken> existing = deviceTokenRepository.findByToken(token);
        if (existing.isPresent()) {
            DeviceToken current = existing.get();
            if (current.getUser().getId().equals(userId)) {
                current.reactivate();
                return toResponse(current);
            }
            deviceTokenRepository.delete(current);
            deviceTokenRepository.flush();
        }

        User userRef = userRepository.getReferenceById(userId);
        DeviceToken created = deviceTokenRepository.save(DeviceToken.of(userRef, token, platform));
        return toResponse(created);
    }

    @Transactional
    public void revoke(UUID userId, UUID deviceId) {
        DeviceToken dt = deviceTokenRepository.findByIdAndUser_Id(deviceId, userId)
                .orElseThrow(DeviceTokenNotFoundException::new);
        dt.revoke();
    }

    private DeviceResponse toResponse(DeviceToken dt) {
        return new DeviceResponse(dt.getId(), dt.getPlatform(), dt.getCreatedAt());
    }
}
