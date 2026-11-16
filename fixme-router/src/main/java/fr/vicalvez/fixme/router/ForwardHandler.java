package fr.vicalvez.fixme.router;

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
				+ " -> " + context.destination().type() + " " + context.destination().id());
	}
}
