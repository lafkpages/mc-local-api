package luisafk.mclocalapi.rest;

import io.javalin.http.ForbiddenResponse;

public class EndpointDisabledResponse extends ForbiddenResponse {
    public EndpointDisabledResponse() {
        super("This endpoint is disabled in the user's configuration");
    }
}