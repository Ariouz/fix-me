package fr.vicalvez.fixme.persistence;

public final class DuplicateOrderIdException extends IllegalArgumentException {

	public DuplicateOrderIdException(String orderId) {
		super("Order ID already exists: " + orderId);
	}
}
