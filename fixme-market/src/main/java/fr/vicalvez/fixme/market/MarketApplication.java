package fr.vicalvez.fixme.market;

import java.io.IOException;

public class MarketApplication {

	public static String MARKET_ID;
	private static final MarketService marketService = new MarketService();

	private MarketApplication() {
	}

	public static void main(String[] args) {
		try (MarketConnection connection = new MarketConnection(
				"localhost",
				MarketApplication::setMarketId,
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
		MARKET_ID = id;
		marketService.initInstruments();
		System.out.println("Assigned Market Id: " + MARKET_ID);
	}

}
