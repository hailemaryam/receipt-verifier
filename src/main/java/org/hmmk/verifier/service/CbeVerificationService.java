package org.hmmk.verifier.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hmmk.verifier.dto.CbeVerifyResult;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for verifying CBE (Commercial Bank of Ethiopia) payment receipts.
 * Fetches PDF receipts from CBE's endpoint and parses the data.
 */
@ApplicationScoped
public class CbeVerificationService {

    private static final Logger LOG = Logger.getLogger(CbeVerificationService.class);

    @ConfigProperty(name = "verifier.cbe.url", defaultValue = "https://apps.cbe.com.et:100/")
    String cbeUrl;

    @ConfigProperty(name = "verifier.cbe.timeout", defaultValue = "30000")
    int timeout;

    private final HttpClient httpClient;

    public CbeVerificationService() {
        this.httpClient = createTrustAllHttpClient();
    }

    /**
     * Creates an HttpClient that trusts all SSL certificates.
     * Required because CBE uses a self-signed certificate.
     */
    private HttpClient createTrustAllHttpClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                            // Trust all
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                            // Trust all
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            LOG.error("Failed to create trust-all HTTP client, falling back to default", e);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
    }

    /**
     * Verifies a CBE receipt by reference number and account suffix.
     *
     * @param reference     The CBE transaction reference number
     * @param accountSuffix The last 4 digits of the account number
     * @return The verification result
     */
    public CbeVerifyResult verifyCBE(String reference, String accountSuffix) {
        String fullId = reference + accountSuffix;
        String url = cbeUrl + "?id=" + fullId;

        try {
            LOG.infof("🔎 Attempting direct fetch: %s", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeout))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "application/pdf")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                return CbeVerifyResult.failure("HTTP error: " + response.statusCode());
            }

            // Check if response is actually a PDF
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.contains("pdf") && response.body().length < 100) {
                return CbeVerifyResult.failure("Response is not a PDF");
            }

            LOG.info("✅ Direct fetch success, parsing PDF");
            return parseCBEReceipt(response.body());

        } catch (IOException | InterruptedException e) {
            LOG.errorf("❌ Error fetching CBE receipt: %s", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CbeVerifyResult.failure("Error fetching receipt: " + e.getMessage());
        }
    }

    /**
     * Parses a CBE receipt PDF and extracts transaction data.
     */
    private CbeVerifyResult parseCBEReceipt(byte[] pdfData) {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String rawText = stripper.getText(document);
            String normalizedText = rawText.replaceAll("\\s+", " ").trim();

            LOG.debugf("PDF text extracted: %d characters", normalizedText.length());

            // Extract payer name
            String payerName = extractPattern(normalizedText, "Payer\\s*:?\\s*(.*?)\\s+Account");
            // Extract receiver name
            String receiverName = extractPattern(normalizedText, "Receiver\\s*:?\\s*(.*?)\\s+Account");

            // Extract account numbers (format: X****1234)
            List<String> accounts = extractAllPatterns(normalizedText, "Account\\s*:?\\s*([A-Z0-9]?\\*{4}\\d{4})");
            String payerAccount = accounts.size() > 0 ? accounts.get(0) : null;
            String receiverAccount = accounts.size() > 1 ? accounts.get(1) : null;

            // Extract reason/type of service
            String reason = extractPattern(normalizedText,
                    "Reason\\s*/\\s*Type of service\\s*:?\\s*(.*?)\\s+Transferred Amount");

            // Extract amount
            String amountText = extractPattern(normalizedText, "Transferred Amount\\s*:?\\s*([\\d,]+\\.\\d{2})\\s*ETB");
            BigDecimal amount = null;
            if (amountText != null) {
                try {
                    amount = new BigDecimal(amountText.replace(",", ""));
                } catch (NumberFormatException e) {
                    LOG.warnf("Failed to parse amount: %s", amountText);
                }
            }

            // Extract reference number
            String referenceNo = extractPattern(normalizedText,
                    "Reference No\\.?\\s*\\(VAT Invoice No\\)\\s*:?\\s*([A-Z0-9]+)");

            // Extract date
            String dateRaw = extractPattern(normalizedText, "Payment Date & Time\\s*:?\\s*([\\d/,: ]+[APM]{2})");
            LocalDateTime date = parseDate(dateRaw);

            // Apply title case to names
            payerName = titleCase(payerName);
            receiverName = titleCase(receiverName);

            // Validate required fields
            if (payerName != null && payerAccount != null && receiverName != null &&
                    receiverAccount != null && amount != null && date != null && referenceNo != null) {
                return CbeVerifyResult.success(
                        payerName, payerAccount, receiverName, receiverAccount,
                        amount, date, referenceNo, reason);
            } else {
                return CbeVerifyResult.failure("Could not extract all required fields from PDF.");
            }

        } catch (IOException e) {
            LOG.errorf("❌ PDF parsing failed: %s", e.getMessage());
            return CbeVerifyResult.failure("Error parsing PDF data");
        }
    }

    /**
     * Extracts a single pattern match from text.
     */
    private String extractPattern(String text, String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Extracts all pattern matches from text.
     */
    private List<String> extractAllPatterns(String text, String regex) {
        List<String> results = new ArrayList<>();
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            results.add(matcher.group(1).trim());
        }
        return results;
    }

    /**
     * Parses a date string in various formats.
     */
    private LocalDateTime parseDate(String dateRaw) {
        if (dateRaw == null)
            return null;

        // Try different date formats
        String[] formats = {
                "MM/dd/yyyy, h:mm:ss a",
                "MM/dd/yyyy h:mm:ss a",
                "dd/MM/yyyy, h:mm:ss a",
                "dd/MM/yyyy h:mm:ss a",
                "yyyy-MM-dd HH:mm:ss"
        };

        for (String format : formats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format, Locale.ENGLISH);
                return LocalDateTime.parse(dateRaw.trim(), formatter);
            } catch (DateTimeParseException e) {
                // Try next format
            }
        }

        LOG.warnf("Failed to parse date: %s", dateRaw);
        return null;
    }

    /**
     * Converts a string to title case.
     */
    private String titleCase(String str) {
        if (str == null || str.isEmpty())
            return str;

        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;

        for (char c : str.toLowerCase().toCharArray()) {
            if (Character.isWhitespace(c)) {
                capitalizeNext = true;
                result.append(c);
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }
}
