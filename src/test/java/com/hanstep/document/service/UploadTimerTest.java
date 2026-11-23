package com.hanstep.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.service.UploadTimer.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 업로드 처리 시간 한 줄(#167)의 형식과, 시간을 재는 감싸기가 트랜잭션 동작(커밋·롤백·예외)을 바꾸지 않는지.
 * DB 없이 돈다(실제 업로드 흐름의 로그는 DocumentUploadTimingLogTest).
 */
class UploadTimerTest {

    private static final long MS = 1_000_000L;

    @Test
    void 새_문서는_모든_구간이_한_줄에_나온다() {
        String line = UploadTimer.format(44L, DocumentSide.FRONT, Outcome.CREATED, 828_369,
                780 * MS, 712 * MS, 747 * MS, 760 * MS);

        assertThat(line).isEqualTo("document upload: documentId=44 side=FRONT result=CREATED bytes=828369"
                + " r2PutMs=712 dbMs=35 commitPublishMs=13 totalMs=780");
    }

    @Test
    void 같은_사진이라_트랜잭션도_PUT도_없으면_bytes와_totalMs만() {
        String line = UploadTimer.format(44L, DocumentSide.FRONT, Outcome.EXISTING, 828_369, 40 * MS, -1, -1, -1);

        assertThat(line).isEqualTo("document upload: documentId=44 side=FRONT result=EXISTING bytes=828369 totalMs=40");
    }

    @Test
    void 트랜잭션이_끝나면_커밋하고_안쪽_시간과_커밋_시간을_적는다() {
        PlatformTransactionManager manager = manager();
        UploadTimer timer = new UploadTimer();

        Long id = timer.inTransaction(new TransactionTemplate(manager), () -> {
            timer.r2Put(() -> { });
            return 7L;
        });

        assertThat(id).isEqualTo(7L);
        verify(manager).commit(any());
        verify(manager, never()).rollback(any());
        assertThat(timer.line(7L, DocumentSide.FRONT, Outcome.CREATED, 10))
                .matches("document upload: documentId=7 side=FRONT result=CREATED bytes=10"
                        + " r2PutMs=\\d+ dbMs=\\d+ commitPublishMs=\\d+ totalMs=\\d+");
    }

    @Test
    void 안쪽에서_예외가_나면_지금처럼_롤백하고_그_예외가_나간다() {
        PlatformTransactionManager manager = manager();
        UploadTimer timer = new UploadTimer();
        IllegalStateException failure = new IllegalStateException("R2 PUT failed");

        assertThatThrownBy(() -> timer.inTransaction(new TransactionTemplate(manager), () -> {
            timer.r2Put(() -> {
                throw failure;
            });
            return 7L;
        })).isSameAs(failure);

        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
        // 실패한 구간은 적지 않는다(실패 요청은 로그 범위 밖)
        assertThat(timer.line(7L, DocumentSide.FRONT, Outcome.CREATED, 10)).doesNotContain("r2PutMs", "dbMs");
    }

    private static PlatformTransactionManager manager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
