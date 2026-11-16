package com.hanstep.document.storage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml 의 hanstep.arc-storage.* 값. 외국인등록증 원본 버킷(R2, S3 호환) 접속 정보.
 * 정책·상품 키(R2_ACCESS_KEY_ID)가 아니라 등록증 버킷 전용 키(R2_OCR_*)를 쓴다.
 *
 * @param endpoint        .env 의 R2_ENDPOINT_URL
 * @param region          .env 의 R2_REGION. R2 는 auto
 * @param accessKeyId     .env 의 R2_OCR_ACCESS_KEY_ID
 * @param secretAccessKey .env 의 R2_OCR_SECRET_ACCESS_KEY
 * @param bucket          .env 의 R2_ARC_BUCKET(hanstep-arc-private)
 */
@ConfigurationProperties(prefix = "hanstep.arc-storage")
public record ArcStorageProperties(String endpoint, @DefaultValue("auto") String region, String accessKeyId,
                                   String secretAccessKey, String bucket) {

    /** 비어 있는 설정 이름. 비어 있으면 업로드·삭제 때 오류를 낸다(앱 시작은 막지 않음). */
    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (blank(endpoint)) {
            missing.add("R2_ENDPOINT_URL");
        }
        if (blank(accessKeyId)) {
            missing.add("R2_OCR_ACCESS_KEY_ID");
        }
        if (blank(secretAccessKey)) {
            missing.add("R2_OCR_SECRET_ACCESS_KEY");
        }
        if (blank(bucket)) {
            missing.add("R2_ARC_BUCKET");
        }
        return missing;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
