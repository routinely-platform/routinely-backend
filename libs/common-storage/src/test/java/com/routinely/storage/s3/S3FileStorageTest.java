package com.routinely.storage.s3;

import com.routinely.core.exception.BusinessException;
import com.routinely.storage.FileUploadCommand;
import com.routinely.storage.StoredFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("S3FileStorage")
class S3FileStorageTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final S3Properties properties = properties();
    private final S3FileStorage storage = new S3FileStorage(s3Client, properties);

    @Test
    @DisplayName("upload 시 PutObject 를 호출하고 저장된 URL 을 반환한다")
    void upload_putsObjectAndReturnsUrl() {
        FileUploadCommand command = command("photo.jpg");

        StoredFile stored = storage.upload(command);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo("routinely-dev");
        assertThat(request.key()).isEqualTo(stored.key());
        assertThat(request.contentType()).isEqualTo("image/jpeg");
        assertThat(request.contentLength()).isEqualTo(5L);
        assertThat(stored.key()).matches("routine-executions/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
        assertThat(stored.url()).isEqualTo("http://localhost:4566/routinely-dev/" + stored.key());
    }

    @Test
    @DisplayName("delete 시 버킷/키로 DeleteObjectRequest 를 보낸다")
    void delete_sendsDeleteObjectRequest() {
        storage.delete("routine-executions/2026/07/abc.jpg");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("routinely-dev");
        assertThat(captor.getValue().key()).isEqualTo("routine-executions/2026/07/abc.jpg");
    }

    @Test
    @DisplayName("buildKey 는 {directory}/{yyyy}/{MM}/{uuid}.{ext} 형식을 만든다")
    void buildKey_hasExpectedShape() {
        String key = storage.buildKey(command("photo.jpg"));

        assertThat(key).matches("routine-executions/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
    }

    @Test
    @DisplayName("buildKey 는 contentType 기준으로 이미지 확장자를 정규화한다")
    void buildKey_normalizesExtensionFromContentType() {
        FileUploadCommand command = command("photo.jpeg.exe", "image/png");

        String key = storage.buildKey(command);

        assertThat(key).matches("routine-executions/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
    }

    @Test
    @DisplayName("buildKey 는 알 수 없는 MIME 의 위험한 파일명 확장자를 버린다")
    void buildKey_ignoresUnsafeExtension() {
        FileUploadCommand command = command("photo.jpg/extra", "application/octet-stream");

        String key = storage.buildKey(command);

        assertThat(key).matches("routine-executions/\\d{4}/\\d{2}/[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("buildKey 는 안전하지 않은 directory 를 거부한다")
    void buildKey_rejectsUnsafeDirectory() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        FileUploadCommand command = new FileUploadCommand("../profiles", "photo.jpg", "image/jpeg",
                bytes.length, new ByteArrayInputStream(bytes));

        assertThatThrownBy(() -> storage.buildKey(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("directory must be a safe relative path");
    }

    @Test
    @DisplayName("buildKey 는 점 두 개가 포함된 정상 directory segment 를 허용한다")
    void buildKey_allowsDotsInsideDirectorySegment() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        FileUploadCommand command = new FileUploadCommand("profile..images", "photo.jpg", "image/jpeg",
                bytes.length, new ByteArrayInputStream(bytes));

        String key = storage.buildKey(command);

        assertThat(key).matches("profile\\.\\.images/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg");
    }

    @Test
    @DisplayName("resolveUrl 은 endpoint override 시 path-style URL 을 만든다")
    void resolveUrl_pathStyleWhenEndpointSet() {
        String url = storage.resolveUrl("routine-executions/2026/07/abc.jpg");

        assertThat(url).isEqualTo("http://localhost:4566/routinely-dev/routine-executions/2026/07/abc.jpg");
    }

    @Test
    @DisplayName("resolveUrl 은 실 AWS 에서 pathStyleAccess=true 이면 path-style URL 을 만든다")
    void resolveUrl_pathStyleWhenAwsAndPathStyleEnabled() {
        properties.setEndpoint(null);
        properties.setPathStyleAccess(true);
        properties.setBucket("routinely.images");

        String url = storage.resolveUrl("routine-executions/2026/07/abc.jpg");

        assertThat(url).isEqualTo("https://s3.ap-northeast-2.amazonaws.com/routinely.images/routine-executions/2026/07/abc.jpg");
    }

    @Test
    @DisplayName("resolveUrl 은 실 AWS 에서 pathStyleAccess=false 이면 virtual-hosted URL 을 만든다")
    void resolveUrl_virtualHostedWhenAwsAndPathStyleDisabled() {
        properties.setEndpoint(null);
        properties.setPathStyleAccess(false);

        String url = storage.resolveUrl("routine-executions/2026/07/abc.jpg");

        assertThat(url).isEqualTo("https://routinely-dev.s3.ap-northeast-2.amazonaws.com/routine-executions/2026/07/abc.jpg");
    }

    @Test
    @DisplayName("resolveUrl 은 publicBaseUrl 이 있으면 우선 사용한다")
    void resolveUrl_prefersPublicBaseUrl() {
        properties.setPublicBaseUrl("https://cdn.routinely.example/images/");

        String url = storage.resolveUrl("routine-executions/2026/07/abc.jpg");

        assertThat(url).isEqualTo("https://cdn.routinely.example/images/routine-executions/2026/07/abc.jpg");
    }

    @Test
    @DisplayName("upload 실패 시 SDK 예외를 cause 로 보존한다")
    void uploadFailure_preservesCause() {
        SdkClientException cause = SdkClientException.create("boom");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(cause);
        FileUploadCommand command = command("photo.jpg");

        assertThatThrownBy(() -> storage.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasCause(cause);
    }

    private static FileUploadCommand command(String filename) {
        return command(filename, "image/jpeg");
    }

    private static FileUploadCommand command(String filename, String contentType) {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        return new FileUploadCommand("routine-executions", filename, contentType,
                bytes.length, new ByteArrayInputStream(bytes));
    }

    private static S3Properties properties() {
        S3Properties props = new S3Properties();
        props.setBucket("routinely-dev");
        props.setRegion("ap-northeast-2");
        props.setEndpoint("http://localhost:4566");
        props.setPathStyleAccess(true);
        return props;
    }
}
