package com.hanstep.document.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.document.dto.DocumentResponse;
import com.hanstep.document.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Document", description = "외국인등록증 업로드·OCR 결과 확인·확정(앞면만)")
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Operation(summary = "외국인등록증 앞면 업로드(DOC-01)",
            description = "multipart 파트 file 하나(image/jpeg·image/png, 10MB 이하). 형식·크기·파일 앞부분(매직 바이트)을 검사한 뒤 "
                    + "비공개 버킷에 저장하고 OCR 작업을 등록한다. OCR 진행 상태는 응답·조회의 ocrJob 으로 본다. "
                    + "같은 사진(같은 내용)을 다시 올리면 새로 저장하지 않고 기존 문서를 200 으로 준다.")
    @ApiResponse(responseCode = "202", description = "저장하고 OCR 작업 등록. ocrJob.publishStatus 가 PUBLISHED 가 아니면 발행 대기")
    @ApiResponse(responseCode = "200", description = "같은 사진으로 이미 올린 문서(새 업로드·새 작업 없음)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: file 없음·빈 파일·형식(JPEG·PNG 아님)·10MB 초과·"
            + "내용이 형식과 다름(details.fields[0].field=file)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "등록증 앞면 이미지(JPEG·PNG, 10MB 이하)") @RequestPart("file") MultipartFile file) {
        DocumentService.UploadResult result = documentService.upload(userId, file);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.body());
    }
}
