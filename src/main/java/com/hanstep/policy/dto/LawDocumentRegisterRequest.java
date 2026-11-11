package com.hanstep.policy.dto;

import com.hanstep.policy.domain.PolicySourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * AI-R6 본문. sourceType 은 지금 LAW 만 받는다. externalId 는 법제처 법령ID·MST(policy_version 에 들어가서 영숫자 20자 이내).
 */
public record LawDocumentRegisterRequest(
        @NotNull PolicySourceType sourceType,
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 100) String issuingAgency,
        @NotBlank @Size(max = 1000) @Pattern(regexp = "^https?://.+") String sourceUrl,
        @NotNull @Pattern(regexp = "^[0-9A-Za-z]{1,20}$") String externalId,
        @Size(max = 20) String visaTypeCode,
        LocalDate publishedOn,
        LocalDate effectiveFrom,
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String contentHash,
        @Size(max = 512) String objectKey
) {
}
