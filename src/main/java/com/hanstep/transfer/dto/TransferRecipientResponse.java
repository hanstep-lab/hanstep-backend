package com.hanstep.transfer.dto;

import com.hanstep.transfer.service.RemittanceRecipient;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "송금 화면의 받는 분(remittance_recipients). 계좌번호는 끝 4자리만")
public record TransferRecipientResponse(
        @Schema(description = "받는 분 ID", example = "1")
        Long recipientId,

        @Schema(description = "받는 분 영문 이름", example = "LORNA REYES")
        String name,

        @Schema(description = "관계 표시 문구(임시 매핑). preferred_language 가 en 이면 영어(vi·zh 도 영어)(예: Mother)", example = "어머니", nullable = true)
        String relation,

        @Schema(description = "받는 은행", example = "BDO")
        String bank,

        @Schema(description = "계좌번호 끝 4자리", example = "5566")
        String accountLast4,

        @Schema(description = "받는 나라 표시 이름(임시 매핑, 모르는 코드는 코드 그대로)", example = "필리핀")
        String country,

        @Schema(description = "받는 나라(ISO 3166-1 alpha-2)", example = "PH")
        String countryCode,

        @Schema(description = "받는 통화(ISO 4217)", example = "PHP")
        String currency
) {

    // 임시값: 화면 문구가 정해지면 바꾼다
    private static final Map<String, String> RELATION_LABELS = Map.of(
            "MOTHER", "어머니", "FATHER", "아버지", "SPOUSE", "배우자", "CHILD", "자녀",
            "SIBLING", "형제자매", "SELF", "본인 계좌", "OTHER", "기타");
    private static final Map<String, String> RELATION_LABELS_EN = Map.of(
            "MOTHER", "Mother", "FATHER", "Father", "SPOUSE", "Spouse", "CHILD", "Child",
            "SIBLING", "Sibling", "SELF", "Own account", "OTHER", "Other");
    private static final Map<String, String> COUNTRY_LABELS = Map.of("PH", "필리핀", "VN", "베트남");

    /** 한국어 관계 문구. */
    public static TransferRecipientResponse from(RemittanceRecipient r) {
        return from(r, false);
    }

    /** english 면 관계 문구만 영어. 나라 표시 이름은 언어와 상관없이 같다. */
    public static TransferRecipientResponse from(RemittanceRecipient r, boolean english) {
        String relation = r.relationship() == null ? null
                : (english ? RELATION_LABELS_EN : RELATION_LABELS).get(r.relationship());
        return new TransferRecipientResponse(r.recipientId(), r.nameEn(), relation, r.bankName(), r.accountLast4(),
                COUNTRY_LABELS.getOrDefault(r.countryCode(), r.countryCode()), r.countryCode(), r.currency());
    }
}
