package com.routinely.user_service.domain;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.jpa.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(unique = true, nullable = false, length = 20)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private UserRole role;

    @Embedded
    private ProfileImage profileImage;

    @Column(length = 100)
    private String bio;

    @Column(name = "nickname_updated_at")
    private LocalDateTime nicknameUpdatedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    public static User createUser(String email, String passwordHash, String nickname) {
        return User.builder()
                .email(email)
                .passwordHash(passwordHash)
                .nickname(nickname)
                .role(UserRole.USER)
                .build();
    }

    public void deactivate() {
        this.isActive = false;
    }

    public void updateNickname(String nickname, LocalDateTime nicknameUpdatedAt) {
        validateNickname(nickname);
        this.nickname = nickname;
        this.nicknameUpdatedAt = nicknameUpdatedAt;
    }

    public void updateBio(String bio) {
        this.bio = (bio != null && bio.isBlank()) ? null : bio;
    }

    public void changeProfileImage(ProfileImage profileImage) {
        this.profileImage = profileImage;
    }

    public void removeProfileImage() {
        this.profileImage = null;
    }

    public String getProfileImageUrl() {
        return profileImage != null ? profileImage.getUrl() : null;
    }

    public String getProfileImageObjectKey() {
        return profileImage != null ? profileImage.getObjectKey() : null;
    }

    private void validateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "닉네임은 비어 있을 수 없습니다.");
        }

        if (nickname.length() < 2 || nickname.length() > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "닉네임은 2자 이상 20자 이하여야 합니다.");
        }
    }
}
