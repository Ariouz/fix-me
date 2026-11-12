package fr.vicalvez.fixme.broker;

import fr.vicalvez.fixme.broker.model.BrokerOrder;
import fr.vicalvez.fixme.broker.model.OrderSide;
import fr.vicalvez.fixme.broker.model.OrderStatus;
import fr.vicalvez.fixme.protocol.FixMessage;
import fr.vicalvez.fixme.protocol.FixMessageType;
import fr.vicalvez.fixme.protocol.FixTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrokerServiceTest {

	private BrokerService service;

	@BeforeEach
	void setUp() {
		service = new BrokerService();
		service.setBrokerId("123456");
	}

	@Test
	void convertsOrderToFixMessageWithOrderId() {
		BrokerOrder order = new BrokerOrder("order-1", "654321", OrderSide.BUY, "AAPL", 5, new BigDecimal("42.50"));

		FixMessage message = service.toFixMessage(order);

		assertEquals("123456", message.senderId());
		assertEquals("654321", message.targetId());
		assertEquals(FixMessageType.BUY, message.type());
		assertEquals("order-1", message.getField(FixTag.ORDER_ID));
		assertEquals("654321", message.getField(FixTag.MARKET));
	}

	@Test
	void convertsExecutionResponseToOrderResult() {
		FixMessage response = new FixMessage(
				"654321", "123456", FixMessageType.EXECUTED, Map.of(FixTag.ORDER_ID, "order-1")
		);

		assertEquals("order-1", service.toOrderResult(response).orderId());
		assertEquals(OrderStatus.EXECUTED, service.toOrderResult(response).status());
	}
}
