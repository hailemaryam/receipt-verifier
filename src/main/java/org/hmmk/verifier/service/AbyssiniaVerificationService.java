package org.hmmk.verifier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hmmk.verifier.dto.AbyssiniaVerifyResult;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Service for verifying Bank of Abyssinia (BoA) payment receipts.
 * Fetches JSON data from Abyssinia's API and parses the data.
 */
@ApplicationScoped
public class AbyssiniaVerificationService {

    private static final Logger LOG = Logger.getLogger(AbyssiniaVerificationService.class);

    @ConfigProperty(name = "verifier.abyssinia.url", defaultValue = "https://cs.bankofabyssinia.com/api/onlineSlip/getDetails/?id=")
    String abyssiniaUrl;

    @ConfigProperty(name = "verifier.abyssinia.timeout", defaultValue = "30000")
    int timeout;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Inject
    public AbyssiniaVerificationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // Default constructor for Quarkus
    public AbyssiniaVerificationService() {
        this(new ObjectMapper());
    }

    /**
     * Verifies an Abyssinia receipt by reference number and account suffix.
     *
     * @param reference The Abyssinia transaction reference number
     * @param suffix    The last 5 digits of the source account
     * @return The verification result
     */
    public AbyssiniaVerifyResult verifyAbyssinia(String reference, String suffix) {
        String fullId = reference + suffix;
        String url = abyssiniaUrl + fullId;

        int maxAttempts = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LOG.infof("🏦 Attempt %d/%d Abyssinia verification: %s", attempt, maxAttempts, url);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(timeout))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return parseAbyssiniaResponse(response.body());
                }

                LOG.errorf("❌ Attempt %d/%d Abyssinia API returned HTTP error: %d", attempt, maxAttempts,
                        response.statusCode());
                if (attempt == maxAttempts) {
                    return AbyssiniaVerifyResult
                            .failure("HTTP error: " + response.statusCode() + " after " + maxAttempts + " attempts");
                }

            } catch (IOException | InterruptedException e) {
                LOG.errorf("❌ Attempt %d/%d Error fetching Abyssinia receipt: %s", attempt, maxAttempts,
                        e.getMessage());
                lastException = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (attempt < maxAttempts) {
                try {
                    // Short delay between retries
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String errorMsg = lastException != null ? lastException.getMessage() : "Unknown error";
        return AbyssiniaVerifyResult.failure("Error fetching receipt after " + maxAttempts + " attempts: " + errorMsg);
    }

    private AbyssiniaVerifyResult parseAbyssiniaResponse(String jsonString) {
        try {
            JsonNode root = objectMapper.readTree(jsonString);

            // Check header status
            JsonNode header = root.path("header");
            if (!header.path("status").asText().equals("success")) {
                LOG.warnf("⚠️ Abyssinia API returned non-success status: %s", header.path("status").asText());
                return AbyssiniaVerifyResult.failure("API status: " + header.path("status").asText());
            }

            // Get body array
            JsonNode body = root.path("body");
            if (!body.isArray() || body.isEmpty()) {
                LOG.warn("⚠️ Abyssinia API returned empty body");
                return AbyssiniaVerifyResult.failure("No transaction data found");
            }

            JsonNode transaction = body.get(0);

            String payerName = transaction.path("Payer's Name").asText("");
            String sourceAccount = transaction.path("Source Account").asText("");
            String sourceAccountName = transaction.path("Source Account Name").asText("");
            String receiverAccount = transaction.path("Receiver's Account").asText("");
            String receiverName = transaction.path("Receiver's Name").asText("");
            String amountStr = transaction.path("Transferred Amount").asText("");
            String dateStr = transaction.path("Transaction Date").asText("");
            String reference = transaction.path("Transaction Reference").asText("");
            String narrative = transaction.path("Narrative").asText("");

            BigDecimal amount = null;
            if (!amountStr.isBlank()) {
                try {
                    // Remove currency if present (though Node code just replaces non-digits)
                    String cleanAmount = amountStr.replaceAll("[^\\d.]", "");
                    amount = new BigDecimal(cleanAmount);
                } catch (Exception e) {
                    LOG.warnf("Failed to parse amount: %s", amountStr);
                }
            }

            LocalDateTime date = null;
            if (!dateStr.isBlank()) {
                try {
                    // Handle format like "2023-06-26 14:30:00" or similar
                    // In Node: new Date(transactionDateStr)
                    // We'll try a common ISO-like format or fall back
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
                    date = LocalDateTime.parse(dateStr, formatter);
                } catch (Exception e) {
                    LOG.warnf("Failed to parse date: %s", dateStr);
                }
            }

            if (reference.isBlank() || amount == null) {
                return AbyssiniaVerifyResult.failure("Missing essential fields in transaction data");
            }

            return AbyssiniaVerifyResult.success(
                    payerName, sourceAccount, sourceAccountName, receiverAccount, receiverName,amount, date, reference, narrative);

        } catch (IOException e) {
            LOG.errorf("❌ Failed to parse Abyssinia JSON: %s", e.getMessage());
            return AbyssiniaVerifyResult.failure("Error parsing JSON data");
        }
    }
}
