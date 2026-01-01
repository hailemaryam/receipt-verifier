package org.hmmk.verifier.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Data Transfer Object representing a Dashen bank verification result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashenVerifyResult {
    private boolean success;
    private String senderName;
    private String senderAccountNumber;
    private String transactionChannel;
    private String serviceType;
    private String narrative;
    private String receiverName;
    private String phoneNo;
    private String institutionName;
    private String transactionReference;
    private String transferReference;
    private LocalDateTime transactionDate;
    private BigDecimal transactionAmount;
    private BigDecimal total;
    private String error;

    public static DashenVerifyResult failure(String error) {
        return DashenVerifyResult.builder()
                .success(false)
                .error(error)
                .build();
    }
}
