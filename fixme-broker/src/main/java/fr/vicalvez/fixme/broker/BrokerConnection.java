package fr.vicalvez.fixme.broker;

import fr.vicalvez.fixme.protocol.FixCodec;
import fr.vicalvez.fixme.protocol.FixMessage;
import fr.vicalvez.fixme.protocol.FixMessageFramer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Non-blocking connection from one Broker to the Router. */
public final class BrokerConnection implements AutoCloseable {

	public static final int ROUTER_PORT = 5000;
	private final String host;
	private final Consumer<String> assignedIdHandler;
	private final Consumer<FixMessage> messageHandler;
	private final Consumer<Exception> errorHandler;
	private final Selector selector;
	private final FixCodec codec = new FixCodec();
	private final FixMessageFramer framer = new FixMessageFramer();
	private final ByteBuffer readBuffer = ByteBuffer.allocate(8 * 1024);
	private final ByteArrayOutputStream handshake = new ByteArrayOutputStream(7);
	private final ConcurrentLinkedQueue<ByteBuffer> outgoing = new ConcurrentLinkedQueue<>();
	private final ExecutorService messageExecutor = Executors.newSingleThreadExecutor();

	private SocketChannel channel;
	private SelectionKey selectionKey;
	private volatile boolean running = true;
	private volatile String assignedId;

	public BrokerConnection(
			String host,
			Consumer<String> assignedIdHandler,
			Consumer<FixMessage> messageHandler,
			Consumer<Exception> errorHandler
	) throws IOException {
		this.host = host;
		this.assignedIdHandler = assignedIdHandler;
		this.messageHandler = messageHandler;
		this.errorHandler = errorHandler;
		this.selector = Selector.open();
	}

	public void connect() throws IOException {
		if (channel != null) {
			throw new IllegalStateException("Broker is already connected");
		}
		channel = SocketChannel.open();
		channel.configureBlocking(false);
		channel.connect(new InetSocketAddress(host, ROUTER_PORT));
		selectionKey = channel.register(selector, SelectionKey.OP_CONNECT);
	}

	public void run() {
		try {
			while (running) {
				updateWriteInterest();
				selector.select();
				updateWriteInterest();
				Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
				while (keys.hasNext()) {
					SelectionKey key = keys.next();
					keys.remove();
					if (!key.isValid()) {
						continue;
					}
					if (key.isConnectable()) {
						finishConnection();
					}
					if (key.isValid() && key.isReadable()) {
						read();
					}
					if (key.isValid() && key.isWritable()) {
						write();
					}
				}
			}
		} catch (IOException | RuntimeException exception) {
			report(exception);
		} finally {
			closeQuietly();
		}
	}

	public void send(FixMessage message) {
		outgoing.offer(codec.encode(message));
		selector.wakeup();
	}

	public String assignedId() {
		return assignedId;
	}

	private void finishConnection() throws IOException {
		if (channel.finishConnect()) {
			selectionKey.interestOps(SelectionKey.OP_READ);
		}
	}

	private void read() throws IOException {
		int read = channel.read(readBuffer);
		if (read < 0) {
			running = false;
			return;
		}
		if (read == 0) {
			return;
		}
		readBuffer.flip();
		try {
			readHandshake(readBuffer);
			if (assignedId != null && readBuffer.hasRemaining()) {
				dispatch(readBuffer);
			}
		} finally {
			readBuffer.clear();
		}
	}

	private void readHandshake(ByteBuffer received) {
		if (assignedId != null) {
			return;
		}
		while (received.hasRemaining()) {
			byte value = received.get();
			if (value == '\n') {
				String id = handshake.toString(StandardCharsets.US_ASCII);
				handshake.reset();
				if (!id.matches("\\d{6}")) {
					throw new IllegalArgumentException("Router returned an invalid client ID");
				}
				assignedId = id;
				assignedIdHandler.accept(id);
				return;
			}
			handshake.write(value);
			if (handshake.size() > 6) {
				throw new IllegalArgumentException("Router ID is too long");
			}
		}
	}

	private void dispatch(ByteBuffer received) {
		List<ByteBuffer> messages = framer.accept(received);
		for (ByteBuffer rawMessage : messages) {
			messageExecutor.execute(() -> {
				try {
					messageHandler.accept(codec.decode(rawMessage));
				} catch (RuntimeException exception) {
					report(exception);
				}
			});
		}
	}

	private void write() throws IOException {
		while (true) {
			ByteBuffer message = outgoing.peek();
			if (message == null) {
				updateWriteInterest();
				return;
			}
			channel.write(message);
			if (message.hasRemaining()) {
				return;
			}
			outgoing.poll();
		}
	}

	private void updateWriteInterest() {
		if (selectionKey == null || !selectionKey.isValid() || !channel.isConnected()) {
			return;
		}
		int interests = SelectionKey.OP_READ;
		if (!outgoing.isEmpty()) {
			interests |= SelectionKey.OP_WRITE;
		}
		selectionKey.interestOps(interests);
	}

	private void report(Exception exception) {
		try {
			errorHandler.accept(exception);
		} catch (RuntimeException ignored) {
			// A callback must not keep a broken network loop alive.
		}
	}

	@Override
	public void close() throws IOException {
		running = false;
		messageExecutor.shutdownNow();
		if (channel != null) {
			channel.close();
		}
		selector.close();
	}

	private void closeQuietly() {
		try {
			close();
		} catch (IOException exception) {
			report(exception);
		}
	}
}
