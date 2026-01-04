package org.hmmk.verifier.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request body for the external callback system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalCallbackRequest {
    private String senderId;
    private String reference;
    private String bankType;
    private BigDecimal amount;
    private String merchantReferenceId;
}
