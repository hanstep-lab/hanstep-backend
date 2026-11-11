package com.hanstep.aijob.domain;

/** ai_jobs.publish_status. DB-MQ 정합성(미발행 재시도). */
public enum PublishStatus {
    NOT_PUBLISHED, PUBLISHED, PUBLISH_FAILED
}
