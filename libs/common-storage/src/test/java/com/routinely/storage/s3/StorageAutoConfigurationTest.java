package com.routinely.storage.s3;

import com.routinely.storage.FileStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageAutoConfiguration")
class StorageAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class));

    @Test
    @DisplayName("enabled=false 이면 S3 관련 Bean 을 등록하지 않는다")
    void disabled_doesNotRegisterBeans() {
        contextRunner
                .withPropertyValues("routinely.storage.s3.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(S3Client.class)
                        .doesNotHaveBean(FileStorage.class));
    }

    @Test
    @DisplayName("enabled=true 이고 필수 설정이 있으면 S3 관련 Bean 을 등록한다")
    void enabled_registersBeans() {
        contextRunner
                .withPropertyValues(
                        "routinely.storage.s3.bucket=routinely-dev",
                        "routinely.storage.s3.region=ap-northeast-2",
                        "routinely.storage.s3.endpoint=http://localhost:4566",
                        "routinely.storage.s3.public-base-url=https://cdn.routinely.example/images",
                        "routinely.storage.s3.path-style-access=true",
                        "routinely.storage.s3.access-key=test",
                        "routinely.storage.s3.secret-key=test"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(S3Client.class)
                        .hasSingleBean(FileStorage.class));
    }

    @Test
    @DisplayName("enabled=true 이고 bucket 이 없으면 컨텍스트 시작에 실패한다")
    void enabledWithoutBucket_failsFast() {
        contextRunner
                .withPropertyValues(
                        "routinely.storage.s3.region=ap-northeast-2",
                        "routinely.storage.s3.access-key=test",
                        "routinely.storage.s3.secret-key=test"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("routinely.storage.s3.bucket must not be blank when S3 storage is enabled"));
    }

    @Test
    @DisplayName("endpoint 가 http(s) URL 이 아니면 컨텍스트 시작에 실패한다")
    void invalidEndpoint_failsFast() {
        contextRunner
                .withPropertyValues(
                        "routinely.storage.s3.bucket=routinely-dev",
                        "routinely.storage.s3.region=ap-northeast-2",
                        "routinely.storage.s3.endpoint=ftp://localhost:4566",
                        "routinely.storage.s3.access-key=test",
                        "routinely.storage.s3.secret-key=test"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("routinely.storage.s3.endpoint must be an http(s) URL"));
    }
}
