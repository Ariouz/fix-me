package fr.vicalvez.fixme.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixCodecTest {

	private final FixCodec codec = new FixCodec();

	@Test
	void encodesAndDecodesBuyOrder() {
		FixMessage order = buyOrder("123456", "654321", "ACME");

		FixMessage decoded = codec.decode(codec.encode(order));

		assertEquals(order, decoded);
	}

	@Test
	void rejectsMessageWithTamperedChecksum() {
		ByteBuffer encoded = codec.encode(buyOrder("123456", "654321", "ACME"));
		byte[] bytes = encoded.array().clone();
		bytes[bytes.length - 2] = bytes[bytes.length - 2] == '0' ? (byte) '1' : (byte) '0';

		assertThrows(IllegalArgumentException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
	}

	@Test
	void rejectsOrderMissingMandatoryField() {
		FixMessage incomplete = new FixMessage(
				"123456", "654321", FixMessageType.BUY,
				Map.of(FixTag.SYMBOL, "ACME", FixTag.ORDER_QTY, "10", FixTag.MARKET, "XPAR")
		);

		assertThrows(IllegalArgumentException.class, () -> codec.encode(incomplete));
	}

	@Test
	void rejectsInvalidRouterId() {
		FixMessage invalidId = buyOrder("12345", "654321", "ACME");

		assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidId));
	}

	@Test
	void encodesAndDecodesExecutionReportWithOrderId() {
		FixMessage report = new FixMessage(
				"654321", "123456", FixMessageType.EXECUTED,
				Map.of(FixTag.ORDER_ID, "order-1")
		);

		assertEquals(report, codec.decode(codec.encode(report)));
	}

	static FixMessage buyOrder(String senderId, String targetId, String symbol) {
		return new FixMessage(senderId, targetId, FixMessageType.BUY, Map.of(
				FixTag.ORDER_ID, "order-1",
				FixTag.SYMBOL, symbol,
				FixTag.ORDER_QTY, "10",
				FixTag.MARKET, "XPAR",
				FixTag.PRICE, "42.50"
		));
	}
}
