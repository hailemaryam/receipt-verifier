package org.hmmk.verifier.resource;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.hmmk.verifier.model.VerifiedPayment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.StringJoiner;

/**
 * REST resource for managing and listing verified payments.
 */
@Path("/api/verified-payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Verified Payments", description = "Endpoints for listing and managing verified payment records")
public class VerifiedPaymentResource {

    /**
     * Lists verified payments with filters for senderId, bankType, and transaction
     * date range.
     *
     * @param senderId Optional sender ID filter
     * @param bankType Optional bank type filter
     * @param fromDate Optional start date for transaction date range
     * @param toDate   Optional end date for transaction date range
     * @param page     Page number (0-indexed)
     * @param pageSize Number of records per page
     * @return List of verified payments
     */
    @GET
    @Operation(summary = "List Verified Payments", description = "Returns a paginated list of verified payments with optional filters")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Successfully retrieved list", content = @Content(mediaType = "application/json", schema = @Schema(type = SchemaType.ARRAY, implementation = VerifiedPayment.class)))
    })
    public List<VerifiedPayment> list(
            @Parameter(description = "Filter by sender ID") @QueryParam("senderId") String senderId,
            @Parameter(description = "Filter by bank type") @QueryParam("bankType") String bankType,
            @Parameter(description = "Start of transaction date range (ISO-8601)") @QueryParam("fromDate") LocalDateTime fromDate,
            @Parameter(description = "End of transaction date range (ISO-8601)") @QueryParam("toDate") LocalDateTime toDate,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("pageSize") int pageSize) {

        StringBuilder query = new StringBuilder("1=1");
        Parameters params = new Parameters();

        if (senderId != null && !senderId.isBlank()) {
            query.append(" and senderId = :senderId");
            params.and("senderId", senderId.trim());
        }

        if (bankType != null && !bankType.isBlank()) {
            query.append(" and bankType = :bankType");
            params.and("bankType", bankType.trim());
        }

        if (fromDate != null) {
            query.append(" and transactionDate >= :fromDate");
            params.and("fromDate", fromDate);
        }

        if (toDate != null) {
            query.append(" and transactionDate <= :toDate");
            params.and("toDate", toDate);
        }

        return VerifiedPayment.find(query.toString(), params)
                .page(Page.of(page, pageSize))
                .list();
    }

    /**
     * Retrieves a single verified payment by its ID.
     *
     * @param id The ID of the verified payment
     * @return The verified payment or 404
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get Verified Payment by ID", description = "Returns details of a specific verified payment")
    public Response getById(@PathParam("id") Long id) {
        return VerifiedPayment.<VerifiedPayment>findByIdOptional(id)
                .map(payment -> Response.ok(payment).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }
}
