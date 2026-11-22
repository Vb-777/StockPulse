package com.stockpulse.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingTest {
    @Test
    void matchesTemplatesAndDecodesParams() {
        Map<String, String> p = HttpApi.match(new String[]{"orders", "{id}"}, new String[]{"orders", "a%20b"});
        assertEquals("a b", p.get("id"));
        assertNull(HttpApi.match(new String[]{"orders", "{id}"}, new String[]{"orders"}));
        assertNull(HttpApi.match(new String[]{"orders", "{id}"}, new String[]{"orders", ""}));
        assertNull(HttpApi.match(new String[]{"products"}, new String[]{"orders"}));
    }

    @Test
    void parsesQueryStrings() {
        Map<String, String> q = HttpApi.query("a=1&b=x%2By&flag&a=2");
        assertEquals("1", q.get("a"));
        assertEquals("x+y", q.get("b"));
        assertEquals("", q.get("flag"));
    }
}
