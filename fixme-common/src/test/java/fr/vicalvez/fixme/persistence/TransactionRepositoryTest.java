package fr.vicalvez.fixme.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionRepositoryTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void duplicateOrderIdIsRejectedAndInventoryIsUpdatedOnlyOnce() {
		TransactionRepository repository = new TransactionRepository(temporaryDirectory.resolve("transactions.db"));
		repository.ensureInstrument("654321", "AAPL", 10, new BigDecimal("42.50"));
		TransactionRecord order = order("order-1");
		repository.createOrder(order);

		assertThrows(DuplicateOrderIdException.class, () -> repository.createOrder(order));
		assertEquals(TransactionState.EXECUTED, repository.processOrder("order-1", "123456", "654321"));
		assertEquals(TransactionState.EXECUTED, repository.processOrder("order-1", "123456", "654321"));
		assertEquals(TransactionState.EXECUTED, repository.find("order-1").orElseThrow().state());
		assertEquals(5L, repository.availableQuantity("654321", "AAPL").orElseThrow());

		TransactionRecord tooLarge = order("order-2", 6);
		repository.createOrder(tooLarge);
		assertEquals(TransactionState.REJECTED, repository.processOrder("order-2", "123456", "654321"));
		assertEquals(5L, repository.availableQuantity("654321", "AAPL").orElseThrow());
	}

	@Test
	void recoveryReturnsAbandonedProcessingOrderToPending() {
		TransactionRepository repository = new TransactionRepository(temporaryDirectory.resolve("recovery.db"));
		repository.createOrder(order("order-2"));
		repository.recoverMarket("654321");

		repository.recoverBroker("broker-a", "654322");
		assertEquals("654322", repository.pendingOrders("broker-a").get(0).brokerId());
	}

	@Test
	void finalResponseRemainsAvailableUntilTheBrokerAcknowledgesIt() {
		TransactionRepository repository = new TransactionRepository(temporaryDirectory.resolve("failover.db"));
		repository.ensureInstrument("654321", "AAPL", 10, new BigDecimal("42.50"));
		repository.createOrder(order("order-3", 1));
		repository.processOrder("order-3", "123456", "654321");

		assertEquals(1, repository.undeliveredFinalOrders("broker-a").size());
		repository.markResponseDelivered("order-3");
		assertEquals(0, repository.undeliveredFinalOrders("broker-a").size());
	}

	private static TransactionRecord order(String orderId) {
		return order(orderId, 5);
	}

	private static TransactionRecord order(String orderId, long quantity) {
		return new TransactionRecord(orderId, "broker-a", "123456", "654321", "BUY", "AAPL", quantity,
				new BigDecimal("42.50"), TransactionState.PENDING);
	}
}
