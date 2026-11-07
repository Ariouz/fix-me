package fr.vicalvez.fixme.broker.model;

/** The execution status returned by a Market for one Broker order. */
public record OrderResult(String orderId, OrderStatus status) {

	public OrderResult {
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalArgumentException("Order ID is required");
		}
		if (status == null) {
			throw new IllegalArgumentException("Order status is required");
		}
	}
}
