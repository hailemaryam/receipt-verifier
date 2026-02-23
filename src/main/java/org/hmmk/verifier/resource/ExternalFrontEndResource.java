package org.hmmk.verifier.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.hmmk.verifier.dto.OcrResult;
import org.hmmk.verifier.dto.UnifiedVerifyRequest;
import org.hmmk.verifier.dto.UnifiedVerifyResult;
import org.hmmk.verifier.model.ReceiverAccount;
import org.hmmk.verifier.model.FailedVerification;
import org.hmmk.verifier.dto.common.PaginatedResponse;
import org.hmmk.verifier.service.OcrService;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * REST resource for receipt verification endpoints.
 */
@Path("/api/verify-receipt")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Receipt Verification For External Front End", description = "Endpoints for verifying payment receipts")
public class ExternalFrontEndResource {

        private static final Logger LOG = Logger.getLogger(ExternalFrontEndResource.class);

        @Inject
        org.hmmk.verifier.service.UnifiedVerificationService unifiedService;

        @Inject
        OcrService ocrService;

        @org.eclipse.microprofile.config.inject.ConfigProperty(name = "verifier.api-key")
        String apiKey;

        /**
         * Unified verification endpoint for various bank types.
         * Coordinates bank service call, external callback, and persistence.
         *
         * @param request The unified verification request
         * @return The process result
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Operation(summary = "Unified Bank Receipt Verification", description = "Verifies receipts across different banks, notifies external systems, and persists results")
        @APIResponses({
                        @APIResponse(responseCode = "200", description = "Verification process completed", content = @Content(schema = @Schema(implementation = org.hmmk.verifier.dto.UnifiedVerifyResult.class))),
                        @APIResponse(responseCode = "400", description = "Invalid request or bank service error"),
                        @APIResponse(responseCode = "401", description = "Invalid or missing API-Key")
        })
        @Parameter(name = "API-Key", description = "Required API Key for authentication", required = true, in = ParameterIn.HEADER, schema = @Schema(type = SchemaType.STRING))
        public Response verifyUnified(
                        @HeaderParam("API-Key") String apiKeyHeader,
                        org.hmmk.verifier.dto.UnifiedVerifyRequest request) {
                if (apiKeyHeader == null || !apiKeyHeader.equals(apiKey)) {
                        return Response.status(Response.Status.UNAUTHORIZED)
                                        .entity(new ErrorResponse("Invalid or missing API-Key"))
                                        .build();
                }

                if (request == null || request.getReference() == null || request.getBankType() == null
                                || request.getSenderId() == null) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Bank type, reference, and senderId are required"))
                                        .build();
                }

                org.hmmk.verifier.dto.UnifiedVerifyResult result = unifiedService.processVerification(request);
                if (result.isSuccess()) {
                        return Response.ok(result).build();
                } else {
                        return Response.status(Response.Status.BAD_REQUEST).entity(result).build();
                }
        }

        /**
         * Screenshot upload endpoint for OCR-based receipt verification.
         * Accepts a receipt screenshot, uses AI vision to extract bank type and
         * reference,
         * then feeds the result into the unified verification pipeline.
         */
        @POST
        @Path("/upload-screenshot")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Operation(summary = "Verify Receipt via Screenshot", description = "Upload a receipt screenshot for OCR-based verification. Supports Telebirr and CBE receipts.")
        @APIResponses({
                        @APIResponse(responseCode = "200", description = "OCR and verification completed"),
                        @APIResponse(responseCode = "400", description = "Invalid image or OCR failed"),
                        @APIResponse(responseCode = "401", description = "Invalid or missing API-Key")
        })
        @Parameter(name = "API-Key", description = "Required API Key for authentication", required = true, in = ParameterIn.HEADER, schema = @Schema(type = SchemaType.STRING))
        public Response verifyScreenshot(
                        @HeaderParam("API-Key") String apiKeyHeader,
                        @RestForm("file") FileUpload file,
                        @RestForm("senderId") String senderId,
                        @RestForm("merchantReferenceId") String merchantReferenceId,
                        @RestForm("suffix") String suffix) {

                if (apiKeyHeader == null || !apiKeyHeader.equals(apiKey)) {
                        return Response.status(Response.Status.UNAUTHORIZED)
                                        .entity(new ErrorResponse("Invalid or missing API-Key"))
                                        .build();
                }

                if (file == null || file.filePath() == null) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("No file uploaded"))
                                        .build();
                }

                if (senderId == null || senderId.isBlank()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("senderId is required"))
                                        .build();
                }

                try {
                        // Read the uploaded file
                        byte[] imageBytes = Files.readAllBytes(file.filePath());
                        String mimeType = file.contentType() != null ? file.contentType() : "image/jpeg";

                        LOG.infof("Received screenshot upload: %s (%d bytes, type: %s)",
                                        file.fileName(), imageBytes.length, mimeType);

                        // Step 1: OCR analysis
                        OcrResult ocrResult = ocrService.analyzeReceipt(imageBytes, mimeType);

                        if (!ocrResult.isSuccess()) {
                                LOG.warnf("OCR analysis failed: %s", ocrResult.getError());
                                return Response.status(Response.Status.BAD_REQUEST)
                                                .entity(new ScreenshotResult(false, null, null, null,
                                                                ocrResult.getError()))
                                                .build();
                        }

                        LOG.infof("OCR detected bank: %s, reference: %s", ocrResult.getBankType(),
                                        ocrResult.getReference());

                        // Step 2: Feed into unified verification pipeline
                        UnifiedVerifyRequest verifyRequest = UnifiedVerifyRequest.builder()
                                        .bankType(ocrResult.getBankType())
                                        .reference(ocrResult.getReference())
                                        .suffix(suffix != null ? suffix : "")
                                        .senderId(senderId)
                                        .merchantReferenceId(merchantReferenceId != null ? merchantReferenceId : "")
                                        .build();

                        UnifiedVerifyResult verifyResult = unifiedService.processVerification(verifyRequest);

                        ScreenshotResult result = new ScreenshotResult(
                                        verifyResult.isSuccess(),
                                        ocrResult.getBankType(),
                                        ocrResult.getReference(),
                                        verifyResult.getMessage(),
                                        verifyResult.isSuccess() ? null : verifyResult.getMessage());

                        if (verifyResult.isSuccess()) {
                                return Response.ok(result).build();
                        } else {
                                return Response.status(Response.Status.BAD_REQUEST).entity(result).build();
                        }

                } catch (IOException e) {
                        LOG.error("Error reading uploaded file", e);
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                        .entity(new ErrorResponse("Failed to read uploaded file"))
                                        .build();
                }
        }

        /**
         * Simple error response DTO.
         */
        public record ErrorResponse(String error) {
        }

        /**
         * Response for screenshot-based verification.
         */
        public record ScreenshotResult(
                        boolean success,
                        String detectedBank,
                        String extractedReference,
                        String message,
                        String error) {
        }

        /**
         * this endpoint will return bank account list one from each bank type while
         * returning it will load balance bank account from each bank
         */
        @GET
        @jakarta.transaction.Transactional
        @Operation(summary = "Get Bank Account List", description = "Returns a list of bank accounts with load balance")
        @APIResponses({
                        @APIResponse(responseCode = "200", description = "Bank account list retrieved successfully", content = @Content(schema = @Schema(implementation = ReceiverAccount.class))),
                        @APIResponse(responseCode = "400", description = "Invalid request or bank service error"),
                        @APIResponse(responseCode = "401", description = "Invalid or missing API-Key")
        })
        @Parameter(name = "API-Key", description = "Required API Key for authentication", required = true, in = ParameterIn.HEADER, schema = @Schema(type = SchemaType.STRING))
        public Response getBankAccountList(
                        @HeaderParam("API-Key") String apiKeyHeader) {
                if (apiKeyHeader == null || !apiKeyHeader.equals(apiKey)) {
                        return Response.status(Response.Status.UNAUTHORIZED)
                                        .entity(new ErrorResponse("Invalid or missing API-Key"))
                                        .build();
                }

                List<String> bankTypes = List.of("Telebirr", "CBE", "Abyssinia"); // Example bank types
                java.util.List<ReceiverAccount> result = new java.util.ArrayList<>();
                for (String bankType : bankTypes) {
                        ReceiverAccount next = ReceiverAccount.getNextAccount(bankType);
                        if (next != null) {
                                result.add(next);
                        }
                }

                return Response.ok(result).build();
        }

        /**
         * Returns a list of failed verification attempts, latest first.
         */
        @GET
        @Path("/failed")
        @Operation(summary = "Get Failed Verification Attempts", description = "Returns a list of failed verification attempts, ordered by latest first")
        @APIResponses({
                        @APIResponse(responseCode = "200", description = "Failed attempts retrieved successfully", content = @Content(schema = @Schema(implementation = PaginatedResponse.class))),
                        @APIResponse(responseCode = "401", description = "Invalid or missing API-Key")
        })
        @Parameter(name = "API-Key", description = "Required API Key for authentication", required = true, in = ParameterIn.HEADER, schema = @Schema(type = SchemaType.STRING))
        public Response getFailedAttempts(
                        @HeaderParam("API-Key") String apiKeyHeader,
                        @QueryParam("page") @DefaultValue("0") int page,
                        @QueryParam("size") @DefaultValue("20") int size) {
                if (apiKeyHeader == null || !apiKeyHeader.equals(apiKey)) {
                        return Response.status(Response.Status.UNAUTHORIZED)
                                        .entity(new ErrorResponse("Invalid or missing API-Key"))
                                        .build();
                }

                List<FailedVerification> list = FailedVerification
                                .find("order by failedAt desc")
                                .page(page, size)
                                .list();

                long total = FailedVerification.count();

                return Response.ok(new PaginatedResponse<>(list, total, page, size)).build();
        }
}
