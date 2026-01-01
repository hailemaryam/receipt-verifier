package org.hmmk.verifier.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.hmmk.verifier.dto.AbyssiniaVerifyResult;
import org.hmmk.verifier.dto.CbeVerifyResult;
import org.hmmk.verifier.dto.TelebirrReceipt;
import org.hmmk.verifier.service.AbyssiniaVerificationService;
import org.hmmk.verifier.service.CbeVerificationService;
import org.hmmk.verifier.service.DashenVerificationService;
import org.hmmk.verifier.service.TelebirrVerificationService;

import java.util.Optional;

/**
 * REST resource for receipt verification endpoints.
 */
@Path("/api/verify-receipt")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Receipt Verification", description = "Endpoints for verifying payment receipts")
public class ExternalFrontEndResource {

        @Inject
        org.hmmk.verifier.service.UnifiedVerificationService unifiedService;

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
                        @APIResponse(responseCode = "400", description = "Invalid request or bank service error")
        })
        public Response verifyUnified(org.hmmk.verifier.dto.UnifiedVerifyRequest request) {
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
}
