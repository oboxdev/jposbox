package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.server.JsonRpcHandler;
import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/** POST /hw_proxy/handshake -> {"status":"connected"} */
public class HandshakeHandler extends JsonRpcHandler {
    @Override
    protected Object process(JsonObject params, HttpExchange exchange) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "connected");
        return result;
    }
}
