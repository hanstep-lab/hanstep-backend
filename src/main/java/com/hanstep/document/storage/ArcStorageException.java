package com.hanstep.document.storage;

/** R2 업로드·삭제 실패. 메시지에 키·이미지 내용은 넣지 않는다. */
public class ArcStorageException extends RuntimeException {

    public ArcStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
