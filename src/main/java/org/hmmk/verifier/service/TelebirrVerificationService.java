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

    @ConfigProperty(name = "verifier.telebirr.timeout", defaultValue = "30000")
    int timeout;

    private final HttpClient httpClient;

    public TelebirrVerificationService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
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
        Optional<TelebirrReceipt> result = fetchFromPrimarySource(reference);
        if (result.isPresent()) {
            return result;
        }
        LOG.errorf("Telebirr verification failed for reference: %s", reference);
        return Optional.empty();
    }

    /**
     * Fetches receipt from the primary Telebirr source.
     */
    private Optional<TelebirrReceipt> fetchFromPrimarySource(String reference) {
        String url = primaryUrl + reference;

        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LOG.infof("Attempt %d/%d to fetch Telebirr receipt from primary source: %s", attempt, maxAttempts, url);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(timeout))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                LOG.debugf("Received response with status: %d", response.statusCode());

                if (response.statusCode() == 200) {
                    TelebirrReceipt receipt = scrapeTelebirrReceipt(response.body());
                    LOG.infof("Successfully extracted Telebirr data for reference: %s, receiptNo: %s",
                            reference, receipt.getReceiptNo());
                    return Optional.of(receipt);
                } else {
                    LOG.warnf("Attempt %d/%d: Primary source returned non-200 status: %d", attempt, maxAttempts,
                            response.statusCode());
                }

            } catch (IOException | InterruptedException e) {
                LOG.errorf("Attempt %d/%d: Error fetching Telebirr receipt from primary source %s: %s", attempt,
                        maxAttempts, url, e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOG.errorf("Telebirr verification failed for reference: %s after %d attempts", reference, maxAttempts);
        return Optional.empty();
    }

    /**
     * Scrapes Telebirr receipt data from HTML content using Jsoup.
     * Updated to match Telebirr's receipt HTML structure.
     */
    private TelebirrReceipt scrapeTelebirrReceipt(String html) {
        Document doc = Jsoup.parse(html);

        LOG.debugf("HTML content length: %d bytes", html.length());
        if (html.length() < 100) {
            LOG.warnf("Suspiciously short HTML response: %s", html);
        }

        String creditedPartyName = extractFieldValue(doc, "የገንዘብ ተቀባይ ስም/Credited Party name");
        String creditedPartyAccountNo = extractFieldValue(doc, "የገንዘብ ተቀባይ ቴሌብር ቁ./Credited party account no");
        String bankName = "";

        // Handle bank account number case
        String bankAccountNumberRaw = extractFieldValue(doc, "የባንክ አካውንት ቁጥር/Bank account number");
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
                .payerName(extractFieldValue(doc, "የከፋይ ስም/Payer Name"))
                .payerTelebirrNo(extractFieldValue(doc, "የከፋይ ቴሌብር ቁ./Payer telebirr no."))
                .creditedPartyName(creditedPartyName)
                .creditedPartyAccountNo(creditedPartyAccountNo)
                .transactionStatus(extractTransactionStatus(doc))
                .receiptNo(extractInvoiceDetailValue(doc, 0))
                .paymentDate(extractInvoiceDetailValue(doc, 1))
                .settledAmount(extractInvoiceDetailValue(doc, 2))
                .serviceFee(extractServiceFee(doc))
                .serviceFeeVAT(extractServiceFeeVAT(doc))
                .totalPaidAmount(extractTotalPaidAmount(doc))
                .bankName(bankName)
                .build();
    }

    /**
     * Extracts field value by finding a TD containing the label and getting the
     * next sibling TD's text.
     */
    private String extractFieldValue(Document doc, String label) {
        // Find TDs containing this label text
        for (Element td : doc.select("td")) {
            String text = td.text().trim();
            if (text.contains(label) || text.equals(label)) {
                Element nextTd = td.nextElementSibling();
                if (nextTd != null && "td".equalsIgnoreCase(nextTd.tagName())) {
                    return cleanText(nextTd.text());
                }
            }
        }
        return "";
    }

    /**
     * Extracts transaction status specifically, handling the unclosed TD tag issue
     * in the HTML.
     */
    private String extractTransactionStatus(Document doc) {
        for (Element td : doc.select("td")) {
            if (td.text().contains("የክፍያው ሁኔታ/transaction status")) {
                Element nextTd = td.nextElementSibling();
                if (nextTd != null) {
                    return cleanText(nextTd.text());
                }
            }
        }
        return "";
    }

    /**
     * Extracts values from the invoice details table row.
     * The table has 3 columns: Invoice No, Payment date, Settled Amount
     * index 0 = Invoice No, 1 = Payment date, 2 = Settled Amount
     */
    private String extractInvoiceDetailValue(Document doc, int index) {
        // Find the invoice details data row (row after header row with Invoice
        // No./Payment date/Settled Amount)
        for (Element tr : doc.select("tr")) {
            var tds = tr.select("td.receipttableTd.receipttableTd2, td.receipttableTd");
            if (tds.size() >= 3) {
                // Check if any TD contains the header text - skip header row
                boolean isHeader = tds.stream().anyMatch(td -> td.text().contains("የክፍያ ቁጥር/Invoice No") ||
                        td.text().contains("የክፍያ ቀን/Payment date") ||
                        td.text().contains("የተከፈለው መጠን/Settled Amount"));

                if (!isHeader) {
                    // This is a data row
                    if (index < tds.size()) {
                        return cleanText(tds.get(index).text());
                    }
                    break;
                }
            }
        }
        return "";
    }

    /**
     * Extracts service fee from the invoice table.
     */
    private String extractServiceFee(Document doc) {
        for (Element td : doc.select("td.receipttableTd1")) {
            String text = td.text();
            if ((text.contains("የአገልግሎት ክፍያ/Service fee") || text.contains("Service fee"))
                    && !text.contains("VAT") && !text.contains("ተ.እ.ታ")) {
                Element next = td.nextElementSibling();
                if (next != null) {
                    return cleanText(next.text());
                }
            }
        }
        // Also try colspan rows
        for (Element td : doc.select("td[colspan]")) {
            String text = td.text();
            if ((text.contains("የአገልግሎት ክፍያ/Service fee") || text.contains("Service fee"))
                    && !text.contains("VAT") && !text.contains("ተ.እ.ታ")) {
                Element next = td.nextElementSibling();
                if (next != null) {
                    return cleanText(next.text());
                }
            }
        }
        return "";
    }

    /**
     * Extracts service fee VAT.
     */
    private String extractServiceFeeVAT(Document doc) {
        for (Element td : doc.select("td.receipttableTd1")) {
            String text = td.text();
            if (text.contains("Service fee VAT") || text.contains("የአገልግሎት ክፍያ ተ.እ.ታ")) {
                Element next = td.nextElementSibling();
                if (next != null) {
                    return cleanText(next.text());
                }
            }
        }
        return "";
    }

    /**
     * Extracts total paid amount.
     */
    private String extractTotalPaidAmount(Document doc) {
        for (Element td : doc.select("td.receipttableTd1")) {
            String text = td.text();
            if (text.contains("ጠቅላላ የተከፈለ/Total Paid Amount") || text.contains("Total Paid Amount")) {
                Element next = td.nextElementSibling();
                if (next != null) {
                    return cleanText(next.text());
                }
            }
        }
        return "";
    }

    /**
     * Cleans extracted text by removing extra whitespace.
     */
    private String cleanText(String text) {
        if (text == null)
            return "";
        return text.replaceAll("\\s+", " ").trim();
    }
}
