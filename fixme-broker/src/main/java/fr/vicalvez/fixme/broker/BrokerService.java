package fr.vicalvez.fixme.broker;

import fr.vicalvez.fixme.broker.model.BrokerOrder;
import fr.vicalvez.fixme.broker.model.OrderSide;
import fr.vicalvez.fixme.broker.model.OrderResult;
import fr.vicalvez.fixme.broker.model.OrderStatus;
import fr.vicalvez.fixme.protocol.FixMessage;
import fr.vicalvez.fixme.protocol.FixMessageType;
import fr.vicalvez.fixme.protocol.FixTag;

import java.util.Map;

public class BrokerService {

	private BrokerConnection connection;

	public void handleMessage(FixMessage message) {
		try {
			OrderStatus status = getMessageOrderStatus(message.type());
			OrderResult result = new OrderResult(message.getField(FixTag.ORDER_ID), status);
			System.out.println("Received " + result.status() + " for order " + result.orderId());
		} catch (Exception e) {
			System.out.println("Response error: " + e.getMessage());
		}
	}


	public void sendOrder(BrokerOrder order) {
		FixMessage message = new FixMessage(
				BrokerApplication.BROKER_ID,
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
		connection.send(message);
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

}
