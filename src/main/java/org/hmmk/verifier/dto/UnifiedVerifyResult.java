package org.hmmk.verifier.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a unified verification process.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedVerifyResult {
    private boolean success;
    private String message;

    public static UnifiedVerifyResult success(String message) {
        return UnifiedVerifyResult.builder()
                .success(true)
                .message(message)
                .build();
    }

    public static UnifiedVerifyResult failure(String message) {
        return UnifiedVerifyResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}
