package luisafk.mclocalapi.rest;

import io.javalin.http.ServiceUnavailableResponse;

public class PlayerUnavailableResponse extends ServiceUnavailableResponse {
    public PlayerUnavailableResponse() {
        super("Player not available");
    }
}
