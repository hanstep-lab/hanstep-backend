package com.hanstep.document.service;

import com.hanstep.aijob.domain.AiJob;
import com.hanstep.aijob.domain.AiJobResourceType;
import com.hanstep.aijob.domain.AiJobType;
import com.hanstep.aijob.repository.AiJobRepository;
import com.hanstep.aijob.service.AiJobEnqueueService;
import com.hanstep.aijob.service.AiJobRepublishService;
import com.hanstep.common.error.BusinessException;
import com.hanstep.common.error.ErrorCode;
import com.hanstep.document.domain.Document;
import com.hanstep.document.domain.DocumentSide;
import com.hanstep.document.domain.DocumentStatus;
import com.hanstep.document.dto.DocumentResponse;
import com.hanstep.document.repository.DocumentFieldRepository;
import com.hanstep.document.repository.DocumentRepository;
import com.hanstep.document.storage.ArcImageStorage;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 외국인등록증 앞면 업로드(DOC-01). 기준선 v1 결정: Spring 이 받아 검사한 뒤 R2 에 넣는다(① multipart).
 * 이미지 내용·OCR 값은 로그에 남기지 않는다.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** spring.servlet.multipart.max-file-size 와 같은 값. 설정을 바꾸면 같이 바꾼다. */
    static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 원본 최대 보관 기한(retention_until). 기한 지난 원본 삭제는 후속 이슈. */
    static final Duration RETENTION = Duration.ofHours(24);
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png");
    /** multipart 파트 이름(지수 합의 11/16). */
    public static final String PART_FRONT = "front";
    public static final String PART_BACK = "back";

    private final DocumentRepository documentRepository;
    private final DocumentFieldRepository documentFieldRepository;
    private final AiJobRepository aiJobRepository;
    private final AiJobEnqueueService aiJobEnqueueService;
    private final AiJobRepublishService republishService;
    private final ArcImageStorage storage;
    private final TransactionTemplate tx;
    private final EntityManager entityManager;

    public DocumentService(DocumentRepository documentRepository, DocumentFieldRepository documentFieldRepository,
                           AiJobRepository aiJobRepository, AiJobEnqueueService aiJobEnqueueService,
                           AiJobRepublishService republishService, ArcImageStorage storage, TransactionTemplate tx,
                           EntityManager entityManager) {
        this.documentRepository = documentRepository;
        this.documentFieldRepository = documentFieldRepository;
        this.aiJobRepository = aiJobRepository;
        this.aiJobEnqueueService = aiJobEnqueueService;
        this.republishService = republishService;
        this.storage = storage;
        this.tx = tx;
        this.entityManager = entityManager;
    }

    /** created=false 면 같은 사진으로 이미 올린 문서(새 업로드·새 작업 없음). */
    public record UploadResult(boolean created, DocumentResponse body) {
    }

    /**
     * multipart 파트 front(필수)·back(선택). 뒷면은 아직 받지 않는다(back 이 오면 400 BACK_NOT_SUPPORTED, 지수 합의 11/16).
     * 뒷면을 열 때는 이 400 만 풀고 같은 {@link #uploadSide} 로 BACK 문서·작업을 하나 더 만든다.
     */
    public UploadResult upload(Long userId, MultipartFile front, MultipartFile back) {
        if (back != null) {
            throw new BusinessException(ErrorCode.BACK_NOT_SUPPORTED,
                    Map.of("fields", List.of(Map.of("field", PART_BACK, "reason", "back side is not supported yet"))));
        }
        if (front == null) {
            throw invalidPart(PART_FRONT, "required part");
        }
        return uploadSide(userId, DocumentSide.FRONT, front);
    }

    /**
     * 면 하나: 검사(형식·크기·매직 바이트) → 같은 사진이면 기존 문서 → 아니면 한 트랜잭션에서 documents 저장, R2 PUT,
     * OCR 작업 등록. R2 PUT 이 실패하면 롤백되어 행이 남지 않고, 롤백되면 그 키를 지운다(PUT 이 응답 전에 끊긴 경우 포함).
     * OCR 작업은 커밋 후 발행된다(실패해도 응답은 성공, 발행 대기로 남아 재발행 대상).
     */
    private UploadResult uploadSide(Long userId, DocumentSide side, MultipartFile file) {
        String part = side.name().toLowerCase(Locale.ROOT);
        String contentType = contentType(part, file);
        byte[] bytes = read(part, file);
        checkContent(part, bytes, contentType);
        String sha256 = sha256(bytes);

        Optional<Document> existing = documentRepository.findByUserIdAndSha256(userId, sha256);
        if (existing.isPresent()) {
            return new UploadResult(false, view(existing.get()));
        }
        Long documentId;
        try {
            documentId = tx.execute(status -> create(userId, side, contentType, bytes, sha256));
        } catch (DataIntegrityViolationException e) {
            // 같은 사진이 동시에 두 번 올라왔다. 먼저 저장된 문서를 준다
            Document first = documentRepository.findByUserIdAndSha256(userId, sha256).orElseThrow(() -> e);
            return new UploadResult(false, view(first));
        }
        return new UploadResult(true, view(documentRepository.findById(documentId).orElseThrow()));
    }

    private Long create(Long userId, DocumentSide side, String contentType, byte[] bytes, String sha256) {
        // 키는 R2 업로드 전에 만든다: arc/{side}/{UUID}.{ext}
        String objectKey = "arc/%s/%s.%s".formatted(side.name().toLowerCase(Locale.ROOT), UUID.randomUUID(),
                EXTENSIONS.get(contentType));
        Document doc = documentRepository.saveAndFlush(Document.uploadedArc(userId, side, objectKey, contentType,
                bytes.length, sha256, Instant.now().plus(RETENTION)));
        // PUT 전에 등록한다: PUT 이 응답 전에 끊겨도(객체는 써졌을 수 있음) 롤백되면 지운다. 없는 키 삭제는 성공으로 본다
        deleteOnRollback(doc.getId(), objectKey);
        storage.put(objectKey, bytes, contentType);
        aiJobEnqueueService.enqueue(AiJobType.OCR, AiJobResourceType.DOCUMENT, doc.getId(), userId,
                ocrIdempotencyKey(doc.getId()));
        entityManager.refresh(doc);   // created_at(DB 기본값)
        return doc.getId();
    }

    /** OCR 작업 멱등 키(ai_jobs uq (job_type, idempotency_key)). */
    static String ocrIdempotencyKey(Long documentId) {
        return "ocr:" + documentId;
    }

    // R2 에는 올렸는데 DB 가 롤백되면 가리키는 행이 없는 객체가 남는다. 롤백 뒤 지운다(실패하면 로그만)
    private void deleteOnRollback(Long documentId, String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    storage.delete(objectKey);
                } catch (RuntimeException e) {
                    log.error("document {} rolled back but uploaded original was not deleted", documentId, e);
                }
            }
        });
    }

    /**
     * U2(a) OCR 다시 시도. 본인 문서이고, OCR 결과 전(UPLOADED)이며, OCR 작업이 발행 대기(PENDING 이면서
     * NOT_PUBLISHED·PUBLISH_FAILED)일 때만 같은 작업을 다시 보낸다. 아니면 409. 트랜잭션 밖에서 불러 재발행 커밋·발행이
     * 끝난 뒤 다시 읽어 준다.
     */
    public DocumentResponse retryOcr(Long userId, Long documentId) {
        Document doc = ownDocument(userId, documentId);
        AiJob job = latestOcrJob(doc.getId());
        boolean requested = doc.getStatus() == DocumentStatus.UPLOADED && job != null && job.isAwaitingPublish()
                && republishService.republish(job.getId(), null);
        if (!requested) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "OCR job is not waiting for publish",
                    Map.of("documentId", documentId, "status", doc.getStatus().name()));
        }
        return view(documentRepository.findById(documentId).orElseThrow());
    }

    /** DOC-02. 본인 문서만(남의 문서·없는 문서는 404). */
    @Transactional(readOnly = true)
    public DocumentResponse get(Long userId, Long documentId) {
        return view(ownDocument(userId, documentId));
    }

    Document ownDocument(Long userId, Long documentId) {
        return documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("documentId", documentId)));
    }

    DocumentResponse view(Document doc) {
        return DocumentResponse.of(doc, latestOcrJob(doc.getId()), documentFieldRepository.findByDocumentId(doc.getId()));
    }

    private AiJob latestOcrJob(Long documentId) {
        return aiJobRepository.findFirstByJobTypeAndResourceTypeAndResourceIdOrderByIdDesc(AiJobType.OCR,
                AiJobResourceType.DOCUMENT, documentId).orElse(null);
    }

    // ---------- 검사 ----------

    private static String contentType(String part, MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).trim();
        int parameters = type.indexOf(';');
        if (parameters >= 0) {
            type = type.substring(0, parameters).trim();
        }
        if (type.equals("image/jpg")) {
            type = "image/jpeg";
        }
        if (!EXTENSIONS.containsKey(type)) {
            throw invalidPart(part, "must be image/jpeg or image/png");
        }
        return type;
    }

    private static byte[] read(String part, MultipartFile file) {
        if (file.isEmpty()) {
            throw invalidPart(part, "empty file");
        }
        if (file.getSize() > MAX_BYTES) {
            throw invalidPart(part, "file too large");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 확장자·Content-Type 만 믿지 않고 앞부분 바이트(매직 넘버)가 선언한 형식과 맞는지 본다
    private static void checkContent(String part, byte[] b, String contentType) {
        boolean jpeg = b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        boolean png = b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
        if (contentType.equals("image/jpeg") ? !jpeg : !png) {
            throw invalidPart(part, "content does not match " + contentType);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException invalidPart(String part, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", part, "reason", reason))));
    }
}
