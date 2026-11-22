package com.stockpulse.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetricsTest {
    @Test
    void rendersCumulativeHistogramBuckets() {
        Metrics m = new Metrics();
        m.observeMs(0.3, "lat", "route", "/x");
        m.observeMs(7, "lat", "route", "/x");
        m.observeMs(9999, "lat", "route", "/x");
        String out = m.render();
        assertTrue(out.contains("lat_bucket{route=\"/x\",le=\"0.5\"} 1"), out);
        assertTrue(out.contains("lat_bucket{route=\"/x\",le=\"10\"} 2"), out);
        assertTrue(out.contains("lat_bucket{route=\"/x\",le=\"+Inf\"} 3"), out);
        assertTrue(out.contains("lat_count{route=\"/x\"} 3"), out);
    }

    @Test
    void countsByLabel() {
        Metrics m = new Metrics();
        m.inc("req", "status", "200");
        m.inc("req", "status", "200");
        m.inc("req", "status", "500");
        assertEquals(2L, m.count("req", "status", "200"));
        assertEquals(1L, m.count("req", "status", "500"));
        assertEquals(0L, m.count("req", "status", "404"));
    }
}
