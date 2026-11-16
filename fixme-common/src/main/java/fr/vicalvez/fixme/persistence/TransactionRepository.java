package fr.vicalvez.fixme.persistence;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite journal shared by Broker and Market.
 *
 * <p>The database path is configured with {@code -Dfixme.database.path=...};
 * both applications must use the same path for local fail-over recovery.</p>
 */
public final class TransactionRepository {

	private static final String DEFAULT_DATABASE = "fixme.db";
	private final String url;

	public TransactionRepository() {
		this(Path.of(System.getProperty("fixme.database.path", DEFAULT_DATABASE)));
	}

	public TransactionRepository(Path databasePath) {
		this.url = "jdbc:sqlite:" + databasePath.toAbsolutePath();
		initialize();
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
				"UPDATE transactions SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?")) {
			statement.setString(1, state.name());
			statement.setString(2, orderId);
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw failure(exception);
		}
	}

	/**
	 * Idempotently executes an order. Inventory mutation and final transaction
	 * state are committed atomically, so a crash cannot apply one without the other.
	 */
	public TransactionState processOrder(String orderId, String brokerId, String marketId) {
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
				TransactionState result = execute(connection, transaction);
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

	private TransactionState execute(Connection connection, TransactionRecord transaction) throws SQLException {
		Long quantity = lookupInventoryQuantity(connection, transaction.marketId(), transaction.instrument());
		if (quantity == null || transaction.quantity() <= 0) {
			return TransactionState.REJECTED;
		}
		if ("BUY".equals(transaction.side())) {
			if (transaction.quantity() > quantity) {
				return TransactionState.REJECTED;
			}
			changeInventory(connection, transaction, quantity - transaction.quantity());
			return TransactionState.EXECUTED;
		}
		if ("SELL".equals(transaction.side())) {
			changeInventory(connection, transaction, Math.addExact(quantity, transaction.quantity()));
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

	private Long lookupInventoryQuantity(Connection connection, String marketId, String symbol) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT quantity FROM inventory WHERE market_id = ? AND symbol = ?")) {
			statement.setString(1, marketId);
			statement.setString(2, symbol);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rows.getLong(1) : null;
			}
		}
	}

	private void changeInventory(Connection connection, TransactionRecord transaction, long quantity) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE inventory SET quantity = ? WHERE market_id = ? AND symbol = ?")) {
			statement.setLong(1, quantity);
			statement.setString(2, transaction.marketId());
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
					+ "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
			statement.execute("CREATE TABLE IF NOT EXISTS inventory ("
					+ "market_id TEXT NOT NULL, symbol TEXT NOT NULL, quantity INTEGER NOT NULL, price TEXT NOT NULL, "
					+ "PRIMARY KEY(market_id, symbol))");
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
