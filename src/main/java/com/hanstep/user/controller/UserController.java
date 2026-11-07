package com.hanstep.user.controller;

import com.hanstep.common.error.ErrorResponse;
import com.hanstep.user.dto.LanguageUpdateRequest;
import com.hanstep.user.dto.UserMeResponse;
import com.hanstep.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "사용자")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "내 정보 조회 (USR-01)",
            description = "앱 시작 시 호출. onboarding.completed=false 면 currentStep 화면으로 이동한다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료, 또는 사용자 비활성",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/me")
    public UserMeResponse me(@Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return userService.getMe(userId);
    }

    @Operation(summary = "언어 변경 (USR-02)",
            description = "ON-001 언어 선택. en·ko·vi·zh. 바뀐 내 정보를 돌려준다(USR-01 과 같은 형식).")
    @ApiResponse(responseCode = "200", description = "변경 성공")
    @ApiResponse(responseCode = "400", description = "지원하지 않는 언어 또는 값 누락",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료, 또는 사용자 비활성",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PatchMapping("/me")
    public UserMeResponse changeLanguage(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody LanguageUpdateRequest request) {
        return userService.changeLanguage(userId, request.preferredLanguage());
    }
}
