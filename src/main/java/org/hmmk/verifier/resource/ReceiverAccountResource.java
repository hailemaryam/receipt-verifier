package org.hmmk.verifier.resource;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.hmmk.verifier.model.ReceiverAccount;

import java.util.List;

@Path("/api/receiver-accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReceiverAccountResource {

    @GET
    public List<ReceiverAccount> getAll() {
        return ReceiverAccount.listAll();
    }

    @GET
    @Path("/{id}")
    public ReceiverAccount getOne(@PathParam("id") Long id) {
        ReceiverAccount entity = ReceiverAccount.findById(id);
        if (entity == null) {
            throw new WebApplicationException("ReceiverAccount with id of " + id + " does not exist.", 404);
        }
        return entity;
    }

    @POST
    @Transactional
    public Response create(ReceiverAccount receiverAccount) {
        if (receiverAccount.id != null) {
            throw new WebApplicationException("Id was invalidly set on request.", 422);
        }
        receiverAccount.persist();
        return Response.ok(receiverAccount).status(201).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public ReceiverAccount update(@PathParam("id") Long id, ReceiverAccount receiverAccount) {
        ReceiverAccount entity = ReceiverAccount.findById(id);
        if (entity == null) {
            throw new WebApplicationException("ReceiverAccount with id of " + id + " does not exist.", 404);
        }

        entity.bankType = receiverAccount.bankType;
        entity.accountNumber = receiverAccount.accountNumber;
        entity.accountName = receiverAccount.accountName;

        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        ReceiverAccount entity = ReceiverAccount.findById(id);
        if (entity == null) {
            throw new WebApplicationException("ReceiverAccount with id of " + id + " does not exist.", 404);
        }
        entity.delete();
        return Response.status(204).build();
    }
}
