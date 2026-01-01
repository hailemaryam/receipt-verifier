package org.hmmk.verifier.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hmmk.verifier.dto.TelebirrReceipt;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for verifying Telebirr payment receipts.
 * Fetches receipt HTML from Telebirr's transaction info page and parses the
 * data.
 */
@ApplicationScoped
public class TelebirrVerificationService {

    private static final Logger LOG = Logger.getLogger(TelebirrVerificationService.class);

    @ConfigProperty(name = "verifier.telebirr.primary-url", defaultValue = "https://transactioninfo.ethiotelecom.et/receipt/")
    String primaryUrl;

    @ConfigProperty(name = "verifier.telebirr.fallback-url", defaultValue = "https://leul.et/verify.php?reference=")
    String fallbackUrl;

    @ConfigProperty(name = "verifier.telebirr.skip-primary", defaultValue = "false")
    boolean skipPrimary;

    @ConfigProperty(name = "verifier.telebirr.timeout", defaultValue = "1500000")
    int timeout;

    private final HttpClient httpClient;

    public TelebirrVerificationService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Verifies a Telebirr receipt by reference number.
     *
     * @param reference The Telebirr transaction reference number
     * @return Optional containing the receipt data if found and valid, empty
     *         otherwise
     */
    public Optional<TelebirrReceipt> verifyTelebirr(String reference) {
        if (!skipPrimary) {
            Optional<TelebirrReceipt> primaryResult = fetchFromPrimarySource(reference);
            if (primaryResult.isPresent() && primaryResult.get().isValid()) {
                return primaryResult;
            }
            LOG.warnf("Primary Telebirr verification failed for reference: %s. Trying fallback proxy...", reference);
        } else {
            LOG.info("Skipping primary verifier due to verifier.telebirr.skip-primary=true");
        }

        Optional<TelebirrReceipt> fallbackResult = fetchFromProxySource(reference);
        if (fallbackResult.isPresent() && fallbackResult.get().isValid()) {
            LOG.infof("Successfully verified Telebirr receipt using fallback proxy for reference: %s", reference);
            return fallbackResult;
        }

        LOG.errorf("Both primary and fallback Telebirr verification failed for reference: %s", reference);
        return Optional.empty();
    }

