package fr.vicalvez.fixme.broker;

import fr.vicalvez.fixme.broker.model.BrokerOrder;
import fr.vicalvez.fixme.broker.model.OrderSide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;

public class BrokerApplication {

	public static String BROKER_ID;
	private static final BrokerService brokerService = new BrokerService();

	private BrokerApplication() {
	}

	public static void main(String[] args) {
		try (BrokerConnection connection = new BrokerConnection(
				"localhost",
				BrokerApplication::onAssignedId,
				brokerService::handleMessage,
				Throwable::printStackTrace
		)) {
			connectionHolder[0] = connection;
			brokerService.setConnection(connection);
			connection.connect();
			connection.run();
		} catch (IOException exception) {
			throw new IllegalStateException("Market error: ", exception);
		}
	}

	private static final BrokerConnection[] connectionHolder = new BrokerConnection[1];

	private static void onAssignedId(String id) {
		BROKER_ID = id;
		brokerService.setBrokerId(id);
		System.out.println("Assigned Broker Id: " + BROKER_ID);
		Thread console = new Thread(() -> readOrders(connectionHolder[0]), "broker-console");
		console.setDaemon(true);
		console.start();
	}

	private static void readOrders(BrokerConnection connection) {
		System.out.println("Enter: BUY|SELL <market-id> <instrument> <quantity> <price>; type quit to exit.");
		try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
			brokerService.resendPendingOrders();
			String line;
			while ((line = input.readLine()) != null && !line.equalsIgnoreCase("quit")) {
				try {
					String[] values = line.trim().split("\\s+");
					if (values.length != 5) {
						throw new IllegalArgumentException("Expected 5 values");
					}
					BrokerOrder order = new BrokerOrder(
							values[1], OrderSide.valueOf(values[0].toUpperCase()), values[2],
							Long.parseLong(values[3]), new BigDecimal(values[4])
					);
					brokerService.sendOrder(order);
					System.out.println("Sent order " + order.orderId());
				} catch (IllegalArgumentException exception) {
					System.out.println("Invalid order: " + exception.getMessage());
				}
			}
			connection.close();
		} catch (IOException exception) {
			System.err.println("Unable to read orders: " + exception.getMessage());
		}
	}

}
