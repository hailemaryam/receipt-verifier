package org.hmmk.verifier.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Data Transfer Object representing an Abyssinia bank verification result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AbyssiniaVerifyResult {
    private boolean success;
    private String payer;
    private String payerAccount;
    private String sourceAccountName;
    private BigDecimal amount;
    private LocalDateTime date;
    private String reference;
    private String narrative;
    private String error;

    public static AbyssiniaVerifyResult success(String payer, String payerAccount, String sourceAccountName,
            BigDecimal amount, LocalDateTime date, String reference, String narrative) {
        return AbyssiniaVerifyResult.builder()
                .success(true)
                .payer(payer)
                .payerAccount(payerAccount)
                .sourceAccountName(sourceAccountName)
                .amount(amount)
                .date(date)
                .reference(reference)
                .narrative(narrative)
                .build();
    }

    public static AbyssiniaVerifyResult failure(String error) {
        return AbyssiniaVerifyResult.builder()
                .success(false)
                .error(error)
                .build();
    }
}
