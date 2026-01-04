package org.hmmk.verifier.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified request for receipt verification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedVerifyRequest {
    private String bankType; // CBE, Telebirr, Abyssinia, Dashen
    private String reference;
    private String suffix;
    private String senderId;
    private String merchantReferenceId;
}
