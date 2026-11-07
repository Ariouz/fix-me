package fr.vicalvez.fixme.router;

import fr.vicalvez.fixme.protocol.FixMessageFramer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

final class ClientConnection {

	private static final int READ_BUFFER_SIZE = 8 * 1024;

	private final SocketChannel channel;
	private final String id;
	private final ConnectionType type;
	private final FixMessageFramer framer = new FixMessageFramer();
	private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
	private final Executor messageExecutor;
	private final ConcurrentLinkedQueue<ByteBuffer> outgoing = new ConcurrentLinkedQueue<>();

	ClientConnection(SocketChannel channel, String id, ConnectionType type, Executor workerPool) {
		this.channel = channel;
		this.id = id;
		this.type = type;
		this.messageExecutor = new SerialExecutor(workerPool);
	}

	SocketChannel channel() {
		return channel;
	}

	String id() {
		return id;
	}

	ConnectionType type() {
		return type;
	}

	FixMessageFramer framer() {
		return framer;
	}

	ByteBuffer readBuffer() {
		return readBuffer;
	}

	Executor messageExecutor() {
		return messageExecutor;
	}

	void enqueue(ByteBuffer message) {
		ByteBuffer copy = ByteBuffer.allocate(message.remaining());
		copy.put(message.duplicate()).flip();
		outgoing.offer(copy);
	}

	boolean flush() throws IOException {
		while (true) {
			ByteBuffer next = outgoing.peek();
			if (next == null) {
				return true;
			}
			channel.write(next);
			if (next.hasRemaining()) {
				return false;
			}
			outgoing.poll();
		}
	}
}
