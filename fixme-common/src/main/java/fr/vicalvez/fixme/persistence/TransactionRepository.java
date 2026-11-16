package fr.vicalvez.fixme.persistence;

import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite journal shared by Broker and Market.
 *
 * <p>By default the database is {@code fixme.db} beside the parent project POM.
 * Its path may be overridden with {@code -Dfixme.database.path=...}.</p>
 */
public final class TransactionRepository {

	private final String url;

	public TransactionRepository() {
		this(defaultDatabasePath());
	}

	public TransactionRepository(Path databasePath) {
		Path absolutePath = databasePath.toAbsolutePath();
		try {
			Path parent = absolutePath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create the SQLite database directory", exception);
		}
		this.url = "jdbc:sqlite:" + absolutePath;
		initialize();
	}

	private static Path defaultDatabasePath() {
		String configuredPath = System.getProperty("fixme.database.path");
		if (configuredPath != null && !configuredPath.isBlank()) {
			return Path.of(configuredPath);
		}
		Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (directory != null) {
			if (Files.isRegularFile(directory.resolve("pom.xml"))
					&& Files.isDirectory(directory.resolve("fixme-common"))) {
				return directory.resolve("fixme.db");
			}
			directory = directory.getParent();
		}
		return Path.of(System.getProperty("user.dir"), "fixme.db");
	}

