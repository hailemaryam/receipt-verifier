package org.hmmk.verifier.resource;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.hmmk.verifier.model.VerifiedPayment;
import org.hmmk.verifier.dto.common.PaginatedResponse;

import org.hmmk.verifier.dto.VerifiedPaymentFilter;
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
     * @param filter Filter criteria for verified payments
     * @return List of verified payments
     */
    @POST
    @Path("/list")
    @Operation(summary = "List Verified Payments", description = "Returns a paginated list of verified payments with optional filters")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Successfully retrieved list", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaginatedResponse.class)))
    })
    public PaginatedResponse<VerifiedPayment> list(VerifiedPaymentFilter filter) {

        StringBuilder query = new StringBuilder("1=1");
        Parameters params = new Parameters();

        if (filter.getSenderId() != null && !filter.getSenderId().isBlank()) {
            query.append(" and senderId = :senderId");
            params.and("senderId", filter.getSenderId().trim());
        }

        if (filter.getBankType() != null && !filter.getBankType().isBlank()) {
            query.append(" and bankType = :bankType");
            params.and("bankType", filter.getBankType().trim());
        }

        if (filter.getFromDate() != null) {
            query.append(" and transactionDate >= :fromDate");
            params.and("fromDate", filter.getFromDate());
        }

        if (filter.getToDate() != null) {
            query.append(" and transactionDate <= :toDate");
            params.and("toDate", filter.getToDate());
        }

        List<VerifiedPayment> items = VerifiedPayment.find(query.toString(), params)
                .page(Page.of(filter.getPage(), filter.getPageSize()))
                .list();

        long total = VerifiedPayment.count(query.toString(), params);

        return new PaginatedResponse<>(items, total, filter.getPage(), filter.getPageSize());
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
