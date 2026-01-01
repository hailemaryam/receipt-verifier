package org.hmmk.verifier.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hmmk.verifier.dto.*;
import org.hmmk.verifier.model.VerifiedPayment;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@ApplicationScoped
public class UnifiedVerificationService {

    private static final Logger LOG = Logger.getLogger(UnifiedVerificationService.class);

    @Inject
    TelebirrVerificationService telebirrService;

    @Inject
    CbeVerificationService cbeService;

    @Inject
    AbyssiniaVerificationService abyssiniaService;

    @Inject
    DashenVerificationService dashenService;

    @ConfigProperty(name = "verifier.callback.url", defaultValue = "http://localhost:8080/api/callback")
    String callbackUrl;

    private final HttpClient httpClient;

    public UnifiedVerificationService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Transactional
    public UnifiedVerifyResult processVerification(UnifiedVerifyRequest request) {
        LOG.infof("Processing unified verification for bank: %s, reference: %s", request.getBankType(),
                request.getReference());

        // 1. Check if reference already exists
        if (VerifiedPayment.exists(request.getBankType(), request.getReference())) {
            LOG.warnf("Reference %s already verified for bank %s", request.getReference(), request.getBankType());
            return UnifiedVerifyResult.failure("Reference already processed");
        }

        // 2. Call required service based on Bank Type
        VerificationOutcome outcome = callBankService(request);
        if (!outcome.isSuccess()) {
            return UnifiedVerifyResult.failure(outcome.getError());
        }

        // 3. Call callback request to other system
        boolean callbackSuccess = sendCallback(request.getSenderId(), request.getReference(), request.getBankType(),
                outcome.getAmount());

        if (callbackSuccess) {
            // 4. Store the senderId along with other payment info to the database
            savePayment(request, outcome);
            return UnifiedVerifyResult.success("Verification successful and recorded");
        } else {
            LOG.errorf("Callback failed for reference: %s", request.getReference());
            return UnifiedVerifyResult.failure("Internal callback failed");
        }
    }

    private VerificationOutcome callBankService(UnifiedVerifyRequest request) {
        String bankType = request.getBankType().toUpperCase();
        switch (bankType) {
            case "CBE":
                var cbeRes = cbeService.verifyCBE(request.getReference(), request.getSuffix());
                return VerificationOutcome.from(cbeRes);
            case "TELEBIRR":
                var teleRes = telebirrService.verifyTelebirr(request.getReference());
                return VerificationOutcome.from(teleRes);
            case "ABYSSINIA":
                var abyRes = abyssiniaService.verifyAbyssinia(request.getReference(), request.getSuffix());
                return VerificationOutcome.from(abyRes);
            case "DASHEN":
                var dashRes = dashenService.verifyDashen(request.getReference());
                return VerificationOutcome.from(dashRes);
            default:
                return VerificationOutcome.error("Unsupported bank type: " + bankType);
        }
    }

    private boolean sendCallback(String senderId, String reference, String bankType, BigDecimal amount) {
        try {
            LOG.infof("Sending callback to %s", callbackUrl);
            String jsonBody = String.format(
                    "{\"senderId\":\"%s\", \"reference\":\"%s\", \"bankType\":\"%s\", \"amount\":%s}",
                    senderId, reference, bankType, amount != null ? amount.toString() : "0");

            HttpRequest callbackReq = HttpRequest.newBuilder()
                    .uri(URI.create(callbackUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(callbackReq, HttpResponse.BodyHandlers.ofString());
            LOG.infof("Callback response code: %d", response.statusCode());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            LOG.error("Error sending callback", e);
            return false;
        }
    }

    private void savePayment(UnifiedVerifyRequest request, VerificationOutcome outcome) {
        VerifiedPayment payment = VerifiedPayment.builder()
                .senderId(request.getSenderId())
                .reference(request.getReference())
                .bankType(request.getBankType())
                .amount(outcome.getAmount())
                .payerName(outcome.getPayerName())
                .transactionDate(outcome.getTransactionDate())
                .verifiedAt(LocalDateTime.now())
                .build();
        payment.persist();
    }

    /**
     * Internal wrapper for bank service response.
     */
    private static class VerificationOutcome {
        private final boolean success;
        private final String error;
        private final BigDecimal amount;
        private final String payerName;
        private final LocalDateTime transactionDate;

        private VerificationOutcome(boolean success, String error, BigDecimal amount, String payerName,
                LocalDateTime transactionDate) {
            this.success = success;
            this.error = error;
            this.amount = amount;
            this.payerName = payerName;
            this.transactionDate = transactionDate;
        }

        public static VerificationOutcome from(CbeVerifyResult res) {
            return new VerificationOutcome(res.isSuccess(), res.getError(), res.getAmount(), res.getPayer(),
                    res.getDate());
        }

        public static VerificationOutcome from(Optional<TelebirrReceipt> res) {
            if (res.isEmpty())
                return error("Telebirr receipt not found");
            var r = res.get();
            BigDecimal amount = null;
            try {
                amount = new BigDecimal(r.getSettledAmount().replaceAll("[^\\d.]", ""));
            } catch (Exception e) {
            }
            return new VerificationOutcome(true, null, amount, r.getPayerName(), null);
        }

        public static VerificationOutcome from(AbyssiniaVerifyResult res) {
            return new VerificationOutcome(res.isSuccess(), res.getError(), res.getAmount(), res.getPayer(),
                    res.getDate());
        }

        public static VerificationOutcome from(DashenVerifyResult res) {
            return new VerificationOutcome(res.isSuccess(), res.getError(), res.getTransactionAmount(),
                    res.getSenderName(), res.getTransactionDate());
        }

        public static VerificationOutcome error(String message) {
            return new VerificationOutcome(false, message, null, null, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getError() {
            return error;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getPayerName() {
            return payerName;
        }

        public LocalDateTime getTransactionDate() {
            return transactionDate;
        }
    }
}
