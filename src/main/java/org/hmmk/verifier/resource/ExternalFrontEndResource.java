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
import org.hmmk.verifier.model.ReceiverAccount;
import java.util.List;

/**
 * REST resource for receipt verification endpoints.
 */
@Path("/api/verify-receipt")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Receipt Verification For External Front End", description = "Endpoints for verifying payment receipts")
public class ExternalFrontEndResource {

        @Inject
        org.hmmk.verifier.service.UnifiedVerificationService unifiedService;

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
         * Simple error response DTO.
         */
        public record ErrorResponse(String error) {
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
}
