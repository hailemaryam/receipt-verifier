package org.hmmk.verifier.security;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

/**
 * Filter to verify API-Key header for specific endpoints.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyFilter implements ContainerRequestFilter {

    @ConfigProperty(name = "verifier.api-key")
    String apiKey;

    private static final String API_KEY_HEADER = "API-Key";
    private static final String PROTECTED_PATH = "/api/verify-receipt";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();

        // We only protect the specific endpoint
        if (path.contains(PROTECTED_PATH)) {
            String requestApiKey = requestContext.getHeaderString(API_KEY_HEADER);

            if (requestApiKey == null || !requestApiKey.equals(apiKey)) {
                requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                        .entity(new ErrorResponse("Invalid or missing API-Key"))
                        .build());
            }
        }
    }

    public record ErrorResponse(String error) {
    }
}
