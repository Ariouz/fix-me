package fr.vicalvez.fixme.broker.model;

import java.math.BigDecimal;
import java.util.Objects;

/** An order that a Broker submits to a target Market through the Router. */
public record BrokerOrder(
		String orderId,
		String marketId,
		String market,
		OrderSide side,
		String instrument,
		long quantity,
		BigDecimal price
) {
	public BrokerOrder(String marketId, String market, OrderSide side, String instrument, long quantity, BigDecimal price) {
		this(java.util.UUID.randomUUID().toString(), marketId, market, side, instrument, quantity, price);
	}

	public BrokerOrder {
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalArgumentException("Order ID is required");
		}
		if (marketId == null || !marketId.matches("\\d{6}")) {
			throw new IllegalArgumentException("Market ID must contain six digits");
		}
		if (market == null || market.isBlank()) {
			throw new IllegalArgumentException("Market is required");
		}
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
}
