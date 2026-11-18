package com.hanstep.visa.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.document.dto.DocumentFieldsUpdateRequest;
import com.hanstep.visa.dto.VisaProfileResponse;
import com.hanstep.visa.service.VisaProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Visa Profile", description = "체류 정보(visa_profiles) 직접 저장. OCR 이 실패하거나 오래 걸릴 때 쓴다")
@RestController
@RequestMapping("/api/v1/visa/profile")
public class VisaProfileController {

    private final VisaProfileService visaProfileService;

    public VisaProfileController(VisaProfileService visaProfileService) {
        this.visaProfileService = visaProfileService;
    }

    @Operation(summary = "체류 정보 직접 저장",
            description = "요청 형식은 DOC-03 과 같다(fields[{fieldKey, value}]). fieldKey 는 nationality·birthDate·"
                    + "visaType·stayExpiry 이고 네 항목 모두 필수. 값 검사는 DOC-04 확정과 같다. visa_profiles 의 이 4개만 "
                    + "바꾸고 입국일·이전 체류 개월은 그대로 둔다(근거 문서 없음, 증빙 상태 DECLARED). "
                    + "온보딩 ID_DOCUMENT 단계를 COMPLETED 로 기록한다. 같은 값으로 다시 보내도 같은 결과.")
    @ApiResponse(responseCode = "200", description = "저장(저장된 값과 daysRemaining)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 모르는·중복 fieldKey, nameEn·registrationNo, "
            + "빠진 항목(field 는 항목 키, reason required), 형식 오류(nationality 는 alpha-3, 날짜는 YYYY-MM-DD, "
            + "birthDate 는 미래 불가, visaType 은 등록된 체류자격). details.fields 에 경로·이유(값은 싣지 않음)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping
    public VisaProfileResponse save(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DocumentFieldsUpdateRequest request) {
        return visaProfileService.save(userId, request);
    }
}
