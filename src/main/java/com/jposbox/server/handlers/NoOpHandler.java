package com.jposbox.server.handlers;

import com.google.gson.JsonObject;
import com.jposbox.server.JsonRpcHandler;

/**
 * Stub for hw_proxy endpoints we don't need to act on (scan_item_success,
 * scan_item_error_unrecognized, take_control, test_ownership, ...).
 * Always returns the configured result.
 */
public class NoOpHandler extends JsonRpcHandler {

    private final Object result;

    public NoOpHandler(Object result) {
        this.result = result;
    }

    @Override
    protected Object process(JsonObject params) {
        return result;
    }
}
