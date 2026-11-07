package fr.vicalvez.fixme.router;

abstract class RouterHandler {

	private RouterHandler next;

	final RouterHandler then(RouterHandler next) {
		this.next = next;
		return next;
	}

	final void handle(RouterMessageContext context) {
		process(context);
		if (next != null) {
			next.handle(context);
		}
	}

	protected abstract void process(RouterMessageContext context);
}
