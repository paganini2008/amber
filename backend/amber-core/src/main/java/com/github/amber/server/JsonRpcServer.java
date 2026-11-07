package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * JSON-RPC 2.0 over line-delimited JSON on stdin/stdout. Requests run concurrently; each response
 * is written as one line. Supports {@code $/cancel}.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class JsonRpcServer {

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    static final int CANCELLED = -32800;

    /** A method implementation: params (may be null) to a result object serializable by Jackson. */
    @FunctionalInterface
    public interface Handler {
        Object handle(JsonNode params) throws Exception;
    }

    private final ObjectMapper mapper;
    private final Map<String, Handler> handlers;
    private final ExecutorService pool;
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();
    private final PrintStream log;
    private OutputStream out;

    public JsonRpcServer(ObjectMapper mapper, Map<String, Handler> handlers, PrintStream log) {
        this.mapper = mapper;
        this.handlers = Map.copyOf(handlers);
        this.log = log;
        this.pool = Executors.newFixedThreadPool(Math.max(1, com.github.amber.core.config.Env.get().integer("AMBER_RPC_THREADS",
                Math.max(2, Runtime.getRuntime().availableProcessors()))), r -> {
            Thread t = new Thread(null, r, "amber-rpc", 64L * 1024 * 1024);
            t.setDaemon(true);
            return t;
        });
    }

    /** Serves until {@code in} reaches end of input. */
    public void serve(InputStream in, OutputStream out) throws IOException {
        this.out = out;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    accept(line);
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    void accept(String line) {
        JsonNode msg;
        try {
            msg = mapper.readTree(line);
        } catch (IOException e) {
            send(error(null, PARSE_ERROR, "Invalid JSON: " + e.getMessage()));
            return;
        }
        if (!(msg instanceof ObjectNode obj) || !obj.hasNonNull("method")) {
            send(error(msg == null ? null : msg.get("id"), INVALID_REQUEST, "Not a JSON-RPC request"));
            return;
        }
        String method = obj.get("method").asText();
        JsonNode id = obj.get("id");
        JsonNode params = obj.get("params");
        if (method.equals("$/cancel")) {
            JsonNode target = params == null ? null : params.get("id");
            Future<?> f = target == null ? null : running.remove(target.toString());
            if (f != null) {
                f.cancel(true);
            }
            return;
        }
        Handler handler = handlers.get(method);
        if (handler == null) {
            if (id != null) {
                send(error(id, METHOD_NOT_FOUND, "Unknown method: " + method));
            }
            return;
        }
        String key = id == null ? null : id.toString();
        Future<?> future = pool.submit(() -> {
            try {
                Object result = handler.handle(params);
                if (id != null) {
                    ObjectNode resp = mapper.createObjectNode();
                    resp.put("jsonrpc", "2.0");
                    resp.set("id", id);
                    resp.set("result", mapper.valueToTree(result));
                    send(resp);
                }
            } catch (IllegalArgumentException e) {
                send(error(id, INVALID_PARAMS, e.getMessage()));
            } catch (CancellationException | InterruptedException e) {
                send(error(id, CANCELLED, "Request cancelled"));
            } catch (Throwable e) {
                e.printStackTrace(log);
                send(error(id, INTERNAL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage()));
            } finally {
                if (key != null) {
                    running.remove(key);
                }
            }
        });
        if (key != null) {
            running.put(key, future);
        }
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        return resp;
    }

    private void send(ObjectNode message) {
        if (message.get("id") == null || message.get("id").isNull()) {
            if (message.has("error") && message.get("error").get("code").asInt() == CANCELLED) {
                return;
            }
        }
        try {
            byte[] bytes = (mapper.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (this) {
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
