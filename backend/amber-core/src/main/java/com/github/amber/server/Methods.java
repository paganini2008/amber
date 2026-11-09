package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.amber.core.i18n.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of the engine's JSON-RPC methods. Methods whose results carry prose take an optional
 * {@code locale} (see {@link #localized}).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Methods {

    private Methods() {
    }

    static Map<String, JsonRpcServer.Handler> handlers(ObjectMapper mapper) {
        AnalysisService analysis = new AnalysisService();
        Map<String, JsonRpcServer.Handler> h = new LinkedHashMap<>();
        h.put("engine.info", params -> Main.info());
        h.put("engine.rules", localized(params -> Dtos.rules()));
        h.put("analyze", localized(params -> analysis.analyze(read(mapper, params, AnalysisService.Request.class))));
        MetadataService metadata = new MetadataService();
        h.put("metadata.import", params -> metadata.importMetadata(read(mapper, params, MetadataService.ImportRequest.class)));
        h.put("db.test", params -> metadata.test(read(mapper, params, MetadataService.TestRequest.class)));
        h.put("db.loadMetadata", localized(params -> metadata.load(read(mapper, params, MetadataService.LoadRequest.class))));
        AiService ai = new AiService(analysis);
        h.put("ai.buildContext", params -> ai.buildContext(read(mapper, params, AiService.ContextRequest.class)));
        h.put("ai.check", localized(params -> ai.check(read(mapper, params, AiService.CheckRequest.class))));
        h.put("ai.maskText", params -> ai.maskText(read(mapper, params, AiService.MaskTextRequest.class)));
        ToolsService tools = new ToolsService(analysis);
        h.put("references", localized(params -> tools.references(read(mapper, params, ToolsService.ReferencesRequest.class))));
        h.put("doc.generate", localized(params -> tools.document(read(mapper, params, ToolsService.DocRequest.class))));
        h.put("diff", localized(params -> tools.diff(read(mapper, params, ToolsService.DiffRequest.class))));
        h.put("rewrite.suggest", localized(params -> tools.rewrites(read(mapper, params, ToolsService.RewriteRequest.class))));
        h.put("rewrite.optimize", localized(params -> tools.optimize(read(mapper, params, ToolsService.OptimizeRequest.class))));
        h.put("rewrite.apply", localized(params -> tools.apply(read(mapper, params, ToolsService.ApplyRequest.class))));
        h.put("format", params -> tools.format(read(mapper, params, ToolsService.FormatRequest.class)));
        h.put("er.model", localized(params -> tools.erModel(read(mapper, params, ToolsService.ErModelRequest.class))));
        return h;
    }

    /**
     * A handler that runs in the language of its optional {@code locale} param ({@code en}, {@code zh-CN} or
     * {@code es}; anything else is English). Only the prose of the result changes; error messages stay English.
     */
    static JsonRpcServer.Handler localized(JsonRpcServer.Handler handler) {
        return params -> Messages.call(
                Messages.of(locale(params)), () -> handler.handle(params));
    }

    /** The {@code locale} param when it is a string, else null. */
    static String locale(JsonNode params) {
        JsonNode l = params == null ? null : params.get("locale");
        return l != null && l.isTextual() ? l.asText() : null;
    }

    static <T> T read(ObjectMapper mapper, JsonNode params, Class<T> type) {
        if (params == null || params.isNull()) {
            throw new IllegalArgumentException("Missing params");
        }
        try {
            return mapper.treeToValue(params, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid params: " + e.getMessage());
        }
    }
}
