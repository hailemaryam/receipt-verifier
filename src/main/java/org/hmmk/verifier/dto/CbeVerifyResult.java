package org.hmmk.verifier.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Data Transfer Object representing a CBE (Commercial Bank of Ethiopia)
 * verification result.
 * Contains all fields extracted from the CBE receipt PDF.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CbeVerifyResult {
    private boolean success;
    private String payer;
    private String payerAccount;
    private String receiver;
    private String receiverAccount;
    private BigDecimal amount;
    private LocalDateTime date;
    private String reference;
    private String reason;
    private String error;

    /**
     * Creates a successful verification result.
     */
    public static CbeVerifyResult success(String payer, String payerAccount, String receiver,
            String receiverAccount, BigDecimal amount,
            LocalDateTime date, String reference, String reason) {
        return CbeVerifyResult.builder()
                .success(true)
                .payer(payer)
                .payerAccount(payerAccount)
                .receiver(receiver)
                .receiverAccount(receiverAccount)
                .amount(amount)
                .date(date)
                .reference(reference)
                .reason(reason)
                .build();
    }

    /**
     * Creates a failed verification result with an error message.
     */
    public static CbeVerifyResult failure(String error) {
        return CbeVerifyResult.builder()
                .success(false)
                .error(error)
                .build();
    }
}
