package fr.vicalvez.fixme.router;

import fr.vicalvez.fixme.protocol.FixMessage;

import java.nio.ByteBuffer;

final class RouterMessageContext {

	private final ClientConnection source;
	private final ByteBuffer rawMessage;
	private FixMessage message;
	private ClientConnection destination;

	RouterMessageContext(ClientConnection source, ByteBuffer rawMessage) {
		this.source = source;
		this.rawMessage = rawMessage.asReadOnlyBuffer();
	}

	ClientConnection source() {
		return source;
	}

	ByteBuffer rawMessage() {
		return rawMessage.asReadOnlyBuffer();
	}

	FixMessage message() {
		return message;
	}

	void message(FixMessage message) {
		this.message = message;
	}

	ClientConnection destination() {
		return destination;
	}

	void destination(ClientConnection destination) {
		this.destination = destination;
	}
}
