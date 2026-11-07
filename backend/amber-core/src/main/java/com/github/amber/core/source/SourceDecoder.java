package com.github.amber.core.source;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes SQL files whose encoding is not declared. Order: byte-order mark (UTF-8, UTF-16LE/BE),
 * then strict UTF-8, then GB18030 (a superset of GBK and GB2312, common for scripts written on
 * Chinese Windows). A caller-supplied charset bypasses detection.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SourceDecoder {

    public static final Charset GB18030 = Charset.forName("GB18030");

    public record Decoded(String text, Charset charset, boolean bom) {
    }

    private SourceDecoder() {
    }

    public static Decoded decode(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8, true);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, true);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE, true);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return new Decoded(text, StandardCharsets.UTF_8, false);
        } catch (CharacterCodingException notUtf8) {
            return decode(bytes, GB18030);
        }
    }

    /** Decodes with a known charset; malformed bytes become U+FFFD instead of failing. */
    public static Decoded decode(byte[] bytes, Charset charset) {
        return new Decoded(new String(bytes, charset), charset, false);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
