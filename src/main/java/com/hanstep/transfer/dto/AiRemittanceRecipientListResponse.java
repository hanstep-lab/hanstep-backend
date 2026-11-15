package com.hanstep.transfer.dto;

import com.hanstep.transfer.service.RemittanceRecipient;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "챗봇용 저장된 받는 분 목록(관계 확인한 행만). 계좌번호는 끝 4자리만")
public record AiRemittanceRecipientListResponse(
        @ArraySchema(schema = @Schema(implementation = Recipient.class),
                arraySchema = @Schema(description = "없으면 빈 배열"))
        List<Recipient> recipients
) {

    @Schema(name = "AiRemittanceRecipient")
    public record Recipient(
            @Schema(example = "1")
            Long recipientId,

            @Schema(example = "LORNA REYES")
            String nameEn,

            @Schema(description = "remittance_recipients.relationship 값 그대로", example = "MOTHER")
            String relationship,

            @Schema(description = "ISO 3166-1 alpha-2", example = "PH")
            String countryCode,

            @Schema(description = "받는 통화(ISO 4217)", example = "PHP")
            String currency,

            @Schema(example = "BDO")
            String bankName,

            @Schema(description = "계좌번호 끝 4자리", example = "5566")
            String accountLast4
    ) {

        public static Recipient from(RemittanceRecipient r) {
            return new Recipient(r.recipientId(), r.nameEn(), r.relationship(), r.countryCode(), r.currency(),
                    r.bankName(), r.accountLast4());
        }
    }

    public static AiRemittanceRecipientListResponse from(List<RemittanceRecipient> recipients) {
        return new AiRemittanceRecipientListResponse(recipients.stream().map(Recipient::from).toList());
    }
}
