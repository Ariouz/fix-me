package fr.vicalvez.fixme.broker;

import java.io.IOException;

public class BrokerApplication {

	public static String BROKER_ID;
	private static final BrokerService marketService = new BrokerService();

	private BrokerApplication() {
	}

	public static void main(String[] args) {
		try (BrokerConnection connection = new BrokerConnection(
				"localhost",
				BrokerApplication::setMarketId,
				marketService::handleMessage,
				Throwable::printStackTrace
		)) {
			marketService.setConnection(connection);
			connection.connect();
			connection.run();
		} catch (IOException exception) {
			throw new IllegalStateException("Market error: ", exception);
		}
	}

	private static void setMarketId(String id) {
		BROKER_ID = id;
		System.out.println("Assigned Broker Id: " + BROKER_ID);
	}

}
