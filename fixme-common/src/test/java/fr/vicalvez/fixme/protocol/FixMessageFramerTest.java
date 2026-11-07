package fr.vicalvez.fixme.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixMessageFramerTest {

	private final FixCodec codec = new FixCodec();

	@Test
	void retainsPartialTcpReadUntilMessageIsComplete() {
		ByteBuffer encoded = codec.encode(FixCodecTest.buyOrder("123456", "654321", "ACME"));
		byte[] bytes = encoded.array();
		FixMessageFramer framer = new FixMessageFramer();

		assertTrue(framer.accept(ByteBuffer.wrap(bytes, 0, 12)).isEmpty());
		List<ByteBuffer> messages = framer.accept(ByteBuffer.wrap(bytes, 12, bytes.length - 12));

		assertEquals(1, messages.size());
		assertEquals(FixCodecTest.buyOrder("123456", "654321", "ACME"), codec.decode(messages.get(0)));
	}

	@Test
	void extractsMultipleMessagesFromOneTcpRead() {
		FixMessage first = FixCodecTest.buyOrder("123456", "654321", "ACME");
		FixMessage second = FixCodecTest.buyOrder("654321", "123456", "BETA");
		ByteBuffer firstBytes = codec.encode(first);
		ByteBuffer secondBytes = codec.encode(second);
		ByteBuffer combined = ByteBuffer.allocate(firstBytes.remaining() + secondBytes.remaining());
		combined.put(firstBytes).put(secondBytes).flip();

		List<ByteBuffer> messages = new FixMessageFramer().accept(combined);

		assertEquals(List.of(first, second), messages.stream().map(codec::decode).toList());
	}

	@Test
	void retainsSecondMessageWhenItArrivesAcrossTwoReads() {
		FixMessage first = FixCodecTest.buyOrder("123456", "654321", "ACME");
		FixMessage second = FixCodecTest.buyOrder("654321", "123456", "BETA");
		byte[] firstBytes = codec.encode(first).array();
		byte[] secondBytes = codec.encode(second).array();
		FixMessageFramer framer = new FixMessageFramer();
		ByteBuffer firstRead = ByteBuffer.allocate(firstBytes.length + 10);
		firstRead.put(firstBytes).put(secondBytes, 0, 10).flip();

		List<ByteBuffer> firstResult = framer.accept(firstRead);
		List<ByteBuffer> secondResult = framer.accept(ByteBuffer.wrap(secondBytes, 10, secondBytes.length - 10));

		assertEquals(List.of(first), firstResult.stream().map(codec::decode).toList());
		assertEquals(List.of(second), secondResult.stream().map(codec::decode).toList());
	}
}
