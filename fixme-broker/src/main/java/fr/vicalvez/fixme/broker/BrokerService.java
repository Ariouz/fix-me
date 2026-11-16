package fr.vicalvez.fixme.broker;

import fr.vicalvez.fixme.broker.model.BrokerOrder;
import fr.vicalvez.fixme.broker.model.OrderSide;
import fr.vicalvez.fixme.broker.model.OrderResult;
import fr.vicalvez.fixme.broker.model.OrderStatus;
import fr.vicalvez.fixme.protocol.FixMessage;
import fr.vicalvez.fixme.protocol.FixMessageType;
import fr.vicalvez.fixme.protocol.FixTag;
import fr.vicalvez.fixme.persistence.TransactionRecord;
import fr.vicalvez.fixme.persistence.TransactionRepository;
import fr.vicalvez.fixme.persistence.TransactionState;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class BrokerService {

	private BrokerConnection connection;
	private String brokerId;
	private final String brokerInstance = System.getProperty("fixme.broker.instance", "default-broker");
	private final TransactionRepository transactions;

	public BrokerService() {
		this(new TransactionRepository());
	}

	BrokerService(TransactionRepository transactions) {
		this.transactions = transactions;
	}

	public void handleMessage(FixMessage message) {
		try {
			OrderResult result = toOrderResult(message);
			transactions.markFinal(result.orderId(), result.status() == OrderStatus.EXECUTED
					? TransactionState.EXECUTED : TransactionState.REJECTED);
			System.out.println("Received " + result.status() + " for order " + result.orderId());
		} catch (Exception e) {
			System.out.println("Response error: " + e.getMessage());
		}
	}


	public void sendOrder(BrokerOrder order) {
		if (connection == null || brokerId == null) {
			throw new IllegalStateException("Broker is not connected to the Router");
		}
		transactions.createOrder(toTransaction(order));
		connection.send(toFixMessage(order));
	}

	/** Returns unfinished orders to resend after a Broker restart. */
	public List<BrokerOrder> pendingOrders() {
		if (brokerId == null) {
			throw new IllegalStateException("Broker ID has not been assigned");
		}
		transactions.recoverBroker(brokerInstance, brokerId);
		return transactions.pendingOrders(brokerInstance).stream().map(this::toBrokerOrder).toList();
	}

	public void resendPendingOrders() {
		recoverUndeliveredResponses();
		for (BrokerOrder order : pendingOrders()) {
			connection.send(toFixMessage(order));
			System.out.println("Restored pending order " + order.orderId());
		}
	}

	/** Restores Market responses persisted while this Broker was disconnected. */
	public void recoverUndeliveredResponses() {
		for (TransactionRecord transaction : transactions.undeliveredFinalOrders(brokerInstance)) {
			OrderStatus status = transaction.state() == TransactionState.EXECUTED
					? OrderStatus.EXECUTED : OrderStatus.REJECTED;
			System.out.println("Recovered " + status + " for order " + transaction.orderId());
			transactions.markResponseDelivered(transaction.orderId());
		}
	}

	public FixMessage toFixMessage(BrokerOrder order) {
		if (brokerId == null) {
			throw new IllegalStateException("Broker ID has not been assigned");
		}
		return new FixMessage(
				brokerId,
				order.marketId(),
				getFIXMessageType(order.side()),
				Map.of(
						FixTag.ORDER_ID, order.orderId(),
						FixTag.SYMBOL, order.instrument(),
						FixTag.ORDER_QTY, String.valueOf(order.quantity()),
						FixTag.MARKET, order.marketId(),
						FixTag.PRICE, String.valueOf(order.price())
				)
		);
	}

	public OrderResult toOrderResult(FixMessage message) {
		if (brokerId == null || !brokerId.equals(message.targetId())) {
			throw new IllegalArgumentException("Response is not addressed to this Broker");
		}
		return new OrderResult(message.getField(FixTag.ORDER_ID), getMessageOrderStatus(message.type()));
	}

	public FixMessageType getFIXMessageType(OrderSide side) {
		return side == OrderSide.BUY ? FixMessageType.BUY : FixMessageType.SELL;
	}

	public OrderStatus getMessageOrderStatus(FixMessageType type) {
		if (type == FixMessageType.EXECUTED) return OrderStatus.EXECUTED;
		else if (type == FixMessageType.REJECTED) return OrderStatus.REJECTED;
		else throw new IllegalArgumentException("Received an unexpected type for order status: " + type);
	}

	public void setConnection(BrokerConnection connection) {
		this.connection = connection;
	}

	public void setBrokerId(String brokerId) {
		this.brokerId = brokerId;
	}

	private TransactionRecord toTransaction(BrokerOrder order) {
		return new TransactionRecord(order.orderId(), brokerInstance, brokerId, order.marketId(), order.side().name(),
				order.instrument(), order.quantity(), order.price(), TransactionState.PENDING);
	}

	private BrokerOrder toBrokerOrder(TransactionRecord record) {
		return new BrokerOrder(record.orderId(), record.marketId(), OrderSide.valueOf(record.side()),
				record.instrument(), record.quantity(), record.price());
	}

}