    /**
     * Fetches receipt from the primary Telebirr source.
     */
    private Optional<TelebirrReceipt> fetchFromPrimarySource(String reference) {
        String url = primaryUrl + reference;

        try {
            LOG.infof("Attempting to fetch Telebirr receipt from primary source: %s", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeout))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debugf("Received response with status: %d", response.statusCode());

            if (response.statusCode() != 200) {
                LOG.warnf("Primary source returned non-200 status: %d", response.statusCode());
                return Optional.empty();
            }

            TelebirrReceipt receipt = scrapeTelebirrReceipt(response.body());
            LOG.infof("Successfully extracted Telebirr data for reference: %s, receiptNo: %s",
                    reference, receipt.getReceiptNo());
            return Optional.of(receipt);

        } catch (IOException | InterruptedException e) {
            LOG.errorf("Error fetching Telebirr receipt from primary source %s: %s", url, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    /**
     * Fetches receipt from the fallback proxy source.
     */
    private Optional<TelebirrReceipt> fetchFromProxySource(String reference) {
        String url = fallbackUrl + reference;

        try {
            LOG.infof("Attempting to fetch Telebirr receipt from proxy: %s", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeout))
                    .header("Accept", "application/json")
                    .header("User-Agent", "VerifierAPI/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debugf("Received proxy response with status: %d", response.statusCode());

            if (response.statusCode() != 200) {
                LOG.warnf("Proxy source returned non-200 status: %d", response.statusCode());
                return Optional.empty();
            }

            // The proxy might return JSON or HTML, try to scrape as HTML
            TelebirrReceipt receipt = scrapeTelebirrReceipt(response.body());
            LOG.infof("Successfully extracted Telebirr data from proxy for reference: %s", reference);
            return Optional.of(receipt);

        } catch (IOException | InterruptedException e) {
            LOG.errorf("Error fetching Telebirr receipt from proxy %s: %s", url, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    /**
     * Scrapes Telebirr receipt data from HTML content using Jsoup and regex.
     */
    private TelebirrReceipt scrapeTelebirrReceipt(String html) {
        Document doc = Jsoup.parse(html);

        LOG.debugf("HTML content length: %d bytes", html.length());
        if (html.length() < 100) {
            LOG.warnf("Suspiciously short HTML response: %s", html);
        }

        String creditedPartyName = getTextWithFallback(doc, html, "የገንዘብ ተቀባይ ስም/Credited Party name");
        String creditedPartyAccountNo = getTextWithFallback(doc, html, "የገንዘብ ተቀባይ ቴሌብር ቁ./Credited party account no");
        String bankName = "";

        // Handle bank account number case
        String bankAccountNumberRaw = getTextWithFallback(doc, html, "የባንክ አካውንት ቁጥር/Bank account number");
        if (bankAccountNumberRaw != null && !bankAccountNumberRaw.isEmpty()) {
            bankName = creditedPartyName; // The original credited party name is the bank
            Pattern bankAccountPattern = Pattern.compile("(\\d+)\\s+(.*)");
            Matcher matcher = bankAccountPattern.matcher(bankAccountNumberRaw);
            if (matcher.find()) {
                creditedPartyAccountNo = matcher.group(1).trim();
                creditedPartyName = matcher.group(2).trim();
            }
        }

        return TelebirrReceipt.builder()
                .payerName(getTextWithFallback(doc, html, "የከፋይ ስም/Payer Name"))
                .payerTelebirrNo(getTextWithFallback(doc, html, "የከፋይ ቴሌብር ቁ./Payer telebirr no."))
                .creditedPartyName(creditedPartyName)
                .creditedPartyAccountNo(creditedPartyAccountNo)
                .transactionStatus(getTextWithFallback(doc, html, "የክፍያው ሁኔታ/transaction status"))
                .receiptNo(extractReceiptNo(html, doc))
                .paymentDate(extractPaymentDate(html, doc))
                .settledAmount(extractSettledAmount(html, doc))
                .serviceFee(extractServiceFee(html, doc))
                .serviceFeeVAT(getTextWithFallback(doc, html, "የአገልግሎት ክፍያ ተ.እ.ታ/Service fee VAT"))
                .totalPaidAmount(getTextWithFallback(doc, html, "ጠቅላላ የተከፈለ/Total Paid Amount"))
                .bankName(bankName)
                .build();
    }

    /**
     * Extracts text using Jsoup with regex fallback.
     */
    private String getTextWithFallback(Document doc, String html, String labelText) {
        // Try regex first
        String regexResult = extractWithRegex(html, labelText);
        if (regexResult != null && !regexResult.isEmpty()) {
            return regexResult;
        }

        // Fallback to Jsoup
        Element labelElement = doc.selectFirst("td:contains(" + labelText + ")");
        if (labelElement != null) {
            Element nextElement = labelElement.nextElementSibling();
            if (nextElement != null) {
                return nextElement.text().trim();
            }
        }
        return "";
    }

    /**
     * Generic regex extractor for fields.
     */
    private String extractWithRegex(String html, String labelPattern) {
        String escapedLabel = Pattern.quote(labelPattern);
        Pattern pattern = Pattern.compile(escapedLabel + ".*?</td>\\s*<td[^>]*>\\s*([^<]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1).replaceAll("<[^>]*>", "").trim();
        }
        return null;
    }

    /**
     * Extracts settled amount using multiple regex patterns.
     */
    private String extractSettledAmount(String html, Document doc) {
        // Pattern 1: Direct match with the exact text structure
        Pattern pattern1 = Pattern.compile(
                "የተከፈለው\\s+መጠን/Settled\\s+Amount.*?</td>\\s*<td[^>]*>\\s*(\\d+(?:\\.\\d{2})?\\s+Birr)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern1.matcher(html);
        if (matcher.find())
            return matcher.group(1).trim();

        // Pattern 2: Look for the table row structure
        Pattern pattern2 = Pattern.compile(
                "<tr[^>]*>.*?የተከፈለው\\s+መጠን/Settled\\s+Amount.*?<td[^>]*>\\s*(\\d+(?:\\.\\d{2})?\\s+Birr)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        matcher = pattern2.matcher(html);
        if (matcher.find())
            return matcher.group(1).trim();

        // Pattern 3: More flexible approach
        Pattern pattern3 = Pattern.compile("Settled\\s+Amount.*?(\\d+(?:\\.\\d{2})?\\s+Birr)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        matcher = pattern3.matcher(html);
        if (matcher.find())
            return matcher.group(1).trim();

        // Fallback to Jsoup
        return doc.select("td.receipttableTd.receipttableTd2")
                .stream()
                .filter(el -> {
                    Element prev = el.previousElementSibling();
                    return prev != null
                            && (prev.text().contains("የተከፈለው መጠን") || prev.text().contains("Settled Amount"));
                })
                .findFirst()
                .map(Element::text)
                .orElse("");
    }

    /**
     * Extracts service fee using regex patterns.
     */
    private String extractServiceFee(String html, Document doc) {
        // Pattern to match service fee but not VAT version
        Pattern pattern = Pattern.compile(
                "የአገልግሎት\\s+ክፍያ/Service\\s+fee(?!\\s+ተ\\.እ\\.ታ).*?</td>\\s*<td[^>]*>\\s*(\\d+(?:\\.\\d{2})?\\s+Birr)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find())
            return matcher.group(1).trim();

        // Fallback to Jsoup
        return doc.select("td.receipttableTd1")
                .stream()
                .filter(el -> {
                    String text = el.text();
                    return (text.contains("የአገልግሎት ክፍያ") || text.contains("Service fee"))
                            && !text.contains("ተ.እ.ታ") && !text.contains("VAT");
                })
                .findFirst()
                .map(el -> {
                    Element next = el.nextElementSibling();
                    return next != null ? next.text().trim() : "";
                })
                .orElse("");
    }

    /**
     * Extracts receipt number using regex patterns.
     */
    private String extractReceiptNo(String html, Document doc) {
        // Try regex first
        Pattern pattern = Pattern.compile(
                "<td[^>]*class=\"[^\"]*receipttableTd[^\"]*receipttableTd2[^\"]*\"[^>]*>\\s*([A-Z0-9]+)\\s*</td>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find())
            return matcher.group(1).trim();

        // Fallback to Jsoup
        var cells = doc.select("td.receipttableTd.receipttableTd2");
        if (cells.size() > 1) {
            return cells.get(1).text().trim();
        }
        return "";
    }

    /**
     * Extracts payment date using regex patterns.
     */
    private String extractPaymentDate(String html, Document doc) {
        // Try regex first - format DD-MM-YYYY HH:MM:SS
        Pattern pattern = Pattern.compile("(\\d{2}-\\d{2}-\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})");
        Matcher matcher = pattern.matcher(html);
        if (matcher.find())
            return matcher.group(1).trim();

        // Fallback to Jsoup
        return doc.select(".receipttableTd")
                .stream()
                .filter(el -> el.text().contains("-202"))
                .findFirst()
                .map(Element::text)
                .orElse("");
    }
}
