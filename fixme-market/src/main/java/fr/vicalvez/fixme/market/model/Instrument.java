package fr.vicalvez.fixme.market.model;

import java.math.BigDecimal;
import java.util.Objects;

/** A tradable instrument and the quantity currently available on this market. */
public record Instrument(String symbol, long availableQuantity, BigDecimal price) {

	public Instrument {
		if (symbol == null || symbol.isBlank()) {
			throw new IllegalArgumentException("Instrument symbol is required");
		}
		if (availableQuantity < 0) {
			throw new IllegalArgumentException("Available quantity cannot be negative");
		}
		price = Objects.requireNonNull(price, "Price is required");
		if (price.signum() <= 0) {
			throw new IllegalArgumentException("Price must be positive");
		}
	}

	public Instrument withAvailableQuantity(long quantity) {
		return new Instrument(symbol, quantity, price);
	}
}
