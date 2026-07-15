package com.routinely.challenge_service.domain.challenge;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 챌린지 대표 이미지 값 객체.
 *
 * <p>외부에서 접근하는 {@code url} 과 저장소 삭제/교체에 사용하는 {@code objectKey} 를 함께 보관한다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChallengeImage {

    @Column(name = "image_url", length = 500)
    private String url;

    @Column(name = "image_object_key", length = 500)
    private String objectKey;

    public static ChallengeImage of(String url, String objectKey) {
        return new ChallengeImage(url, objectKey);
    }
}
