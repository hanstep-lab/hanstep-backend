package com.hanstep.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * users 테이블. 앱인앱이라 비밀번호가 없고, SOL 에서 넘어온 진입 키(external_user_key)로 사용자를 찾는다.
 * 사용자 생성은 아직 API 로 하지 않는다(데모 사용자는 db/init/03_hanstep_demo_seed.sql).
 *
 * <p>ENUM 칸은 columnDefinition 을 DB 와 똑같이 적어 둔다. ddl-auto: validate 가 MySQL ENUM 칸을 비교할 때
 * 타입 이름이 어긋나 실행이 막히는 경우를 피하기 위함.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_user_key", nullable = false, length = 64)
    private String externalUserKey;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_language", nullable = false, columnDefinition = "enum('en','ko','vi','zh')")
    private Language preferredLanguage;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, columnDefinition = "enum('USER','ADMIN')")
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "enum('ACTIVE','SUSPENDED','WITHDRAWN')")
    private UserStatus status;

    // 생성·수정 시각은 DB 기본값이 채운다
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public void changeLanguage(Language language) {
        this.preferredLanguage = language;
    }
}
