package fr.vicalvez.fixme.market;

import fr.vicalvez.fixme.market.model.ExecutionStatus;
import fr.vicalvez.fixme.market.model.OrderSide;
import fr.vicalvez.fixme.market.model.TradeOrder;
import fr.vicalvez.fixme.protocol.FixMessage;
import fr.vicalvez.fixme.protocol.FixMessageType;
import fr.vicalvez.fixme.protocol.FixTag;
import fr.vicalvez.fixme.persistence.TransactionRepository;
import fr.vicalvez.fixme.persistence.TransactionState;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class MarketService {

	private final HashMap<String, Long> instruments = new HashMap<>();
	private final TransactionRepository transactions;
	private MarketConnection connection;

	public MarketService() {
		this(new TransactionRepository());
	}

	MarketService(TransactionRepository transactions) {
		this.transactions = transactions;
	}

	public void initInstruments() {
		this.instruments.put("AAPL", 15L);
		this.instruments.put("META", 1L);
		this.instruments.put("GOOGL", 48L);
		this.instruments.put("NVDA", 600L);
		this.instruments.put("MSFT", 25L);
		if (MarketApplication.MARKET_ID != null) {
			instruments.forEach((symbol, quantity) -> transactions.ensureInstrument(
					MarketApplication.MARKET_ID, symbol, quantity, new BigDecimal("1.00")
			));
			transactions.recoverMarket(MarketApplication.MARKET_ID);
		}
	}

	public void handleMessage(FixMessage message) {
		if (!message.targetId().equals(MarketApplication.MARKET_ID)
				|| !message.getField(FixTag.MARKET).equals(MarketApplication.MARKET_ID)) {
			throw new IllegalArgumentException("Message is not addressed to this Market");
		}

		try {
			TradeOrder order = asTradeOrder(message);
			TransactionState state = transactions.processOrder(order.orderId(), order.brokerId(), order.marketId());
			ExecutionStatus status = state == TransactionState.EXECUTED
					? ExecutionStatus.EXECUTED : ExecutionStatus.REJECTED;
			sendStatus(message.senderId(), order.orderId(), status, responseReason(order, status));
		} catch (Exception ignored) {
			sendStatus(message.senderId(), message.getField(FixTag.ORDER_ID), ExecutionStatus.REJECTED, "invalid order");
		}

	}

	public ExecutionStatus buy(TradeOrder order) {
		if (!instruments.containsKey(order.instrument())) return ExecutionStatus.REJECTED;
		long qty = instruments.get(order.instrument());

		if (order.quantity() <= 0) return ExecutionStatus.REJECTED;
		if (order.quantity() > qty) return ExecutionStatus.REJECTED;

		instruments.replace(order.instrument(), qty - order.quantity());
		return ExecutionStatus.EXECUTED;
	}

	public ExecutionStatus sell(TradeOrder order) {
		if (!instruments.containsKey(order.instrument())) return ExecutionStatus.REJECTED;
		long qty = instruments.get(order.instrument());

		if (order.quantity() <= 0) return ExecutionStatus.REJECTED;

		instruments.replace(order.instrument(), qty + order.quantity());
		return ExecutionStatus.EXECUTED;
	}

	public long availableQuantity(String symbol) {
		Long quantity = instruments.get(symbol);
		if (quantity == null) {
			throw new IllegalArgumentException("Unknown instrument: " + symbol);
		}
		return quantity;
	}

	public void sendStatus(String targetId, String orderId, ExecutionStatus status) {
		sendStatus(targetId, orderId, status, status == ExecutionStatus.EXECUTED ? "order executed" : "order rejected");
	}

	private void sendStatus(String targetId, String orderId, ExecutionStatus status, String reason) {
		if (connection == null) { throw new NullPointerException("Failed to send status message, connection is null"); }
		connection.send(toStatusMessage(targetId, orderId, status));
		System.out.println("[MARKET] " + status + " order " + orderId + ": " + reason);
	}

	public FixMessage toStatusMessage(String targetId, String orderId, ExecutionStatus status) {
		return new FixMessage(MarketApplication.MARKET_ID, targetId, getFIXMessageType(status), Map.of(FixTag.ORDER_ID, orderId));
	}

	private String responseReason(TradeOrder order, ExecutionStatus status) {
		if (status == ExecutionStatus.EXECUTED) {
			return "order executed";
		}
		if (transactions.find(order.orderId()).isEmpty()) {
			return "transaction not found in the shared database";
		}
		Long available = transactions.availableQuantity(order.marketId(), order.instrument()).orElse(null);
		if (available == null) {
			return "instrument is not traded";
		}
		if (order.side() == OrderSide.BUY && order.quantity() > available) {
			return "insufficient stock";
		}
		return "order rejected";
	}

	public static TradeOrder asTradeOrder(FixMessage message) throws IllegalArgumentException{
		return new TradeOrder(
				message.getField(FixTag.ORDER_ID),
				message.senderId(),
				message.targetId(),
				message.type() == FixMessageType.BUY ? OrderSide.BUY : message.type() == FixMessageType.SELL ? OrderSide.SELL : null,
				message.getField(FixTag.SYMBOL),
				Long.parseLong(message.getField(FixTag.ORDER_QTY)),
				new BigDecimal(message.getField(FixTag.PRICE))
		);
	}

	public FixMessageType getFIXMessageType(ExecutionStatus status) {
		return status == ExecutionStatus.EXECUTED ? FixMessageType.EXECUTED : FixMessageType.REJECTED;
	}

	public void setConnection(MarketConnection connection) {
		this.connection = connection;
	}
}
