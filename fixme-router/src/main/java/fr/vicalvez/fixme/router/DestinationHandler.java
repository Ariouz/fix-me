package fr.vicalvez.fixme.router;

import java.util.function.Function;

final class DestinationHandler extends RouterHandler {

	private final Function<String, ClientConnection> connectionLookup;

	DestinationHandler(Function<String, ClientConnection> connectionLookup) {
		this.connectionLookup = connectionLookup;
	}

	@Override
	protected void process(RouterMessageContext context) {
		ClientConnection destination = connectionLookup.apply(context.message().targetId());
		if (destination == null) {
			throw new IllegalArgumentException("Unknown message destination");
		}
		context.destination(destination);
	}
}
