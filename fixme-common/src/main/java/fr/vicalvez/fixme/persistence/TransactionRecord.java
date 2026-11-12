package fr.vicalvez.fixme.persistence;

import java.math.BigDecimal;

/** Persistent representation of one Broker order. */
public record TransactionRecord(
		String orderId,
		String brokerInstance,
		String brokerId,
		String marketId,
		String side,
		String instrument,
		long quantity,
		BigDecimal price,
		TransactionState state
) {
}
