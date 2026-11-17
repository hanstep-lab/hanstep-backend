package com.hanstep.document.dto;

import com.hanstep.document.domain.Document;
import com.hanstep.document.domain.DocumentSide;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OCR Worker 가 원본을 읽을 위치. objectKey 로 R2 원본을 받아 sha256 으로 대조한다.
 * 개인정보(사용자·OCR 값)는 넣지 않는다.
 */
@Schema(description = "OCR 원본 위치(Worker 용)")
public record DocumentSourceResponse(
        @Schema(description = "R2 hanstep-arc-private 버킷 키 arc/{side}/{UUID}.jpg(사용자·문서 id 없음). 원본 삭제 후 null",
                nullable = true,
                example = "arc/front/3f1c2a9e-7b4d-4e8a-9c21-5d6f0b8a1e47.jpg")
        String objectKey,

        @Schema(description = "면. 등록증 OCR 은 FRONT 만", example = "FRONT")
        DocumentSide side,

        @Schema(description = "원본 바이트 SHA-256(소문자 hex 64자)",
                example = "47787198e094ced88f6c38bbb328f4faa4fa4b00f25f3bb8911195af472285f5")
        String sha256
) {
    public static DocumentSourceResponse of(Document doc) {
        return new DocumentSourceResponse(doc.getObjectKey(), doc.getSide(), doc.getSha256());
    }
}
