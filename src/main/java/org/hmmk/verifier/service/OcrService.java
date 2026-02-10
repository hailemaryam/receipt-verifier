package org.hmmk.verifier.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hmmk.verifier.dto.OcrResult;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Service that uses Mistral AI's pixtral vision model to analyze receipt
 * screenshots,
 * detect the bank type (Telebirr or CBE), and extract the transaction reference
 * number.
 */
@ApplicationScoped
public class OcrService {

    private static final Logger LOG = Logger.getLogger(OcrService.class);

    @ConfigProperty(name = "verifier.ocr.mistral.api-key", defaultValue = "")
    String apiKey;

    @ConfigProperty(name = "verifier.ocr.mistral.model", defaultValue = "pixtral-12b-2409")
    String model;

    @ConfigProperty(name = "verifier.ocr.mistral.url", defaultValue = "https://api.mistral.ai/v1/chat/completions")
    String apiUrl;

    private final HttpClient httpClient;

    public OcrService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Analyzes a receipt screenshot and extracts bank type + reference number.
     *
     * @param imageBytes raw image bytes (JPEG/PNG)
     * @param mimeType   the MIME type of the image (e.g. "image/jpeg", "image/png")
     * @return OcrResult with bankType and reference, or an error
     */
    public OcrResult analyzeReceipt(byte[] imageBytes, String mimeType) {
        if (apiKey == null || apiKey.isBlank()) {
            LOG.error("Mistral API key is not configured (verifier.ocr.mistral.api-key)");
            return OcrResult.failure("OCR service is not configured");
        }

        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:" + mimeType + ";base64," + base64Image;

            String prompt = buildPrompt();
            String requestBody = buildRequestBody(prompt, dataUri);

            LOG.info("Sending receipt image to Mistral Vision for OCR analysis...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.errorf("Mistral API returned status %d: %s", response.statusCode(), response.body());
                return OcrResult.failure("OCR service returned an error (status " + response.statusCode() + ")");
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            LOG.error("Error during OCR analysis", e);
            return OcrResult.failure("OCR analysis failed: " + e.getMessage());
        }
    }

    private String buildPrompt() {
        return "You are a payment receipt analyzer. Based on the uploaded image, determine:\\n"
                + "- If the receipt was issued by Telebirr or the Commercial Bank of Ethiopia (CBE).\\n"
                + "- If it's a CBE receipt, extract the transaction ID (usually starts with 'FT').\\n"
                + "- If it's a Telebirr receipt, extract the transaction number (usually starts with 'CE').\\n\\n"
                + "Rules:\\n"
                + "- CBE receipts usually include a purple header with the title \\\"Commercial Bank of Ethiopia\\\" and a structured table.\\n"
                + "- Telebirr receipts are typically green with a large minus sign before the amount.\\n"
                + "- CBE receipts may mention Telebirr (as the receiver) but are still CBE receipts.\\n\\n"
                + "Return this JSON format exactly:\\n"
                + "{\\n"
                + "  \\\"type\\\": \\\"telebirr\\\" or \\\"cbe\\\",\\n"
                + "  \\\"reference\\\": \\\"the extracted transaction reference\\\"\\n"
                + "}\\n\\n"
                + "If you cannot determine the type or extract the reference, return:\\n"
                + "{\\n"
                + "  \\\"type\\\": \\\"unknown\\\",\\n"
                + "  \\\"reference\\\": null,\\n"
                + "  \\\"error\\\": \\\"reason why\\\"\\n"
                + "}";
    }

    private String buildRequestBody(String prompt, String imageDataUri) {
        // Build JSON manually to avoid extra dependencies
        return "{"
                + "\"model\":\"" + model + "\","
                + "\"messages\":[{"
                + "\"role\":\"user\","
                + "\"content\":["
                + "{\"type\":\"text\",\"text\":\"" + prompt + "\"},"
                + "{\"type\":\"image_url\",\"image_url\":\"" + imageDataUri + "\"}"
                + "]"
                + "}],"
                + "\"response_format\":{\"type\":\"json_object\"}"
                + "}";
    }

    private OcrResult parseResponse(String responseBody) {
        try {
            // Extract the content field from the Mistral response
            // Response format: {"choices":[{"message":{"content":"..."}}]}
            String content = extractJsonField(responseBody, "content");
            if (content == null) {
                LOG.errorf("Could not extract content from Mistral response: %s", responseBody);
                return OcrResult.failure("Invalid OCR response format");
            }

            // Unescape the content string (it's a JSON string within JSON)
            content = content.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");

            String type = extractJsonField(content, "type");
            String reference = extractJsonField(content, "reference");

            LOG.infof("OCR Result - type: %s, reference: %s", type, reference);

            if (type == null || "unknown".equalsIgnoreCase(type) || reference == null) {
                String error = extractJsonField(content, "error");
                return OcrResult.failure(error != null ? error : "Could not recognize receipt or extract reference");
            }

            String bankType;
            if ("telebirr".equalsIgnoreCase(type)) {
                bankType = "TELEBIRR";
            } else if ("cbe".equalsIgnoreCase(type)) {
                bankType = "CBE";
            } else {
                return OcrResult.failure("Unsupported receipt type: " + type);
            }

            return OcrResult.of(bankType, reference);

        } catch (Exception e) {
            LOG.error("Error parsing OCR response", e);
            return OcrResult.failure("Failed to parse OCR response");
        }
    }

    /**
     * Simple JSON field extractor - looks for "fieldName":"value" patterns.
     * Handles nested JSON by finding the correct field in the response.
     */
    private String extractJsonField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1)
            return null;

        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex == -1)
            return null;

        // Skip whitespace after colon
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length())
            return null;

        char firstChar = json.charAt(valueStart);

        // Handle null value
        if (json.startsWith("null", valueStart)) {
            return null;
        }

        // Handle string value
        if (firstChar == '"') {
            int valueEnd = valueStart + 1;
            while (valueEnd < json.length()) {
                if (json.charAt(valueEnd) == '\\') {
                    valueEnd += 2; // skip escaped character
                } else if (json.charAt(valueEnd) == '"') {
                    break;
                } else {
                    valueEnd++;
                }
            }
            return json.substring(valueStart + 1, valueEnd);
        }

        return null;
    }
}
