package com.hanstep.document.service;

import com.hanstep.document.domain.DocumentSide;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 등록증 업로드 한 번의 구간별 처리 시간(#167). 요청 하나당 INFO 한 줄로 남긴다. 시간만 재고 순서·트랜잭션은 바꾸지 않는다.
 * 남기는 것: documentId·면·결과·받은 바이트 수·구간별 ms. 남기지 않는 것: object key·파일명·sha256·userId·OCR 값.
 *
 * <p>구간(System.nanoTime 기준, ms 버림):
 * <ul>
 *   <li>r2PutMs: storage.put 호출 시간. PUT 을 하지 않으면(EXISTING) 없음</li>
 *   <li>dbMs: 트랜잭션 안 작업에서 r2PutMs 를 뺀 시간(문서 저장·옛 문서 삭제·OCR 작업 등록·refresh)</li>
 *   <li>commitPublishMs: 커밋 + 커밋 후 OCR 작업 발행. 발행은 커밋한 스레드에서 broker confirm 을 기다린다
 *       (AiJobPublishListener, 최대 hanstep.ai-jobs.confirm-timeout)</li>
 *   <li>totalMs: uploadSide 시작부터 응답 본문을 만든 뒤까지(검사·sha256·조회·트랜잭션·응답 조립).
 *       multipart 본문은 그 전에 다 받아 두므로 폰 → 서버 전송 시간은 들어가지 않는다</li>
 * </ul>
 * 실패(예외)로 끝난 업로드는 남기지 않는다(#156 client error 로그·기존 ERROR 로그로 본다).
 */
final class UploadTimer {

    enum Outcome {
        /** 새 문서 */
        CREATED,
        /** 같은 사진이라 기존 문서를 돌려줌(R2 PUT 없음) */
        EXISTING,
        /** 같은 사진의 옛 문서(OCR 실패 #73·확정 전 원본 만료 #165)를 지우고 새 문서 */
        REPLACED
    }

    private final long startNanos = System.nanoTime();
    private long r2PutNanos = -1;
    private long txWorkNanos = -1;
    private long txNanos = -1;

    /** storage.put 을 그대로 부르고 걸린 시간만 적는다. 예외는 그대로 나간다. */
    void r2Put(Runnable put) {
        long start = System.nanoTime();
        put.run();
        r2PutNanos = System.nanoTime() - start;
    }

    /**
     * tx.execute(status -> work) 를 그대로 부르고, 안쪽 작업 시간과 커밋(+커밋 후 발행)까지의 시간을 적는다.
     * 예외는 그대로 나가고 롤백도 tx 가 지금처럼 한다(예외로 끝나면 시간을 적지 않는다).
     */
    <T> T inTransaction(TransactionTemplate tx, Supplier<T> work) {
        long start = System.nanoTime();
        T result = tx.execute(status -> {
            long workStart = System.nanoTime();
            T value = work.get();
            txWorkNanos = System.nanoTime() - workStart;
            return value;
        });
        txNanos = System.nanoTime() - start;
        return result;
    }

    /** 반환 직전에 부른다. */
    String line(Long documentId, DocumentSide side, Outcome outcome, int bytes) {
        return format(documentId, side, outcome, bytes, System.nanoTime() - startNanos,
                r2PutNanos, txWorkNanos, txNanos);
    }

    /** 잰 값(-1 은 재지 않음)으로 한 줄을 만든다. */
    static String format(Long documentId, DocumentSide side, Outcome outcome, int bytes, long totalNanos,
                         long r2PutNanos, long txWorkNanos, long txNanos) {
        StringBuilder line = new StringBuilder("document upload: documentId=").append(documentId)
                .append(" side=").append(side)
                .append(" result=").append(outcome)
                .append(" bytes=").append(bytes);
        if (r2PutNanos >= 0) {
            line.append(" r2PutMs=").append(ms(r2PutNanos));
        }
        if (txWorkNanos >= 0) {
            line.append(" dbMs=").append(ms(txWorkNanos - Math.max(r2PutNanos, 0)));
        }
        if (txWorkNanos >= 0 && txNanos >= 0) {
            line.append(" commitPublishMs=").append(ms(txNanos - txWorkNanos));
        }
        return line.append(" totalMs=").append(ms(totalNanos)).toString();
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
