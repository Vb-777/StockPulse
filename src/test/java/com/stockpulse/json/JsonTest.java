package com.stockpulse.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesNestedDocument() {
        Map<String, Object> m = Json.parseObject("{\"a\": [1, -2.5, \"x\\n\\u00e9\"], \"b\": {\"c\": null, \"d\": true}}");
        List<?> a = (List<?>) m.get("a");
        assertEquals(Long.valueOf(1), a.get(0));
        assertEquals(new BigDecimal("-2.5"), a.get(1));
        assertEquals("x\n\u00e9", a.get(2));
        Map<?, ?> b = (Map<?, ?>) m.get("b");
        assertTrue(b.containsKey("c"));
        assertEquals(Boolean.TRUE, b.get("d"));
    }

    @Test
    void roundTripsThroughWriter() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "quote\" back\\ tab\t ctl\u0001");
        m.put("n", 42L);
        m.put("l", List.of(1L, 2L));
        m.put("z", null);
        assertEquals(m, Json.parse(Json.write(m)));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[]{"", "{", "{\"a\":1,}", "[1 2]", "01", "1.", "-", "\"abc", "{\"a\":1}x",
                "nul", "{1:2}", "\"\\x\"", "{\"a\":1,\"a\":2}", "\"\u0001\""}) {
            assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void capsNestingDepth() {
        String deep = "[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5);
        assertThrows(Json.ParseException.class, () -> Json.parse(deep));
    }

    @Test
    void largeIntegersBecomeBigDecimal() {
        assertEquals(new BigDecimal("123456789012345678901"), Json.parse("123456789012345678901"));
        assertEquals(Long.valueOf(-7), Json.parse("-7"));
    }
}
