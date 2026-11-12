package fr.vicalvez.fixme.persistence;

public enum TransactionState {
	PENDING,
	PROCESSING,
	EXECUTED,
	REJECTED;

	public boolean isFinal() {
		return this == EXECUTED || this == REJECTED;
	}
}
