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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON-RPC 2.0 over line-delimited JSON on stdin/stdout. Requests run concurrently; each response
 * is written as one line. Supports {@code $/cancel}: the request is answered with -32800 at once (also
 * when it was still queued) and its worker is interrupted. Every request with an id gets exactly one
 * answer; notifications get none. When the input ends, requests already accepted are still answered
 * (for up to {@value #DRAIN_SECONDS} s) before {@link #serve} returns.
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
    /** How long {@link #serve} waits for accepted requests once the input has ended. */
    static final int DRAIN_SECONDS = 60;

    /** A method implementation: params (may be null) to a result object serializable by Jackson. */
    @FunctionalInterface
    public interface Handler {
        Object handle(JsonNode params) throws Exception;
    }

    /**
     * Whether a failure is the result of {@code $/cancel}: the worker was interrupted, or the chain holds a
     * cancellation or interruption (parallel parsing wraps them in {@code CompletionException}).
     */
    static boolean isCancellation(Throwable e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CancellationException || t instanceof InterruptedException
                    || t instanceof java.nio.channels.ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    private final ObjectMapper mapper;
    private final Map<String, Handler> handlers;
    private final ExecutorService pool;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
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
            try {
                pool.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A request in flight; {@code answered} makes sure it is answered once, by the worker or by a cancel. */
    private record Running(JsonNode id, String method, FutureTask<?> task, AtomicBoolean answered) {
    }

    void accept(String line) {
        JsonNode msg;
        try {
            msg = mapper.readTree(line);
        } catch (IOException e) {
            send(error(idOf(line), PARSE_ERROR, "Invalid JSON: " + e.getMessage()));
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
            Running r = target == null ? null : running.remove(target.toString());
            if (r != null) {
                r.task().cancel(true);
                if (r.answered().compareAndSet(false, true)) {
                    log.println("amber: request " + target + " (" + r.method() + ") cancelled");
                    send(error(r.id(), CANCELLED, "Request cancelled"));
                }
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
        AtomicBoolean answered = new AtomicBoolean();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                Object result = handler.handle(params);
                if (id != null) {
                    ObjectNode resp = mapper.createObjectNode();
                    resp.put("jsonrpc", "2.0");
                    resp.set("id", id);
                    resp.putPOJO("result", result);                // serialized once, without an intermediate tree
                    String answer = mapper.writeValueAsString(resp);  // before claiming: a failure here is answered below
                    if (answered.compareAndSet(false, true)) {
                        write(answer);
                    }
                }
            } catch (IllegalArgumentException e) {
                if (id != null && answered.compareAndSet(false, true)) {
                    send(error(id, INVALID_PARAMS, e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            } catch (java.nio.file.FileSystemException e) {
                if (id != null && answered.compareAndSet(false, true)) {
                    send(error(id, INVALID_PARAMS, fileProblem(e)));     // a path the user gave: not an engine error
                }
            } catch (Throwable e) {
                boolean answer = id != null && answered.compareAndSet(false, true);
                if (id != null && !answer) {
                    return;                                             // already answered as cancelled
                }
                if (isCancellation(e)) {
                    log.println("amber: request " + key + " (" + method + ") cancelled");
                    if (answer) {
                        send(error(id, CANCELLED, "Request cancelled"));
                    }
                } else {
                    e.printStackTrace(log);
                    if (answer) {
                        send(error(id, INTERNAL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage()));
                    }
                }
            } finally {
                if (key != null) {
                    running.remove(key);
                }
            }
        }, null);
        if (key != null) {
            running.put(key, new Running(id, method, task, answered));   // before it runs: no stale entries
        }
        pool.execute(task);
    }

    private static String fileProblem(java.nio.file.FileSystemException e) {
        String file = e.getFile();
        if (e instanceof java.nio.file.NoSuchFileException) {
            return "File not found: " + file;
        }
        if (e instanceof java.nio.file.AccessDeniedException) {
            return "Access denied: " + file;
        }
        return "Cannot access " + file + ": " + e.getMessage();
    }

    /**
     * The id of a request whose line is not valid JSON (cut off, or over a limit), read with a streaming
     * parser that skips the other members without materializing them; null when it cannot be found.
     */
    JsonNode idOf(String line) {
        try (com.fasterxml.jackson.core.JsonParser p = mapper.getFactory().createParser(line)) {
            if (p.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                String name = p.currentName();
                com.fasterxml.jackson.core.JsonToken value = p.nextToken();
                if ("id".equals(name)) {
                    return value != null && value.isScalarValue() && value != com.fasterxml.jackson.core.JsonToken.VALUE_TRUE
                            && value != com.fasterxml.jackson.core.JsonToken.VALUE_FALSE ? mapper.readTree(p) : null;
                }
                p.skipChildren();
            }
        } catch (IOException | RuntimeException e) {
            // no id to be found
        }
        return null;
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
            write(mapper.writeValueAsString(message));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String line) {
        try {
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (this) {
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
