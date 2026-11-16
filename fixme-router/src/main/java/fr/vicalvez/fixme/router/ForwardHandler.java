package fr.vicalvez.fixme.router;

import fr.vicalvez.fixme.protocol.FixCodec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class ForwardHandler extends RouterHandler {

	private final Consumer<ClientConnection> writeNotifier;

	ForwardHandler(Consumer<ClientConnection> writeNotifier) {
		this.writeNotifier = writeNotifier;
	}

	@Override
	protected void process(RouterMessageContext context) {
		context.destination().enqueue(context.rawMessage());
		writeNotifier.accept(context.destination());
		System.out.println("[ROUTER] " + context.source().type() + " " + context.source().id()
				+ " -> " + context.destination().type() + " " + context.destination().id()
				+ " | " + display(context.rawMessage()));
	}

	private static String display(ByteBuffer rawMessage) {
		byte[] bytes = new byte[rawMessage.remaining()];
		rawMessage.get(bytes);
		return new String(bytes, StandardCharsets.US_ASCII).replace((char) FixCodec.SOH, '|');
	}
}
