package com.hanstep.aijob.messaging;

import com.hanstep.aijob.domain.AiJobType;
import java.util.Arrays;

/** 큐 선언과 발행에서 같은 경로를 쓴다. 경로가 없는 작업 종류(DOCUMENT_ANALYSIS)는 발행하지 않는다. */
public enum AiJobRoute {
    POLICY_INGEST(AiJobType.POLICY_INGEST, "policy.ingest"),
    // 등록증 OCR(#73). 소비자는 ai-worker(합의안 Q2)
    OCR(AiJobType.OCR, "ocr.request");

    public static final String EXCHANGE = "hanstep.ai";
    private final AiJobType jobType;
    private final String routingKey;

    AiJobRoute(AiJobType jobType, String routingKey) {
        this.jobType = jobType;
        this.routingKey = routingKey;
    }

    public String routingKey() { return routingKey; }
    public String retryQueue() { return routingKey + ".retry"; }
    public String deadLetterQueue() { return routingKey + ".dlq"; }

    public static AiJobRoute forType(AiJobType type) {
        return Arrays.stream(values()).filter(route -> route.jobType == type).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported AI job type: " + type));
    }
}
