package org.hmmk.verifier.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hmmk.verifier.dto.DashenVerifyResult;
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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for verifying Dashen Bank payment receipts.
 * Fetches PDF receipts from Dashen's endpoint and parses the data.
 */
@ApplicationScoped
public class DashenVerificationService {

    private static final Logger LOG = Logger.getLogger(DashenVerificationService.class);

    @ConfigProperty(name = "verifier.dashen.url", defaultValue = "https://receipt.dashensuperapp.com/receipt/")
    String dashenUrl;

    @ConfigProperty(name = "verifier.dashen.timeout", defaultValue = "30000")
    int timeout;

    private final HttpClient httpClient;

    public DashenVerificationService() {
        this.httpClient = createTrustAllHttpClient();
    }

    private HttpClient createTrustAllHttpClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
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
            LOG.error("Failed to create trust-all HTTP client for Dashen", e);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
    }

    public DashenVerifyResult verifyDashen(String reference) {
        String url = dashenUrl + reference;

        try {
            LOG.infof("🔎 Attempting Dashen verification: %s", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeout))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "application/pdf")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                return DashenVerifyResult.failure("HTTP error: " + response.statusCode());
            }

            return parseDashenReceipt(response.body());

        } catch (IOException | InterruptedException e) {
            LOG.errorf("❌ Error fetching Dashen receipt: %s", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return DashenVerifyResult.failure("Error fetching receipt: " + e.getMessage());
        }
    }

    private DashenVerifyResult parseDashenReceipt(byte[] pdfData) {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String rawText = stripper.getText(document);
            String normalizedText = rawText.replaceAll("\\s+", " ").trim();

            LOG.debugf("Dashen PDF text extracted: %d characters", normalizedText.length());

            String senderName = extractPattern(normalizedText,
                    "Sender\\s*Name\\s*:?\\s*(.*?)\\s+(?:Sender\\s*Account|Account)");
            String senderAccount = extractPattern(normalizedText,
                    "Sender\\s*Account\\s*(?:Number)?\s*:?\\s*([A-Z0-9\\*\\-]+)");
            String channel = extractPattern(normalizedText,
                    "Transaction\\s*Channel\\s*:?\\s*(.*?)\\s+(?:Service|Type)");
            String serviceType = extractPattern(normalizedText,
                    "Service\\s*Type\\s*:?\\s*(.*?)\\s+(?:Narrative|Description)");
            String narrative = extractPattern(normalizedText, "Narrative\\s*:?\\s*(.*?)\\s+(?:Receiver|Phone)");
            String receiverName = extractPattern(normalizedText,
                    "Receiver\\s*Name\s*:?\\s*(.*?)\\s+(?:Phone|Institution)");
            String phoneNo = extractPattern(normalizedText, "Phone\\s*(?:No\\.?|Number)?\\s*:?\\s*([\\+\\d\\-\\s]+)");
            String instName = extractPattern(normalizedText,
                    "Institution\\s*Name\\s*:?\\s*(.*?)\\s+(?:Transaction|Reference)");
            String transRef = extractPattern(normalizedText, "Transaction\\s*Reference\\s*:?\\s*([A-Z0-9\\-]+)");
            String transferRef = extractPattern(normalizedText, "Transfer\\s*Reference\\s*:?\\s*([A-Z0-9\\-]+)");

            String dateRaw = extractPattern(normalizedText,
                    "Transaction\\s*Date\\s*(?:&\\s*Time)?\\s*:?\\s*([\\d/\\-,: ]+(?:[APM]{2})?)");
            LocalDateTime date = parseDate(dateRaw);

            BigDecimal amount = extractAmount(normalizedText,
                    "Transaction\\s*Amount\\s*(?:ETB|Birr)?\\s*([\\d,]+\\.?\\d*)");
            BigDecimal total = extractAmount(normalizedText, "Total\\s*(?:ETB|Birr)?\\s*([\\d,]+\\.?\\d*)");

            if (transRef == null || amount == null) {
                return DashenVerifyResult.failure("Could not extract required fields (Reference and Amount) from PDF.");
            }

            return DashenVerifyResult.builder()
                    .success(true)
                    .senderName(titleCase(senderName))
                    .senderAccountNumber(senderAccount)
                    .transactionChannel(channel)
                    .serviceType(serviceType)
                    .narrative(narrative)
                    .receiverName(titleCase(receiverName))
                    .phoneNo(phoneNo)
                    .institutionName(titleCase(instName))
                    .transactionReference(transRef)
                    .transferReference(transferRef)
                    .transactionDate(date)
                    .transactionAmount(amount)
                    .total(total)
                    .build();

        } catch (IOException e) {
            LOG.errorf("❌ Dashen PDF parsing failed: %s", e.getMessage());
            return DashenVerifyResult.failure("Error parsing PDF data");
        }
    }

    private String extractPattern(String text, String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private BigDecimal extractAmount(String text, String regex) {
        String amountStr = extractPattern(text, regex);
        if (amountStr != null) {
            try {
                return new BigDecimal(amountStr.replace(",", ""));
            } catch (NumberFormatException e) {
                LOG.warnf("Failed to parse Dashen amount: %s", amountStr);
            }
        }
        return null;
    }

    private LocalDateTime parseDate(String dateRaw) {
        if (dateRaw == null)
            return null;
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
            }
        }
        return null;
    }

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
