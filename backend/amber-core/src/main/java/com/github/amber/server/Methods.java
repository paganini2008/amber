package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of the engine's JSON-RPC methods.
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
        h.put("analyze", params -> analysis.analyze(read(mapper, params, AnalysisService.Request.class)));
        MetadataService metadata = new MetadataService();
        h.put("metadata.import", params -> metadata.importMetadata(read(mapper, params, MetadataService.ImportRequest.class)));
        h.put("db.test", params -> metadata.test(read(mapper, params, MetadataService.TestRequest.class)));
        h.put("db.loadMetadata", params -> metadata.load(read(mapper, params, MetadataService.LoadRequest.class)));
        AiService ai = new AiService(analysis);
        h.put("ai.buildContext", params -> ai.buildContext(read(mapper, params, AiService.ContextRequest.class)));
        h.put("ai.check", params -> ai.check(read(mapper, params, AiService.CheckRequest.class)));
        h.put("ai.maskText", params -> ai.maskText(read(mapper, params, AiService.MaskTextRequest.class)));
        ToolsService tools = new ToolsService(analysis);
        h.put("references", params -> tools.references(read(mapper, params, ToolsService.ReferencesRequest.class)));
        h.put("doc.generate", params -> tools.document(read(mapper, params, ToolsService.DocRequest.class)));
        h.put("diff", params -> tools.diff(read(mapper, params, ToolsService.DiffRequest.class)));
        h.put("rewrite.suggest", params -> tools.rewrites(read(mapper, params, ToolsService.RewriteRequest.class)));
        h.put("rewrite.apply", params -> tools.apply(read(mapper, params, ToolsService.ApplyRequest.class)));
        h.put("format", params -> tools.format(read(mapper, params, ToolsService.FormatRequest.class)));
        return h;
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
