package fr.vicalvez.fixme.market.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Domain representation of a validated BUY or SELL FIX message. */
public record TradeOrder(
		String orderId,
		String brokerId,
		String marketId,
		OrderSide side,
		String instrument,
		long quantity,
		BigDecimal price
) {

	public TradeOrder {
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalArgumentException("Order ID is required");
		}
		brokerId = requiredId(brokerId, "Broker ID");
		marketId = requiredId(marketId, "Market ID");
		side = Objects.requireNonNull(side, "Order side is required");
		if (instrument == null || instrument.isBlank()) {
			throw new IllegalArgumentException("Instrument is required");
		}
		if (quantity <= 0) {
			throw new IllegalArgumentException("Order quantity must be positive");
		}
		price = Objects.requireNonNull(price, "Order price is required");
		if (price.signum() <= 0) {
			throw new IllegalArgumentException("Order price must be positive");
		}
	}

	private static String requiredId(String id, String label) {
		if (id == null || !id.matches("\\d{6}")) {
			throw new IllegalArgumentException(label + " must contain six digits");
		}
		return id;
	}
}