	public void createOrder(TransactionRecord transaction) {
		String sql = "INSERT INTO transactions(order_id, broker_instance, broker_id, market_id, side, instrument, quantity, price, state) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, transaction.orderId());
			statement.setString(2, transaction.brokerInstance());
			statement.setString(3, transaction.brokerId());
			statement.setString(4, transaction.marketId());
			statement.setString(5, transaction.side());
			statement.setString(6, transaction.instrument());
			statement.setLong(7, transaction.quantity());
			statement.setString(8, transaction.price().toPlainString());
			statement.executeUpdate();
		} catch (SQLException exception) {
			if (exception.getMessage().contains("UNIQUE constraint failed")) {
				throw new DuplicateOrderIdException(transaction.orderId());
			}
			throw failure(exception);
		}
	}

	public List<TransactionRecord> pendingOrders(String brokerInstance) {
		String sql = "SELECT * FROM transactions WHERE broker_instance = ? AND state IN ('PENDING', 'PROCESSING') ORDER BY created_at";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, brokerInstance);
			return readAll(statement);
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	/** Returns final Market responses that could not be delivered to a Broker. */
	public List<TransactionRecord> undeliveredFinalOrders(String brokerInstance) {
		String sql = "SELECT * FROM transactions WHERE broker_instance = ? "
				+ "AND state IN ('EXECUTED', 'REJECTED') AND response_delivered = 0 ORDER BY updated_at";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, brokerInstance);
			return readAll(statement);
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	public Optional<TransactionRecord> find(String orderId) {
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM transactions WHERE order_id = ?")) {
			statement.setString(1, orderId);
			List<TransactionRecord> records = readAll(statement);
			return records.stream().findFirst();
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	public Optional<Long> availableQuantity(String marketId, String symbol) {
		try (Connection connection = open()) {
			return Optional.ofNullable(lookupInventoryQuantity(connection, marketId, symbol));
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	public List<InventoryRecord> inventory(String marketId) {
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
				"SELECT symbol, quantity, price FROM inventory WHERE market_id = ? ORDER BY symbol")) {
			statement.setString(1, marketId);
			try (ResultSet rows = statement.executeQuery()) {
				List<InventoryRecord> instruments = new ArrayList<>();
				while (rows.next()) {
					instruments.add(new InventoryRecord(rows.getString("symbol"), rows.getLong("quantity"),
							new BigDecimal(rows.getString("price"))));
				}
				return instruments;
			}
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	public void ensureInstrument(String marketId, String symbol, long quantity, BigDecimal price) {
		String sql = "INSERT OR IGNORE INTO inventory(market_id, symbol, quantity, price) VALUES (?, ?, ?, ?)";
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, marketId);
			statement.setString(2, symbol);
			statement.setLong(3, quantity);
			statement.setString(4, price.toPlainString());
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	/** Resets abandoned work after a Market process is restarted. */
	public void recoverMarket(String marketId) {
		update("UPDATE transactions SET state = 'PENDING', updated_at = CURRENT_TIMESTAMP WHERE market_id = ? AND state = 'PROCESSING'", marketId);
	}

	/** Associates unfinished orders with the new Router connection ID after a Broker restart. */
	public void recoverBroker(String brokerInstance, String brokerId) {
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
				"UPDATE transactions SET broker_id = ?, state = 'PENDING', updated_at = CURRENT_TIMESTAMP "
						+ "WHERE broker_instance = ? AND state IN ('PENDING', 'PROCESSING')")) {
			statement.setString(1, brokerId);
			statement.setString(2, brokerInstance);
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	/** Records the final response received by a Broker without touching inventory. */
	public void markFinal(String orderId, TransactionState state) {
		if (!state.isFinal()) {
			throw new IllegalArgumentException("Only a final state can be stored as a response");
		}
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
				"UPDATE transactions SET state = ?, response_delivered = 1, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?")) {
			statement.setString(1, state.name());
			statement.setString(2, orderId);
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	public void markResponseDelivered(String orderId) {
		update("UPDATE transactions SET response_delivered = 1, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?", orderId);
	}

	/** Applies a final Market response to the Broker portfolio exactly once. */
	public boolean applyBrokerResponse(String orderId, TransactionState state) {
		if (!state.isFinal()) {
			throw new IllegalArgumentException("Broker response must be final");
		}
		try (Connection connection = open()) {
			begin(connection);
			try {
				TransactionRecord transaction = selectByOrderId(connection, orderId);
				if (transaction == null) {
					commit(connection);
					return false;
				}
				boolean positionApplied = brokerPositionApplied(connection, orderId);
				boolean changed = false;
				if (state == TransactionState.EXECUTED && !positionApplied) {
					long delta = "BUY".equals(transaction.side()) ? transaction.quantity() : -transaction.quantity();
					changeBrokerPosition(connection, transaction.brokerInstance(), transaction.instrument(), delta);
					changed = true;
				}
				try (PreparedStatement statement = connection.prepareStatement(
						"UPDATE transactions SET state = ?, response_delivered = 1, broker_position_applied = 1, "
								+ "updated_at = CURRENT_TIMESTAMP WHERE order_id = ?")) {
					statement.setString(1, state.name());
					statement.setString(2, orderId);
					statement.executeUpdate();
				}
				commit(connection);
				return changed;
			} catch (SQLException | RuntimeException exception) {
				rollback(connection);
				throw exception;
			}
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	public Map<String, Long> brokerInventory(String brokerInstance) {
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
				"SELECT symbol, quantity FROM broker_inventory WHERE broker_instance = ? ORDER BY symbol")) {
			statement.setString(1, brokerInstance);
			try (ResultSet rows = statement.executeQuery()) {
				Map<String, Long> positions = new LinkedHashMap<>();
				while (rows.next()) {
					positions.put(rows.getString("symbol"), rows.getLong("quantity"));
				}
				return positions;
			}
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	/**
	 * Idempotently executes an order. Inventory mutation and final transaction
	 * state are committed atomically, so a crash cannot apply one without the other.
	 */
	public TransactionState processOrder(String orderId, String brokerId, String marketId) {
		return processOrder(orderId, brokerId, marketId, marketId);
	}

	public TransactionState processOrder(String orderId, String brokerId, String marketId, String inventoryMarketId) {
		try (Connection connection = open()) {
			try (Statement begin = connection.createStatement()) {
				begin.execute("BEGIN IMMEDIATE");
			}
			try {
				TransactionRecord transaction = selectForProcessing(connection, orderId, brokerId, marketId);
				if (transaction == null) {
					commit(connection);
					return TransactionState.REJECTED;
				}
				if (transaction.state().isFinal()) {
					commit(connection);
					return transaction.state();
				}

				setState(connection, orderId, TransactionState.PROCESSING);
				TransactionState result = execute(connection, transaction, inventoryMarketId);
				setState(connection, orderId, result);
				commit(connection);
				return result;
			} catch (SQLException | RuntimeException exception) {
				rollback(connection);
				throw exception;
			}
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	private TransactionState execute(Connection connection, TransactionRecord transaction, String inventoryMarketId) throws SQLException {
		Long quantity = lookupInventoryQuantity(connection, inventoryMarketId, transaction.instrument());
		if (quantity == null || transaction.quantity() <= 0) {
			return TransactionState.REJECTED;
		}
		if ("BUY".equals(transaction.side())) {
			if (transaction.quantity() > quantity) {
				return TransactionState.REJECTED;
			}
			changeInventory(connection, inventoryMarketId, transaction, quantity - transaction.quantity());
			return TransactionState.EXECUTED;
		}
		if ("SELL".equals(transaction.side())) {
			changeInventory(connection, inventoryMarketId, transaction, Math.addExact(quantity, transaction.quantity()));
			return TransactionState.EXECUTED;
		}
		return TransactionState.REJECTED;
	}

	private TransactionRecord selectForProcessing(Connection connection, String orderId, String brokerId, String marketId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT * FROM transactions WHERE order_id = ? AND broker_id = ? AND market_id = ?")) {
			statement.setString(1, orderId);
			statement.setString(2, brokerId);
			statement.setString(3, marketId);
			return readAll(statement).stream().findFirst().orElse(null);
		}
	}

	private TransactionRecord selectByOrderId(Connection connection, String orderId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM transactions WHERE order_id = ?")) {
			statement.setString(1, orderId);
			return readAll(statement).stream().findFirst().orElse(null);
		}
	}

	private boolean brokerPositionApplied(Connection connection, String orderId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT broker_position_applied FROM transactions WHERE order_id = ?")) {
			statement.setString(1, orderId);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() && rows.getInt(1) != 0;
			}
		}
	}

	private void changeBrokerPosition(Connection connection, String brokerInstance, String symbol, long delta) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO broker_inventory(broker_instance, symbol, quantity) VALUES (?, ?, ?) "
						+ "ON CONFLICT(broker_instance, symbol) DO UPDATE SET quantity = quantity + excluded.quantity")) {
			statement.setString(1, brokerInstance);
			statement.setString(2, symbol);
			statement.setLong(3, delta);
			statement.executeUpdate();
		}
	}

	private Long lookupInventoryQuantity(Connection connection, String marketId, String symbol) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT quantity FROM inventory WHERE market_id = ? AND symbol = ?")) {
			statement.setString(1, marketId);
			statement.setString(2, symbol);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rows.getLong(1) : null;
			}
		}
	}

	private void changeInventory(Connection connection, String inventoryMarketId, TransactionRecord transaction, long quantity) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE inventory SET quantity = ? WHERE market_id = ? AND symbol = ?")) {
			statement.setLong(1, quantity);
			statement.setString(2, inventoryMarketId);
			statement.setString(3, transaction.instrument());
			statement.executeUpdate();
		}
	}

	private void setState(Connection connection, String orderId, TransactionState state) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE transactions SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?")) {
			statement.setString(1, state.name());
			statement.setString(2, orderId);
			statement.executeUpdate();
		}
	}

	private void commit(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("COMMIT");
		}
	}

	private void begin(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("BEGIN IMMEDIATE");
		}
	}

	private void rollback(Connection connection) {
		try (Statement statement = connection.createStatement()) {
			statement.execute("ROLLBACK");
		} catch (SQLException ignored) {
			// The connection is already rolling back or closed.
		}
	}

	private void initialize() {
		try (Connection connection = open(); Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode = WAL");
			statement.execute("PRAGMA busy_timeout = 5000");
			statement.execute("CREATE TABLE IF NOT EXISTS transactions ("
					+ "order_id TEXT PRIMARY KEY, broker_instance TEXT NOT NULL, broker_id TEXT NOT NULL, market_id TEXT NOT NULL, side TEXT NOT NULL, "
					+ "instrument TEXT NOT NULL, quantity INTEGER NOT NULL, price TEXT NOT NULL, state TEXT NOT NULL, "
					+ "response_delivered INTEGER NOT NULL DEFAULT 0, "
					+ "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
			try {
				statement.execute("ALTER TABLE transactions ADD COLUMN response_delivered INTEGER NOT NULL DEFAULT 0");
			} catch (SQLException exception) {
				if (!exception.getMessage().contains("duplicate column name")) {
					throw exception;
				}
			}
			try {
				statement.execute("ALTER TABLE transactions ADD COLUMN broker_position_applied INTEGER NOT NULL DEFAULT 0");
			} catch (SQLException exception) {
				if (!exception.getMessage().contains("duplicate column name")) {
					throw exception;
				}
			}
			statement.execute("CREATE TABLE IF NOT EXISTS inventory ("
					+ "market_id TEXT NOT NULL, symbol TEXT NOT NULL, quantity INTEGER NOT NULL, price TEXT NOT NULL, "
					+ "PRIMARY KEY(market_id, symbol))");
			statement.execute("CREATE TABLE IF NOT EXISTS broker_inventory ("
					+ "broker_instance TEXT NOT NULL, symbol TEXT NOT NULL, quantity INTEGER NOT NULL, "
					+ "PRIMARY KEY(broker_instance, symbol))");
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	private Connection open() throws SQLException {
		Connection connection = DriverManager.getConnection(url);
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA busy_timeout = 5000");
		}
		return connection;
	}

	private List<TransactionRecord> readAll(PreparedStatement statement) throws SQLException {
		try (ResultSet rows = statement.executeQuery()) {
			List<TransactionRecord> result = new ArrayList<>();
			while (rows.next()) {
				result.add(new TransactionRecord(rows.getString("order_id"), rows.getString("broker_instance"), rows.getString("broker_id"),
						rows.getString("market_id"), rows.getString("side"), rows.getString("instrument"),
						rows.getLong("quantity"), new BigDecimal(rows.getString("price")),
						TransactionState.valueOf(rows.getString("state"))));
			}
			return result;
		}
	}

	private void update(String sql, String value) {
		try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, value);
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	private IllegalStateException failure(SQLException exception) {
		return new IllegalStateException("SQLite transaction storage failed", exception);
	}
}
