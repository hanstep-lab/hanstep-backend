package com.hanstep.document.storage;

/** 외국인등록증 원본 이미지 저장소(비공개 버킷). 실패하면 ArcStorageException. */
public interface ArcImageStorage {

    void put(String objectKey, byte[] bytes, String contentType);

    /** 없는 키를 지워도 성공으로 본다(S3 DeleteObject 와 같음). */
    void delete(String objectKey);
}
