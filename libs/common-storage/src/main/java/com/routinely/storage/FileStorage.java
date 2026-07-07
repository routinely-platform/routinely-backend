package com.routinely.storage;

/**
 * 파일 저장소 추상화.
 *
 * <p>루틴 실행 사진 업로드 등에서 사용한다. 구현체(S3 등)를 교체해도
 * 호출 측 코드가 바뀌지 않도록 인터페이스로 분리한다.
 */
public interface FileStorage {

    /**
     * 파일을 업로드하고 저장 결과(오브젝트 키 + 접근 URL)를 반환한다.
     *
     * @param command 업로드 대상 파일 정보
     * @return 저장된 파일의 키와 URL
     */
    StoredFile upload(FileUploadCommand command);

    /**
     * 저장된 파일을 삭제한다.
     *
     * @param key 삭제할 오브젝트 키
     */
    void delete(String key);
}
