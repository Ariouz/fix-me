package fr.vicalvez.fixme.router;

import fr.vicalvez.fixme.protocol.FixMessageFramer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

final class RouterServer implements AutoCloseable {

	static final int BROKER_PORT = 5000;
	static final int MARKET_PORT = 5001;

	private final Selector selector = Selector.open();
	private final ExecutorService workers = Executors.newFixedThreadPool(
			Math.max(2, Runtime.getRuntime().availableProcessors())
	);
	private final Map<String, ClientConnection> routingTable = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<ClientConnection> pendingWrites = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<ClientConnection> pendingClosures = new ConcurrentLinkedQueue<>();
	private final RouterHandler processingChain;

	private volatile boolean running = true;

	RouterServer() throws IOException {
		processingChain = new ValidationHandler();
		processingChain.then(new DestinationHandler(routingTable::get))
				.then(new ForwardHandler(this::requestWrite));
	}

	void run() throws IOException {
		bind(BROKER_PORT, ConnectionType.BROKER);
		bind(MARKET_PORT, ConnectionType.MARKET);
		while (running) {
			applyPendingChanges();
			selector.select();
			applyPendingChanges();

			Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
			while (keys.hasNext()) {
				SelectionKey key = keys.next();
				keys.remove();
				if (!key.isValid()) {
					continue;
				}
				try {
					if (key.isAcceptable()) {
						acceptClients(key);
					}
					if (key.isReadable()) {
						read((ClientConnection) key.attachment());
					}
					if (key.isValid() && key.isWritable()) {
						write((ClientConnection) key.attachment());
					}
				} catch (IOException | RuntimeException exception) {
					if (key.attachment() instanceof ClientConnection connection) {
						requestClose(connection);
					}
				}
			}
		}
	}

	private void bind(int port, ConnectionType type) throws IOException {
		ServerSocketChannel server = ServerSocketChannel.open();
		server.configureBlocking(false);
		server.bind(new InetSocketAddress(port));
		server.register(selector, SelectionKey.OP_ACCEPT, type);
	}

	private void acceptClients(SelectionKey key) throws IOException {
		ServerSocketChannel server = (ServerSocketChannel) key.channel();
		ConnectionType type = (ConnectionType) key.attachment();
		SocketChannel channel;
		while ((channel = server.accept()) != null) {
			channel.configureBlocking(false);
			ClientConnection connection = register(channel, type);
			connection.enqueue(ByteBuffer.wrap((connection.id() + "\n").getBytes(StandardCharsets.US_ASCII)));
			requestWrite(connection);
			System.out.println("[ROUTER] " + type + " " + connection.id() + " connected");
		}
	}

	private ClientConnection register(SocketChannel channel, ConnectionType type) throws IOException {
		String id;
		do {
			id = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
		} while (routingTable.containsKey(id));

		ClientConnection connection = new ClientConnection(channel, id, type, workers);
		routingTable.put(id, connection);
		channel.register(selector, SelectionKey.OP_READ, connection);
		return connection;
	}

	private void read(ClientConnection connection) throws IOException {
		ByteBuffer buffer = connection.readBuffer();
		int read = connection.channel().read(buffer);
		if (read == -1) {
			requestClose(connection);
			return;
		}
		if (read == 0) {
			return;
		}

		buffer.flip();
		List<ByteBuffer> messages;
		try {
			messages = connection.framer().accept(buffer);
		} finally {
			buffer.clear();
		}
		for (ByteBuffer message : messages) {
			connection.messageExecutor().execute(() -> process(connection, message));
		}
	}

	private void process(ClientConnection source, ByteBuffer rawMessage) {
		try {
			processingChain.handle(new RouterMessageContext(source, rawMessage));
		} catch (RuntimeException exception) {
			requestClose(source);
		}
	}

	private void write(ClientConnection connection) throws IOException {
		if (connection.flush()) {
			SelectionKey key = connection.channel().keyFor(selector);
			if (key != null && key.isValid()) {
				key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
			}
		}
	}

	private void requestWrite(ClientConnection connection) {
		pendingWrites.offer(connection);
		selector.wakeup();
	}

	private void requestClose(ClientConnection connection) {
		pendingClosures.offer(connection);
		selector.wakeup();
	}

	private void applyPendingChanges() {
		ClientConnection connection;
		while ((connection = pendingClosures.poll()) != null) {
			closeConnection(connection);
		}
		while ((connection = pendingWrites.poll()) != null) {
			SelectionKey key = connection.channel().keyFor(selector);
			if (key != null && key.isValid()) {
				key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
			}
		}
	}

	private void closeConnection(ClientConnection connection) {
		if (!routingTable.remove(connection.id(), connection)) {
			return;
		}
		System.out.println("[ROUTER] " + connection.type() + " " + connection.id() + " disconnected");
		SelectionKey key = connection.channel().keyFor(selector);
		if (key != null) {
			key.cancel();
		}
		try {
			connection.channel().close();
		} catch (IOException ignored) {
			// The connection is already unusable.
		}
	}

	@Override
	public void close() throws IOException {
		running = false;
		workers.shutdownNow();
		for (SelectionKey key : Set.copyOf(selector.keys())) {
			if (key.attachment() instanceof ClientConnection connection) {
				closeConnection(connection);
			} else {
				key.channel().close();
			}
		}
		selector.close();
	}
}
