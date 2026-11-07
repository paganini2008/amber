package com.github.amber.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.amber.core.Dialect;
import com.github.amber.dialect.DialectFrontends;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine entry point.
 * <pre>
 *   engine.jar [serve]                                   JSON-RPC over stdin/stdout
 *   engine.jar analyze FILE --dialect ID [--fallback] [--json]
 *   engine.jar version
 * </pre>
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Main {

    public static final String VERSION = versionOf();

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "serve" : args[0];
        switch (command) {
            case "serve" -> serve();
            case "analyze" -> System.exit(Cli.analyze(mapper(), List.of(args).subList(1, args.length), System.out, System.err));
            case "doc" -> System.exit(Cli.doc(List.of(args).subList(1, args.length), System.out, System.err));
            case "version" -> System.out.println("Amber " + VERSION);
            default -> {
                System.err.println("Usage: engine.jar [serve | analyze FILE --dialect ID [--fallback] [--json] | doc FILE --dialect ID [--html] [--out PATH] | version]");
                System.exit(2);
            }
        }
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static void serve() throws Exception {
        PrintStream stdout = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));   // stray prints never corrupt the protocol
        ObjectMapper mapper = mapper();
        Warmup.startInBackground(System.err);
        new JsonRpcServer(mapper, Methods.handlers(mapper), System.err).serve(System.in, stdout);
    }

    /** Result of {@code engine.info}. */
    public record Info(String name, String version, String java, List<DialectInfo> dialects) {
    }

    public record DialectInfo(String id, String name) {
    }

    static Info info() {
        List<DialectInfo> dialects = new ArrayList<>();
        for (Dialect d : Dialect.values()) {
            if (DialectFrontends.all().containsKey(d)) {
                dialects.add(new DialectInfo(d.id(), d.displayName()));
            }
        }
        return new Info("Amber", VERSION, Runtime.version().toString(), dialects);
    }

    private static String versionOf() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
