package com.routinely.storage.s3;

import com.routinely.storage.FileStorage;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.util.Locale;

/**
 * S3 파일 저장소 자동 구성.
 *
 * <p>{@code routinely.storage.s3.enabled} 가 true(기본값)일 때 {@link S3Client} 와
 * {@link FileStorage}({@link S3FileStorage}) Bean 을 등록한다. 이 모듈을 의존하는
 * 서비스는 별도 설정 없이 {@link FileStorage} 를 주입받아 사용할 수 있다.
 */
@AutoConfiguration
@EnableConfigurationProperties(S3Properties.class)
@ConditionalOnProperty(prefix = "routinely.storage.s3", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public S3Client s3Client(S3Properties properties) {
        validateProperties(properties);
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .forcePathStyle(properties.isPathStyleAccess());

        if (hasText(properties.getAccessKey()) && hasText(properties.getSecretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())));
        }
        if (hasText(properties.getEndpoint())) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public FileStorage fileStorage(S3Client s3Client, S3Properties properties) {
        return new S3FileStorage(s3Client, properties);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void validateProperties(S3Properties properties) {
        requireText(properties.getBucket(), "routinely.storage.s3.bucket must not be blank when S3 storage is enabled");
        requireText(properties.getRegion(), "routinely.storage.s3.region must not be blank when S3 storage is enabled");
        validateUri(properties.getEndpoint(), "routinely.storage.s3.endpoint");
        validateUri(properties.getPublicBaseUrl(), "routinely.storage.s3.public-base-url");
    }

    private static void requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalStateException(message);
        }
    }

    private static void validateUri(String value, String propertyName) {
        if (!hasText(value)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " must be a valid http(s) URL", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.toLowerCase(Locale.ROOT).equals("http")
                && !scheme.toLowerCase(Locale.ROOT).equals("https"))) {
            throw new IllegalStateException(propertyName + " must be an http(s) URL");
        }
    }
}
