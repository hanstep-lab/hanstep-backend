package com.hanstep.document.service;

import com.hanstep.document.domain.Document;
import com.hanstep.document.domain.DocumentField;
import com.hanstep.document.repository.DocumentFieldRepository;
import com.hanstep.document.repository.DocumentRepository;
import com.hanstep.document.storage.ArcImageStorage;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록증 원본 보관 기한(retention_until) 만료 삭제(#162). 확정하지 않고 떠난 문서·OCR 실패 문서의 원본을 기한이 지나면 지운다.
 * DB 행이 없는 R2 객체(고아)는 찾을 수 없다. 그건 R2 수명 주기 규칙(arc-expire-1d)이 지운다.
 * object_key·항목 값은 로그에 남기지 않는다.
 */
@Service
public class ArcOriginalExpiryService {

    private final DocumentRepository documentRepository;
    private final DocumentFieldRepository documentFieldRepository;
    private final ArcImageStorage storage;

    public ArcOriginalExpiryService(DocumentRepository documentRepository,
                                    DocumentFieldRepository documentFieldRepository, ArcImageStorage storage) {
        this.documentRepository = documentRepository;
        this.documentFieldRepository = documentFieldRepository;
        this.storage = storage;
    }

    /** now 기준 기한이 지났는데 원본이 남은 문서 id. 기한이 이른 것부터 limit 개까지. */
    @Transactional(readOnly = true)
    public List<Long> findExpired(Instant now, int limit) {
        return documentRepository.findOriginalExpiredIds(now, Limit.of(limit));
    }

    /**
     * 문서 1건(트랜잭션 1개). 확정·OCR 결과 보고와 같은 잠금(문서 행 먼저)을 잡고 조건을 다시 본 뒤 R2 원본을 지우고, 그다음
     * object_key NULL·original_deleted_at·항목 값 삭제(status·confidence 는 남김)를 한다. 문서 상태는 바꾸지 않는다.
     * R2 삭제가 실패하면 예외로 롤백되어 행은 그대로 남고 다음 주기에 다시 한다. 이 순서를 바꾸지 않는다: DB 를 먼저 바꾸고
     * 커밋 뒤에 지우면, R2 삭제가 실패했을 때 지웠다고 기록된 채 원본이 남는다. R2 는 지웠는데 커밋이 실패하면 다음 주기에
     * 없는 키를 다시 지운다(성공으로 본다).
     *
     * @return 지웠으면 true. 잠근 뒤 보니 문서가 없어졌거나(재업로드·데모 초기화) 원본이 이미 지워졌거나(확정) 기한이 바뀌었으면 false
     */
    @Transactional
    public boolean deleteOriginal(Long documentId, Instant now) {
        Document doc = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (doc == null || !doc.originalExpired(now)) {
            return false;
        }
        storage.delete(doc.getObjectKey());
        documentFieldRepository.findByDocumentId(documentId).forEach(DocumentField::clearValues);
        doc.markOriginalExpired(Instant.now());
        return true;
    }
}
