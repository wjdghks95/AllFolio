package com.allfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * device_tokens 테이블 매핑 (V4__device_tokens.sql, Task 029).
 * refresh_tokens와 달리 토큰을 해시하지 않고 평문으로 보관한다 — FCM 발송 시 원문 토큰이 필요하고,
 * 탈취되어도 "그 기기로 알림을 보낼 수 있다" 이상의 권한이 없다.
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "token", nullable = false, updatable = false, length = 4096)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, updatable = false, length = 10)
    private DevicePlatform platform;

    /** 기기 해지(로그아웃·토큰 만료) 시 세팅된다. null이면 아직 활성 토큰. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DeviceToken() {
        // JPA
    }

    private DeviceToken(User user, String token, DevicePlatform platform, Instant createdAt) {
        this.user = user;
        this.token = token;
        this.platform = platform;
        this.createdAt = createdAt;
    }

    public static DeviceToken of(User user, String token, DevicePlatform platform) {
        return new DeviceToken(user, token, platform, Instant.now());
    }

    /** 기기 해지 시 호출. 이미 revoke된 토큰이면 최초 시각을 유지한다. */
    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }

    /** 같은 유저가 동일 토큰을 재등록(재로그인·앱 재설치)할 때 호출해 해지 이력을 되돌린다(Task 029). */
    public void reactivate() {
        this.revokedAt = null;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getToken() {
        return token;
    }

    public DevicePlatform getPlatform() {
        return platform;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
