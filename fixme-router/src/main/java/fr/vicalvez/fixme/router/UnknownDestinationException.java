package fr.vicalvez.fixme.router;

import fr.vicalvez.fixme.protocol.FixMessage;

final class UnknownDestinationException extends RuntimeException {

	private final FixMessage message;

	UnknownDestinationException(FixMessage message) {
		super("Unknown message destination");
		this.message = message;
	}

	FixMessage message() {
		return message;
	}
}
