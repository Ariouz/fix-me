package fr.vicalvez.fixme.market;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class MarketApplication {

	public static String MARKET_ID;
	private static final MarketService marketService = new MarketService();
	private static final MarketConnection[] connectionHolder = new MarketConnection[1];

	private MarketApplication() {
	}

	public static void main(String[] args) {
		try (MarketConnection connection = new MarketConnection(
				"localhost",
				MarketApplication::setMarketId,
				marketService::handleMessage,
				MarketApplication::onConnectionError
		)) {
			connectionHolder[0] = connection;
			marketService.setConnection(connection);
			connection.connect();
			connection.run();
		} catch (IOException exception) {
			throw new IllegalStateException("Market error: ", exception);
		}
	}

	private static void onConnectionError(Exception exception) {
		System.out.println("[MARKET] Router unavailable on localhost:5001. Start the Router before the Market.");
	}

	private static void setMarketId(String id) {
		MARKET_ID = id;
		marketService.initInstruments();
		System.out.println("Assigned Market Id: " + MARKET_ID);
		Thread console = new Thread(() -> readCommands(connectionHolder[0]), "market-console");
		console.setDaemon(true);
		console.start();
	}

	private static void readCommands(MarketConnection connection) {
		System.out.println("Enter LIST to display stocks or quit to exit.");
		try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
			String line;
			while ((line = input.readLine()) != null) {
				if (line.equalsIgnoreCase("quit")) {
					connection.close();
					return;
				}
				if (line.equalsIgnoreCase("list")) {
					marketService.listStocks().forEach(stock ->
							System.out.println(stock.symbol() + ": " + stock.quantity())
					);
				} else {
					System.out.println("Unknown command. Use LIST or quit.");
				}
			}
		} catch (IOException exception) {
			System.err.println("Unable to read Market commands: " + exception.getMessage());
		}
	}

}
