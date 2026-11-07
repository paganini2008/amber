package com.github.amber.core.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class SourceDecoderTest {

    @Test
    void detectsUtf8Gb18030AndBom() {
        String sql = "select 'café crème' from dual;";
        assertEquals(StandardCharsets.UTF_8, SourceDecoder.decode(sql.getBytes(StandardCharsets.UTF_8)).charset());

        SourceDecoder.Decoded gbk = SourceDecoder.decode(sql.getBytes(SourceDecoder.GB18030));
        assertEquals(SourceDecoder.GB18030, gbk.charset());
        assertEquals(sql, gbk.text());

        byte[] body = sql.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        SourceDecoder.Decoded bom = SourceDecoder.decode(withBom);
        assertEquals(sql, bom.text());
        assertEquals(true, bom.bom());
    }
}
