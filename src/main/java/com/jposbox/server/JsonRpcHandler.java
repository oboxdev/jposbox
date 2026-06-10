package com.jposbox.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base for endpoints called by Odoo's hw_proxy JS client, which posts a
 * JSON-RPC 2.0 envelope: {"jsonrpc":"2.0","method":"call","params":{...},"id":N}
 * and expects {"jsonrpc":"2.0","id":N,"result":...} back.
 */
public abstract class JsonRpcHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(JsonRpcHandler.class.getName());
    protected static final Gson GSON = new Gson();

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        Object id = null;
        try {
            JsonObject params = new JsonObject();
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonObject body;
                try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    body = GSON.fromJson(reader, JsonObject.class);
                }
                if (body != null) {
                    if (body.has("id")) {
                        id = GSON.fromJson(body.get("id"), Object.class);
                    }
                    if (body.has("params") && body.get("params").isJsonObject()) {
                        params = body.getAsJsonObject("params");
                    }
                }
            }
            Object result = process(params);
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", GSON.toJsonTree(id));
            response.add("result", GSON.toJsonTree(result));
            sendJson(exchange, 200, response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Request failed: " + exchange.getRequestURI(), e);
            JsonObject error = new JsonObject();
            error.addProperty("message", e.getMessage() == null ? e.toString() : e.getMessage());
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", GSON.toJsonTree(id));
            response.add("error", error);
            sendJson(exchange, 200, response);
        }
    }

    /** Implement the call; return the JSON-RPC "result" payload. */
    protected abstract Object process(JsonObject params) throws Exception;

    protected static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
