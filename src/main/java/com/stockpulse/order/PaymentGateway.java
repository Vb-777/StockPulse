package com.stockpulse.order;

import java.util.UUID;

/**
 * The external payment step. Real gateways accept an idempotency key; here the order id plays
 * that role, so charging the same order twice returns the same outcome.
 */
public interface PaymentGateway {
    record Outcome(boolean approved, String reason) { }

    Outcome charge(UUID orderId, long amountCents) throws InterruptedException;

    /**
     * Deterministic stand-in: a fixed latency, and a decline for a fixed fraction of orders chosen
     * by hashing the order id (so a redelivered order gets the same answer as the first time).
     */
    final class Simulated implements PaymentGateway {
        private final int latencyMs;
        private final int declinePercent;

        public Simulated(int latencyMs, int declinePercent) {
            if (declinePercent < 0 || declinePercent > 100) throw new IllegalArgumentException("declinePercent");
            this.latencyMs = latencyMs;
            this.declinePercent = declinePercent;
        }

        @Override
        public Outcome charge(UUID orderId, long amountCents) throws InterruptedException {
            if (latencyMs > 0) Thread.sleep(latencyMs);
            int bucket = Math.floorMod(orderId.hashCode(), 100);
            return bucket < declinePercent ? new Outcome(false, "payment_declined") : new Outcome(true, null);
        }
    }
}
