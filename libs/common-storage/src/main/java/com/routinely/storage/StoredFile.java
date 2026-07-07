package com.routinely.storage;

/**
 * 파일 저장 결과.
 *
 * @param key 저장소 내 오브젝트 키 (삭제 등 후속 작업에 사용)
 * @param url 외부에서 접근 가능한 URL
 */
public record StoredFile(String key, String url) {
}
