package com.hanstep.document.controller;

import com.hanstep.common.config.OpenApiConfig;
import com.hanstep.common.error.ErrorResponse;
import com.hanstep.document.dto.DocumentSourceResponse;
import com.hanstep.document.dto.OcrResultRequest;
import com.hanstep.document.service.DocumentInternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal · Document", description = "AI Worker → Spring 등록증 OCR 내부 API (X-Internal-Token)")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_TOKEN)
@RestController
public class DocumentInternalController {

    // 합성 데이터(가상 인물 Reyes). 실제 등록증 값이 아니다
    private static final String OCR_RESULT_EXAMPLE = """
            {
              "documentId": 42,
              "modelVersion": "ppocrv5-ko-mobile-int8",
              "latencyMs": 842,
              "side": "FRONT",
              "fields": [
                {"fieldKey": "nameEn", "value": "MARK ANTHONY REYES", "confidence": 0.9812, "isMasked": false},
                {"fieldKey": "nationality", "value": "PHL", "confidence": 0.9655, "isMasked": false},
                {"fieldKey": "birthDate", "value": "1995-08-15", "confidence": 0.9734, "isMasked": false},
                {"fieldKey": "visaType", "value": "E-9", "confidence": 0.9521, "isMasked": false},
                {"fieldKey": "stayExpiry", "value": null, "confidence": null, "isMasked": false},
                {"fieldKey": "registrationNo", "value": "950815-5******", "confidence": 0.9907, "isMasked": true}
              ]
            }""";

    private final DocumentInternalService documentInternalService;

    public DocumentInternalController(DocumentInternalService documentInternalService) {
        this.documentInternalService = documentInternalService;
    }

    @Operation(summary = "OCR 원본 위치 조회 (Worker)",
            description = "OCR Worker 가 메시지의 documentId 로 원본 위치를 다시 조회한다. objectKey 로 R2 원본을 받고 "
                    + "sha256 으로 대조한다. 개인정보(사용자·OCR 값)는 없다. 원본이 삭제됐으면 objectKey 는 null.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "documents 행 없음(RESOURCE_NOT_FOUND, details.documentId)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/internal/v1/ai-context/documents/{documentId}/source")
    public DocumentSourceResponse source(@PathVariable Long documentId) {
        return documentInternalService.source(documentId);
    }

    @Operation(summary = "OCR 결과 보고 (Worker)",
            description = "등록증 앞면 6개 항목(nameEn, nationality, birthDate, visaType, stayExpiry, registrationNo)을 "
                    + "한 번씩 보낸다. document_fields 에 저장(value null 은 MISSING, stayExpiry 는 항상 null)하고, "
                    + "문서를 PENDING_CONFIRMATION 으로 바꾸고 ocr_model_version·ocr_latency_ms 를 기록한다. "
                    + "같은 문서의 OCR 작업이 PENDING·QUEUED·PROCESSING 이면 COMPLETED·completed_at(Spring 수신 시각)으로 "
                    + "바꾼다. OCR 작업이 이미 FAILED 면 409 JOB_ALREADY_FINISHED 이고 아무것도 바꾸지 않는다. "
                    + "받는 문서 상태는 UPLOADED·OCR_QUEUED·OCR_PROCESSING. "
                    + "registrationNo 는 isMasked=true 이고 ######-#****** 형식이어야 한다(전체 번호는 400, 값은 오류에 "
                    + "싣지 않음). confidence 는 소수 4자리로 반올림해 저장한다. "
                    + "이미 반영된 결과와 fields(fieldKey·value·confidence·isMasked)·modelVersion 이 같으면 204 이고 "
                    + "아무것도 바꾸지 않는다(latencyMs 는 비교하지 않고 처음 값을 유지).")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = OcrResultRequest.class),
            examples = @ExampleObject(name = "Reyes 앞면(합성)", value = OCR_RESULT_EXAMPLE)))
    @ApiResponse(responseCode = "204", description = "반영됨(같은 결과 재전송 포함)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR(details.fields): 본문 형식 오류, documentId 가 "
            + "경로와 다름, side 가 FRONT 아님, 모르는·중복·빠진 fieldKey, registrationNo 마스킹 아님, confidence 0~1 밖",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "X-Internal-Token 없음·틀림",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "documents 행 없음(RESOURCE_NOT_FOUND, details.documentId)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE: 문서 side 가 다름(details.field·expected), "
            + "이미 다른 결과가 반영됨(PENDING_CONFIRMATION) 또는 결과를 받을 수 없는 문서 상태(CONFIRMED·FAILED), "
            + "details 에 documentId·status. JOB_ALREADY_FINISHED: 그 문서의 OCR 작업이 FAILED, "
            + "details 에 documentId·jobId·status",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/internal/v1/ai/documents/{documentId}/ocr-result")
    public ResponseEntity<Void> ocrResult(@PathVariable Long documentId,
                                          @Valid @RequestBody OcrResultRequest request) {
        documentInternalService.recordOcrResult(documentId, request);
        return ResponseEntity.noContent().build();
    }
}
