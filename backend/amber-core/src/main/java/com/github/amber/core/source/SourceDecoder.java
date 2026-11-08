package com.github.amber.core.source;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes SQL files whose encoding is not declared. Order: byte-order mark (UTF-8, UTF-16LE/BE),
 * UTF-16 without a byte-order mark (zero bytes in every other position), then strict UTF-8, then GB18030
 * (a superset of GBK and GB2312, common for scripts written on Chinese Windows). A caller-supplied charset
 * bypasses detection.
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
        Charset utf16 = utf16WithoutBom(bytes);
        if (utf16 != null) {
            return new Decoded(new String(bytes, utf16), utf16, false);
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

    /**
     * UTF-16 without a byte-order mark, as some export tools write it: mostly ASCII text then has a zero
     * byte in every other position (odd positions for little endian). Checked on the first 4 KB; SQL text
     * has no NUL characters otherwise, so UTF-8 and GB18030 files never look like this.
     */
    private static Charset utf16WithoutBom(byte[] bytes) {
        int n = Math.min(bytes.length, 4096) & ~1;
        if (n < 4) {
            return null;
        }
        int zeroEven = 0;
        int zeroOdd = 0;
        for (int i = 0; i < n; i += 2) {
            if (bytes[i] == 0) {
                zeroEven++;
            }
            if (bytes[i + 1] == 0) {
                zeroOdd++;
            }
        }
        int pairs = n / 2;
        if (zeroOdd * 10 >= pairs * 4 && zeroEven * 20 <= pairs) {
            return StandardCharsets.UTF_16LE;
        }
        if (zeroEven * 10 >= pairs * 4 && zeroOdd * 20 <= pairs) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
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
