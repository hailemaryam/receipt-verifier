package org.hmmk.verifier.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of OCR analysis on a receipt screenshot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrResult {
    private String bankType; // "TELEBIRR" or "CBE"
    private String reference; // extracted transaction reference
    private boolean success;
    private String error;

    public static OcrResult failure(String error) {
        return OcrResult.builder().success(false).error(error).build();
    }

    public static OcrResult of(String bankType, String reference) {
        return OcrResult.builder().success(true).bankType(bankType).reference(reference).build();
    }
}
