package com.hanstep.document.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.document.dto.DocumentFieldsUpdateRequest;
import com.hanstep.document.dto.DocumentResponse;
import com.hanstep.document.service.DocumentReviewService;
import com.hanstep.document.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Document", description = "외국인등록증 업로드·OCR 결과 확인·확정(앞면만)")
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentReviewService documentReviewService;

    public DocumentController(DocumentService documentService, DocumentReviewService documentReviewService) {
        this.documentService = documentService;
        this.documentReviewService = documentReviewService;
    }

    @Operation(summary = "외국인등록증 업로드(DOC-01, 앞면만)",
            description = "multipart 파트 front(필수, image/jpeg·image/png, 10MB 이하)·back(선택, 아직 지원 안 함). "
                    + "back 이 와도 무시한다(저장·검사하지 않음). 형식·크기·파일 앞부분(매직 바이트)을 검사한 뒤 비공개 버킷에 저장하고 "
                    + "OCR 작업을 등록한다. OCR 진행 상태는 응답·조회의 status·ocrJob 으로 본다. "
                    + "같은 사진(같은 내용)을 다시 올리면 새로 저장하지 않고 기존 문서를 200 으로 준다. "
                    + "단, 그 문서의 OCR 이 실패(status=FAILED)했으면 실패 문서를 지우고 새 문서·새 OCR 작업을 만들어 202 "
                    + "(documentId 가 바뀐다).")
    @ApiResponse(responseCode = "202", description = "저장하고 OCR 작업 등록(OCR 실패 문서를 같은 사진으로 다시 올린 경우 포함, "
            + "새 documentId). ocrJob.publishStatus 가 PUBLISHED 가 아니면 발행 대기")
    @ApiResponse(responseCode = "200", description = "같은 사진으로 이미 올린 문서(새 업로드·새 작업 없음). OCR 실패 문서는 해당 안 됨")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: front 없음·빈 파일·형식(JPEG·PNG 아님)·10MB 초과·"
            + "내용이 형식과 다름(details.fields[0].field=front)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "등록증 앞면 이미지(필수, JPEG·PNG, 10MB 이하)")
            @RequestPart(value = DocumentService.PART_FRONT, required = false) MultipartFile front,
            @Parameter(description = "등록증 뒷면 이미지(아직 지원 안 함: 보내도 무시하고 저장하지 않음)")
            @RequestPart(value = DocumentService.PART_BACK, required = false) MultipartFile back) {
        DocumentService.UploadResult result = documentService.upload(userId, front, back);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.body());
    }

    @Operation(summary = "등록증 문서·OCR 결과 조회(DOC-02)",
            description = "본인 문서만. OCR 진행은 ocrJob(status·publishStatus)으로 본다. OCR 작업이 실패하면 "
                    + "status=FAILED 와 failureCode(작업의 error_code). "
                    + "OCR 결과가 오면 status=PENDING_CONFIRMATION 이고 fields 6개(nameEn·nationality·birthDate·visaType·"
                    + "stayExpiry·registrationNo)가 온다. 신뢰도 0.8 미만이거나 못 읽은 항목은 needsReview=true. "
                    + "확정 후에는 value 가 null 이고 status·confidence 만 남는다. 등록번호는 마스킹 값만 준다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없거나 다른 사용자의 문서",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{documentId}")
    public DocumentResponse get(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "documents.id", example = "42") @PathVariable Long documentId) {
        return documentService.get(userId, documentId);
    }

    @Operation(summary = "OCR 다시 시도(발행 대기 작업 재발행)",
            description = "본문 없음. OCR 작업이 큐에 들어가지 못한 채(ocrJob.retryable=true: status PENDING 이면서 "
                    + "publishStatus NOT_PUBLISHED·PUBLISH_FAILED) 남았을 때 같은 작업을 다시 보낸다(새 작업 없음). "
                    + "서버도 1분마다 자동으로 다시 보낸다. 응답의 ocrJob.publishStatus 로 결과를 본다.")
    @ApiResponse(responseCode = "202", description = "다시 보냄(브로커가 받으면 publishStatus=PUBLISHED)")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없거나 다른 사용자의 문서",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: 발행 대기가 아님(이미 큐에 들어감·처리 중·끝남, "
            + "OCR 결과가 이미 옴)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{documentId}/ocr-retry")
    public ResponseEntity<DocumentResponse> retryOcr(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "documents.id", example = "42") @PathVariable Long documentId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(documentService.retryOcr(userId, documentId));
    }

    @Operation(summary = "OCR 항목 확인·수정(DOC-03)",
            description = "확인 화면(ON-003)에서 고친 값. 보낸 항목만 바꾼다(OCR 값과 같으면 CONFIRMED, 다르면 CORRECTED). "
                    + "OCR 결과 확인 대기(PENDING_CONFIRMATION)일 때만. 등록번호는 고칠 수 없다. "
                    + "앞면에 없는 stayExpiry(체류 만료일)는 여기서 넣어야 확정할 수 있다.")
    @ApiResponse(responseCode = "200", description = "성공(문서 전체)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 모르는·중복 fieldKey, registrationNo 수정, "
            + "형식 오류(nationality 는 alpha-3, 날짜는 YYYY-MM-DD, birthDate 는 미래 불가, stayExpiry 는 오늘 이전 불가, "
            + "visaType 은 등록된 체류자격). details.fields 에 경로·이유(값은 싣지 않음)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없거나 다른 사용자의 문서",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: OCR 결과 전(UPLOADED)이거나 이미 확정(CONFIRMED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PatchMapping("/{documentId}/fields")
    public DocumentResponse updateFields(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "documents.id", example = "42") @PathVariable Long documentId,
            @Valid @RequestBody DocumentFieldsUpdateRequest request) {
        return documentReviewService.updateFields(userId, documentId, request);
    }

    @Operation(summary = "OCR 확정 → 체류 프로필 반영(DOC-04)",
            description = "본문 없음. 국적·생년월일·체류자격·체류 만료일을 visa_profiles 에 반영하고(등록번호·영문 이름은 "
                    + "저장하지 않음), 원본 이미지와 OCR 항목 값을 지운다(status·confidence 만 남음). "
                    + "이미 확정한 문서를 다시 확정하면 그대로 200.")
    @ApiResponse(responseCode = "200", description = "확정(문서 전체, fields 의 value 는 null)")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "없거나 다른 사용자의 문서",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: OCR 결과 전(UPLOADED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_DATA: nationality·birthDate·visaType·stayExpiry 중 "
            + "값이 없거나 형식이 틀림(stayExpiry 는 오늘 이전 불가, details.fields 에 항목·이유). DOC-03 으로 고친 뒤 다시",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{documentId}/confirm")
    public DocumentResponse confirm(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "documents.id", example = "42") @PathVariable Long documentId) {
        return documentReviewService.confirm(userId, documentId);
    }
}
