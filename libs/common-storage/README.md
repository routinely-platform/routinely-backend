# common-storage

파일 업로드/삭제를 위한 공통 저장소 모듈. `FileStorage` 인터페이스와 AWS SDK v2 기반
`S3FileStorage` 구현체를 제공한다. (이슈 #54)

## 사용법

파일 업로드가 필요한 서비스의 `build.gradle` 에 의존성을 추가한다.

```gradle
implementation project(':libs:common-storage')
```

`StorageAutoConfiguration` 이 `FileStorage` Bean 을 자동 등록하므로 주입받아 사용한다.

```java
@Service
@RequiredArgsConstructor
public class RoutinePhotoService {
    private final FileStorage fileStorage;

    public String upload(MultipartFile file) {
        FileUploadCommand command = new FileUploadCommand(
                "routine-executions",
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file.getInputStream());
        return fileStorage.upload(command).url();
    }
}
```

## 설정 (application.yml)

```yaml
routinely:
  storage:
    s3:
      enabled: true
      bucket: routinely-dev
      region: ap-northeast-2
      endpoint:                 # 비우면 실 AWS S3
      public-base-url:          # CDN/공개 버킷 URL. 비우면 S3 기본 URL
      path-style-access: false  # LocalStack 이면 true
      access-key: ${AWS_ACCESS_KEY:}
      secret-key: ${AWS_SECRET_KEY:}
```

- `endpoint` 를 비우면 실 AWS S3 로 접속한다(기본).
- `public-base-url` 이 있으면 업로드 결과 URL 은 `{public-base-url}/{key}` 로 반환한다.
  운영에서 CloudFront 나 공개 버킷 도메인을 사용할 때 지정한다.
- `access-key`/`secret-key` 를 비우면 AWS 기본 자격증명 provider chain(환경변수, 인스턴스 프로파일 등)을 사용한다.

## 로컬 개발 — LocalStack

실 AWS 없이 로컬에서 테스트하려면 LocalStack 을 사용한다. `infra/docker-compose.yml`
에 `localstack` 서비스가 포함되어 있으며, 기동 시 `routinely-dev` 버킷을 자동 생성한다.

```bash
docker compose -f infra/docker-compose.yml up -d localstack
```

`local` 프로파일 설정 예시:

```yaml
routinely:
  storage:
    s3:
      endpoint: http://localhost:4566
      public-base-url: http://localhost:4566/routinely-dev
      path-style-access: true
      access-key: test
      secret-key: test
```

업로드 결과 확인:

```bash
docker exec routinely-localstack awslocal s3 ls s3://routinely-dev --recursive
```

## 오브젝트 키 규칙

`{directory}/{yyyy}/{MM}/{uuid}.{ext}` — 예: `routine-executions/2026/07/a1b2c3….jpg`
