package fr.vicalvez.fixme.market.model;

import java.util.Objects;

/** Result produced by a market after evaluating an order. */
public record ExecutionReport(TradeOrder order, ExecutionStatus status, String reason) {

	public ExecutionReport {
		order = Objects.requireNonNull(order, "Order is required");
		status = Objects.requireNonNull(status, "Execution status is required");
		reason = reason == null ? "" : reason;
		if (status == ExecutionStatus.REJECTED && reason.isBlank()) {
			throw new IllegalArgumentException("A rejected order requires a reason");
		}
	}

	public static ExecutionReport executed(TradeOrder order) {
		return new ExecutionReport(order, ExecutionStatus.EXECUTED, "");
	}

	public static ExecutionReport rejected(TradeOrder order, String reason) {
		return new ExecutionReport(order, ExecutionStatus.REJECTED, reason);
	}
}
