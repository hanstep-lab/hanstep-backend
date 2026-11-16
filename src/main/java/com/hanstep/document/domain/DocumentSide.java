package com.hanstep.document.domain;

/** documents.side. 등록증 OCR 은 앞면(FRONT)만 한다(엔진이 뒷면 미지원). */
public enum DocumentSide {
    FRONT, BACK, SINGLE
}
