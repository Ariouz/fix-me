package fr.vicalvez.fixme.market;

import fr.vicalvez.fixme.market.model.ExecutionStatus;
import fr.vicalvez.fixme.market.model.OrderSide;
import fr.vicalvez.fixme.market.model.TradeOrder;
import fr.vicalvez.fixme.protocol.FixMessage;
import fr.vicalvez.fixme.protocol.FixMessageType;
import fr.vicalvez.fixme.protocol.FixTag;
import fr.vicalvez.fixme.persistence.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketServiceTest {

	@TempDir
	Path temporaryDirectory;

	private MarketService service;

	@BeforeEach
	void setUp() {
		service = new MarketService(new TransactionRepository(temporaryDirectory.resolve("market.db")));
		service.initInstruments();
	}

	@Test
	void buyReducesAvailableStock() {
		TradeOrder order = order(OrderSide.BUY, "AAPL", 5);

		assertEquals(ExecutionStatus.EXECUTED, service.buy(order));
		assertEquals(10, service.availableQuantity("AAPL"));
	}

	@Test
	void buyBeyondAvailableStockIsRejectedWithoutMutation() {
		TradeOrder order = order(OrderSide.BUY, "AAPL", 16);

		assertEquals(ExecutionStatus.REJECTED, service.buy(order));
		assertEquals(15, service.availableQuantity("AAPL"));
	}

	@Test
	void sellIncreasesAvailableStock() {
		TradeOrder order = order(OrderSide.SELL, "META", 4);

		assertEquals(ExecutionStatus.EXECUTED, service.sell(order));
		assertEquals(5, service.availableQuantity("META"));
	}

	@Test
	void unknownInstrumentIsRejected() {
		assertEquals(ExecutionStatus.REJECTED, service.buy(order(OrderSide.BUY, "UNKNOWN", 1)));
	}

	@Test
	void executionResponseKeepsTheOrderId() {
		MarketApplication.MARKET_ID = "654321";

		FixMessage response = service.toStatusMessage("123456", "order-1", ExecutionStatus.EXECUTED);

		assertEquals(FixMessageType.EXECUTED, response.type());
		assertEquals("order-1", response.getField(FixTag.ORDER_ID));
	}

	private static TradeOrder order(OrderSide side, String symbol, long quantity) {
		return new TradeOrder("order-1", "123456", "654321", side, symbol, quantity, new BigDecimal("42.50"));
	}
}
