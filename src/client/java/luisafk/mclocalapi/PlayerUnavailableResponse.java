package luisafk.mclocalapi;

import io.javalin.http.ServiceUnavailableResponse;

public class PlayerUnavailableResponse extends ServiceUnavailableResponse {
    public PlayerUnavailableResponse() {
        super("Player not available");
    }
}
