package com.hanstep.auth.controller;

import com.hanstep.auth.dto.EntryRequest;
import com.hanstep.auth.dto.EntryResponse;
import com.hanstep.auth.service.AuthService;
import com.hanstep.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "SOL Global 앱인앱 진입")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "SOL 진입 토큰으로 JWT 발급 (AUTH-01)",
            description = "자체 로그인 없음. SOL 인증이 끝났다고 전제하고 진입 토큰만 교환한다. "
                    + "데모 entryToken: demo-e9-nguyen")
    @SecurityRequirements // 토큰 없이 호출
    @ApiResponse(responseCode = "200", description = "성공")
    @ApiResponse(responseCode = "400", description = "entryToken 누락",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "알 수 없는 토큰 또는 비활성 사용자",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/entry")
    public EntryResponse entry(@Valid @RequestBody EntryRequest request) {
        return authService.enter(request.entryToken());
    }
}
