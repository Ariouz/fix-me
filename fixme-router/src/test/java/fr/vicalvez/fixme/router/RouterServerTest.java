package fr.vicalvez.fixme.router;

import fr.vicalvez.fixme.protocol.FixCodec;
import fr.vicalvez.fixme.protocol.FixMessage;
import fr.vicalvez.fixme.protocol.FixMessageFramer;
import fr.vicalvez.fixme.protocol.FixMessageType;
import fr.vicalvez.fixme.protocol.FixTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouterServerTest {

	@Test
	void forwardsOrderAndExecutionReportBetweenBrokerAndMarket() throws Exception {
		int brokerPort = availablePort();
		int marketPort = availablePort();
		RouterServer router = new RouterServer(brokerPort, marketPort);
		Thread eventLoop = new Thread(() -> {
			try {
				router.run();
			} catch (IOException ignored) {
				// Closing the selector ends the test event loop.
			}
		}, "router-integration-test");
		eventLoop.start();

		try (Socket market = socket(marketPort); Socket broker = socket(brokerPort)) {
			String marketId = greeting(market);
			String brokerId = greeting(broker);
			FixCodec codec = new FixCodec();
			FixMessage order = new FixMessage(brokerId, marketId, FixMessageType.BUY, Map.of(
					FixTag.ORDER_ID, "order-1", FixTag.SYMBOL, "AAPL", FixTag.ORDER_QTY, "5",
					FixTag.MARKET, marketId, FixTag.PRICE, "42.50"
			));
			FixMessage orderForMissingMarket = new FixMessage(brokerId, "999999", FixMessageType.BUY, Map.of(
					FixTag.ORDER_ID, "missing-market-order", FixTag.SYMBOL, "AAPL", FixTag.ORDER_QTY, "1",
					FixTag.MARKET, "999999", FixTag.PRICE, "42.50"
			));

			send(broker, codec.encode(orderForMissingMarket));
			FixMessage rejection = new FixMessage("000000", brokerId, FixMessageType.REJECTED,
					Map.of(FixTag.ORDER_ID, "missing-market-order"));
			assertEquals(rejection, receive(broker, codec));

			send(broker, codec.encode(order));
			assertEquals(order, receive(market, codec));

			FixMessage execution = new FixMessage(marketId, brokerId, FixMessageType.EXECUTED,
					Map.of(FixTag.ORDER_ID, "order-1"));
			send(market, codec.encode(execution));
			assertEquals(execution, receive(broker, codec));
		} finally {
			router.close();
			eventLoop.join(2_000);
		}
	}

	private static int availablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static Socket socket(int port) throws IOException {
		IOException lastFailure = null;
		for (int attempt = 0; attempt < 20; attempt++) {
			try {
				Socket socket = new Socket("localhost", port);
				socket.setSoTimeout(3_000);
				return socket;
			} catch (IOException exception) {
				lastFailure = exception;
				try {
					Thread.sleep(50);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for Router", interrupted);
				}
			}
		}
		throw lastFailure;
	}

	private static String greeting(Socket socket) throws IOException {
		StringBuilder id = new StringBuilder(6);
		InputStream input = socket.getInputStream();
		int value;
		while ((value = input.read()) != '\n') {
			id.append((char) value);
		}
		return id.toString();
	}

	private static void send(Socket socket, ByteBuffer message) throws IOException {
		OutputStream output = socket.getOutputStream();
		output.write(message.array(), message.position(), message.remaining());
		output.flush();
	}

	private static FixMessage receive(Socket socket, FixCodec codec) throws IOException {
		FixMessageFramer framer = new FixMessageFramer();
		InputStream input = socket.getInputStream();
		byte[] buffer = new byte[256];
		while (true) {
			int read = input.read(buffer);
			List<ByteBuffer> messages = framer.accept(ByteBuffer.wrap(buffer, 0, read));
			if (!messages.isEmpty()) {
				return codec.decode(messages.get(0));
			}
		}
	}
}
