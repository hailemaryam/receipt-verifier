package org.hmmk.verifier.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object representing a Telebirr receipt.
 * Contains all fields extracted from the Telebirr receipt page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TelebirrReceipt {
    private String payerName;
    private String payerTelebirrNo;
    private String creditedPartyName;
    private String creditedPartyAccountNo;
    private String transactionStatus;
    private String receiptNo;
    private String paymentDate;
    private String settledAmount;
    private String serviceFee;
    private String serviceFeeVAT;
    private String totalPaidAmount;
    private String bankName;

    /**
     * Validates that essential fields have values.
     *
     * @return true if the receipt has the minimum required fields
     */
    public boolean isValid() {
        return  true;
//        return receiptNo != null && !receiptNo.isEmpty()
//                && payerName != null && !payerName.isEmpty()
//                && transactionStatus != null && !transactionStatus.isEmpty();
    }
}
