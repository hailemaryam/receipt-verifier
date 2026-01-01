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
import org.hmmk.verifier.dto.DashenVerifyResult;
import org.hmmk.verifier.dto.TelebirrReceipt;
import org.hmmk.verifier.service.AbyssiniaVerificationService;
import org.hmmk.verifier.service.CbeVerificationService;
import org.hmmk.verifier.service.DashenVerificationService;
import org.hmmk.verifier.service.TelebirrVerificationService;

import java.util.Optional;

/**
 * REST resource for receipt verification endpoints.
 */
@Path("/api/verify")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Receipt Verification", description = "Endpoints for verifying payment receipts")
public class VerificationResource {

        @Inject
        TelebirrVerificationService telebirrService;

        @Inject
        CbeVerificationService cbeService;

        @Inject
        AbyssiniaVerificationService abyssiniaService;

        @Inject
        DashenVerificationService dashenService;

        /**
         * Verifies a Telebirr receipt by transaction reference.
         *
         * @param reference The Telebirr transaction reference number
         * @return The verified receipt data or 404 if not found/invalid
         */
        @GET
        @Path("/telebirr/{reference}")
        @Operation(summary = "Verify Telebirr Receipt", description = "Fetches and verifies a Telebirr payment receipt by its transaction reference number")
        @APIResponses({
                        @APIResponse(responseCode = "200", description = "Successfully verified the receipt", content = @Content(schema = @Schema(implementation = TelebirrReceipt.class))),
                        @APIResponse(responseCode = "404", description = "Receipt not found or invalid"),
                        @APIResponse(responseCode = "400", description = "Invalid reference format")
        })
        public Response verifyTelebirr(
                        @Parameter(description = "Telebirr transaction reference number", required = true) @PathParam("reference") String reference) {

                if (reference == null || reference.isBlank()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Reference is required"))
                                        .build();
                }

                Optional<TelebirrReceipt> result = telebirrService.verifyTelebirr(reference.trim());

                return result
                                .map(receipt -> Response.ok(receipt).build())
                                .orElse(Response.status(Response.Status.NOT_FOUND)
                                                .entity(new ErrorResponse("Receipt not found or could not be verified"))
                                                .build());
        }

        /**
         * Verifies a CBE receipt by transaction reference and account suffix.
         *
         * @param reference     The CBE transaction reference number
         * @param accountSuffix The last 4 digits of the account number
         * @return The verification result
         */
        @GET
        @Path("/cbe")
        @Operation(summary = "Verify CBE Receipt", description = "Fetches and verifies a Commercial Bank of Ethiopia payment receipt by reference and account suffix")
        @APIResponses({
                        @APIResponse(responseCode = "200", description = "Verification completed (check 'success' field for result)", content = @Content(schema = @Schema(implementation = CbeVerifyResult.class))),
                        @APIResponse(responseCode = "400", description = "Missing required parameters")
        })
        public Response verifyCBE(
                        @Parameter(description = "CBE transaction reference number", required = true) @QueryParam("reference") String reference,
                        @Parameter(description = "Last 4 digits of the account number", required = true) @QueryParam("accountSuffix") String accountSuffix) {

                if (reference == null || reference.isBlank()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Reference is required"))
                                        .build();
                }

                if (accountSuffix == null || accountSuffix.isBlank()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Account suffix is required"))
                                        .build();
                }

                CbeVerifyResult result = cbeService.verifyCBE(reference.trim(), accountSuffix.trim());
                return Response.ok(result).build();
        }

        /**
         * Verifies an Abyssinia receipt by transaction reference and account suffix.
         *
         * @param reference The Abyssinia transaction reference number
         * @param suffix    The last 5 digits of the account number
         * @return The verification result
         */
        @GET
        @Path("/abyssinia")
        @Operation(summary = "Verify Abyssinia Receipt", description = "Fetches and verifies a Bank of Abyssinia payment receipt by reference and account suffix")
        @APIResponses({
                        @APIResponse(responseCode = "200", description = "Verification completed", content = @Content(schema = @Schema(implementation = AbyssiniaVerifyResult.class))),
                        @APIResponse(responseCode = "400", description = "Missing required parameters")
        })
        public Response verifyAbyssinia(
                        @Parameter(description = "Abyssinia transaction reference number", required = true) @QueryParam("reference") String reference,
                        @Parameter(description = "Last 5 digits of the account number", required = true) @QueryParam("suffix") String suffix) {

                if (reference == null || reference.isBlank()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Reference is required"))
                                        .build();
                }

                if (suffix == null || suffix.isBlank()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorResponse("Account suffix is required"))
                                        .build();
                }

                AbyssiniaVerifyResult result = abyssiniaService.verifyAbyssinia(reference.trim(), suffix.trim());
                return Response.ok(result).build();
        }

        /**
         * Verifies a Dashen receipt by transaction reference.
         *
         * @param reference The Dashen transaction reference number
         * @return The verification result
         */
//        @GET
//        @Path("/dashen/{reference}")
//        @Operation(summary = "Verify Dashen Receipt", description = "Fetches and verifies a Dashen Bank payment receipt by its transaction reference number")
//        @APIResponses({
//                        @APIResponse(responseCode = "200", description = "Verification completed", content = @Content(schema = @Schema(implementation = DashenVerifyResult.class))),
//                        @APIResponse(responseCode = "400", description = "Invalid reference")
//        })
//        public Response verifyDashen(
//                        @Parameter(description = "Dashen transaction reference number", required = true) @PathParam("reference") String reference) {
//
//                if (reference == null || reference.isBlank()) {
//                        return Response.status(Response.Status.BAD_REQUEST)
//                                        .entity(new ErrorResponse("Reference is required"))
//                                        .build();
//                }
//
//                DashenVerifyResult result = dashenService.verifyDashen(reference.trim());
//                return Response.ok(result).build();
//        }

        /**
         * Simple error response DTO.
         */
        public record ErrorResponse(String error) {
        }
}
