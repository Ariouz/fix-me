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
	}

	@Test
	void recoveryReturnsAbandonedProcessingOrderToPending() {
		TransactionRepository repository = new TransactionRepository(temporaryDirectory.resolve("recovery.db"));
		repository.createOrder(order("order-2"));
		repository.recoverMarket("654321");

		repository.recoverBroker("broker-a", "654322");
		assertEquals("654322", repository.pendingOrders("broker-a").get(0).brokerId());
	}

	private static TransactionRecord order(String orderId) {
		return new TransactionRecord(orderId, "broker-a", "123456", "654321", "BUY", "AAPL", 5,
				new BigDecimal("42.50"), TransactionState.PENDING);
	}
}
