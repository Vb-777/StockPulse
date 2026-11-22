package com.stockpulse.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stockpulse.http.ApiError;
import com.stockpulse.json.Json;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderValidationTest {
    @Test
    void sortsLinesBySku() {
        TreeMap<String, Integer> l = OrderService.parseLines(
                Json.parseObject("{\"lines\":[{\"sku\":\"ZED-1\",\"qty\":2},{\"sku\":\"ABC-9\",\"qty\":1}]}"));
        assertEquals(List.of("ABC-9", "ZED-1"), List.copyOf(l.keySet()));
    }

    @Test
    void requestHashIgnoresLineOrderButNotQuantities() {
        String a = OrderService.requestHash(OrderService.parseLines(
                Json.parseObject("{\"lines\":[{\"sku\":\"A1\",\"qty\":1},{\"sku\":\"B2\",\"qty\":2}]}")));
        String b = OrderService.requestHash(OrderService.parseLines(
                Json.parseObject("{\"lines\":[{\"sku\":\"B2\",\"qty\":2},{\"sku\":\"A1\",\"qty\":1}]}")));
        String c = OrderService.requestHash(OrderService.parseLines(
                Json.parseObject("{\"lines\":[{\"sku\":\"A1\",\"qty\":1},{\"sku\":\"B2\",\"qty\":3}]}")));
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void rejectsBadLines() {
        for (String bad : new String[]{"{}", "{\"lines\":[]}", "{\"lines\":[{\"sku\":\"a\",\"qty\":1}]}",
                "{\"lines\":[{\"sku\":\"AB\",\"qty\":0}]}", "{\"lines\":[{\"sku\":\"AB\",\"qty\":1001}]}",
                "{\"lines\":[{\"sku\":\"AB\",\"qty\":1.5}]}", "{\"lines\":[{\"sku\":\"AB\",\"qty\":1},{\"sku\":\"AB\",\"qty\":2}]}",
                "{\"lines\":[1]}"}) {
            ApiError e = assertThrows(ApiError.class, () -> OrderService.parseLines(Json.parseObject(bad)), bad);
            assertEquals(400, e.status);
        }
    }

    @Test
    void capsLineCount() {
        StringBuilder b = new StringBuilder("{\"lines\":[");
        for (int i = 0; i <= OrderService.MAX_LINES; i++) b.append(i == 0 ? "" : ",").append("{\"sku\":\"S").append(i + 10).append("\",\"qty\":1}");
        String body = b.append("]}").toString();
        assertThrows(ApiError.class, () -> OrderService.parseLines(Json.parseObject(body)));
    }

    @Test
    void simulatedGatewayIsDeterministicPerOrder() throws InterruptedException {
        PaymentGateway g = new PaymentGateway.Simulated(0, 30);
        int declined = 0;
        for (int i = 0; i < 2000; i++) {
            UUID id = UUID.nameUUIDFromBytes(("o" + i).getBytes());
            boolean first = g.charge(id, 100).approved();
            assertEquals(first, g.charge(id, 100).approved());
            if (!first) declined++;
        }
        // 30% target; the hash split over 2000 ids lands within a few points of it.
        assertEquals(0.30, declined / 2000.0, 0.04);
    }
}
