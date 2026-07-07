package com.routinely.storage.s3;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 파일 저장소 설정.
 *
 * <p>prefix: {@code routinely.storage.s3}
 *
 * <p>{@code endpoint} 를 비워두면 실 AWS S3 로 접속하고, 값을 채우면(예: LocalStack
 * {@code http://localhost:4566}) 해당 endpoint 로 override 한다. LocalStack 은
 * virtual-hosted-style 을 지원하지 않으므로 {@code path-style-access=true} 로 둔다.
 * 외부 접근 URL 은 {@code public-base-url} 로 별도 지정할 수 있다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "routinely.storage.s3")
public class S3Properties {

    /** 저장소 사용 여부. false 면 관련 Bean 을 생성하지 않는다. */
    private boolean enabled = true;

    /** 버킷 이름. */
    private String bucket;

    /** 리전 (예: ap-northeast-2). */
    private String region = "ap-northeast-2";

    /** endpoint override URL. 비우면 실 AWS, 채우면 LocalStack 등 커스텀 endpoint. */
    private String endpoint;

    /** 클라이언트에 반환할 공개 URL prefix. 비우면 S3 기본 URL 을 사용한다. */
    private String publicBaseUrl;

    /** path-style 접근 여부. LocalStack 은 true 필요. */
    private boolean pathStyleAccess = false;

    /** 정적 자격증명 access key. 비우면 기본 자격증명 provider chain 사용. */
    private String accessKey;

    /** 정적 자격증명 secret key. */
    private String secretKey;
}
