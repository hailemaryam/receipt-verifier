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

    @ConfigProperty(name = "verifier.callback.url", defaultValue = "https://n8n.memby.online/webhook/59dca007-f8ff-4321-99ae-62d545e8bef6")
    String callbackUrl;

    @ConfigProperty(name = "verifier.telebirr.receiver-account", defaultValue = "")
    String telebirrReceiverAccount;

    @ConfigProperty(name = "verifier.telebirr.receiver-name", defaultValue = "")
    String telebirrReceiverName;

    @ConfigProperty(name = "verifier.cbe.receiver-account", defaultValue = "")
    String cbeReceiverAccount;

    @ConfigProperty(name = "verifier.cbe.receiver-name", defaultValue = "")
    String cbeReceiverName;

    @ConfigProperty(name = "verifier.abyssinia.receiver-account", defaultValue = "")
    String abyssiniaReceiverAccount;

    @ConfigProperty(name = "verifier.abyssinia.receiver-name", defaultValue = "")
    String abyssiniaReceiverName;

    @ConfigProperty(name = "verifier.dashen.receiver-account", defaultValue = "")
    String dashenReceiverAccount;

    @ConfigProperty(name = "verifier.dashen.receiver-name", defaultValue = "")
    String dashenReceiverName;

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

        // 2.1 Validate receiver details
        outcome = validateReceiver(request.getBankType(), outcome);
        if (!outcome.isSuccess()) {
            LOG.warnf("Receiver validation failed for %s: %s", request.getReference(), outcome.getError());
            return UnifiedVerifyResult.failure(outcome.getError());
        }

        // 3. Call callback request to other system
        boolean callbackSuccess = sendCallback(request.getSenderId(), request.getReference(), request.getBankType(),
                outcome.getAmount(), request.getMerchantReferenceId());

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

    private boolean sendCallback(String senderId, String reference, String bankType, BigDecimal amount,
            String merchantReferenceId) {
        try {
            LOG.infof("Sending callback to %s", callbackUrl);
            String jsonBody = String.format(
                    "{\"senderId\":\"%s\", \"reference\":\"%s\", \"bankType\":\"%s\", \"amount\":%s, \"merchantReferenceId\":\"%s\"}",
                    senderId, reference, bankType, amount != null ? amount.toString() : "0",
                    merchantReferenceId != null ? merchantReferenceId : "");

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
                .merchantReferenceId(request.getMerchantReferenceId())
                .verifiedAt(LocalDateTime.now())
                .build();
        payment.persist();
    }

    private VerificationOutcome validateReceiver(String bankType, VerificationOutcome outcome) {
        String expectedAccount = "";
        String expectedName = "";

        switch (bankType.toUpperCase()) {
            case "TELEBIRR":
                expectedAccount = telebirrReceiverAccount;
                expectedName = telebirrReceiverName;
                break;
            case "CBE":
                expectedAccount = cbeReceiverAccount;
                expectedName = cbeReceiverName;
                break;
            case "ABYSSINIA":
                expectedAccount = abyssiniaReceiverAccount;
                expectedName = abyssiniaReceiverName;
                break;
            case "DASHEN":
                expectedAccount = dashenReceiverAccount;
                expectedName = dashenReceiverName;
                break;
        }

        if (expectedAccount != null && !expectedAccount.isBlank() && outcome.getReceiverAccount() != null
                && !outcome.getReceiverAccount().contains(expectedAccount)
                && !expectedAccount.contains(outcome.getReceiverAccount())) {
            return VerificationOutcome.error("Receiver account mismatch");
        }

        if (expectedName != null && !expectedName.isBlank() && outcome.getReceiverName() != null
                && !outcome.getReceiverName().equalsIgnoreCase(expectedName)) {
            // Check if one contains the other for better matching
            if (!outcome.getReceiverName().toLowerCase().contains(expectedName.toLowerCase()) &&
                    !expectedName.toLowerCase().contains(outcome.getReceiverName().toLowerCase())) {
                return VerificationOutcome.error("Receiver name mismatch");
            }
        }

        return outcome;
    }

    /**
     * Internal wrapper for bank service response.
     */
    private static class VerificationOutcome {
        private final boolean success;
        private final String error;
        private final BigDecimal amount;
        private final String payerName;
        private final String receiverAccount;
        private final String receiverName;
        private final LocalDateTime transactionDate;

        private VerificationOutcome(boolean success, String error, BigDecimal amount, String payerName,
                String receiverAccount, String receiverName, LocalDateTime transactionDate) {
            this.success = success;
            this.error = error;
            this.amount = amount;
            this.payerName = payerName;
            this.receiverAccount = receiverAccount;
            this.receiverName = receiverName;
            this.transactionDate = transactionDate;
        }

        public static VerificationOutcome from(CbeVerifyResult res) {
            return new VerificationOutcome(res.isSuccess(), res.getError(), res.getAmount(), res.getPayer(),
                    res.getReceiverAccount(), res.getReceiver(), res.getDate());
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
            return new VerificationOutcome(true, null, amount, r.getPayerName(),
                    r.getCreditedPartyAccountNo(), r.getCreditedPartyName(), null);
        }

        public static VerificationOutcome from(AbyssiniaVerifyResult res) {
            return new VerificationOutcome(res.isSuccess(), res.getError(), res.getAmount(), res.getPayer(),
                    res.getReceiverAccount(), res.getReceiverName(), res.getDate());
        }

        public static VerificationOutcome from(DashenVerifyResult res) {
            return new VerificationOutcome(res.isSuccess(), res.getError(), res.getTransactionAmount(),
                    res.getSenderName(), null, res.getReceiverName(), res.getTransactionDate());
        }

        public static VerificationOutcome error(String message) {
            return new VerificationOutcome(false, message, null, null, null, null, null);
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

        public String getReceiverAccount() {
            return receiverAccount;
        }

        public String getReceiverName() {
            return receiverName;
        }

        public LocalDateTime getTransactionDate() {
            return transactionDate;
        }
    }
}
