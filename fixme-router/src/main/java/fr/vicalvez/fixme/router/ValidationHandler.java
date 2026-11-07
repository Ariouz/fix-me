package fr.vicalvez.fixme.router;

import fr.vicalvez.fixme.protocol.FixCodec;
import fr.vicalvez.fixme.protocol.FixMessage;

final class ValidationHandler extends RouterHandler {

	private final FixCodec codec = new FixCodec();

	@Override
	protected void process(RouterMessageContext context) {
		FixMessage message = codec.decode(context.rawMessage());
		if (!context.source().id().equals(message.senderId())) {
			throw new IllegalArgumentException("Message sender does not match the connected client");
		}
		context.message(message);
	}
}
