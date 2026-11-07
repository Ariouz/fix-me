package fr.vicalvez.fixme.router;

import java.io.IOException;

public final class RouterApplication {

	private RouterApplication() {
	}

	public static void main(String[] args) {
		try (RouterServer server = new RouterServer()) {
			server.run();
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to start router", exception);
		}
	}
}
