package nl.liacs.watch_cli;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import nl.liacs.watch.protocol.types.Datapoint;

/**
 * Database handles the storage and retrieval of persisted data.
 */
class Database {
    /**
     * @return The default path for the database file.
     */
    static String getDefaultPath() throws IOException, FileAlreadyExistsException {
        var dir = Utils.getDataDir();
        return dir.resolve("database.db").toString();
    }

    /**
     * Connetion to the sqlite database.
     */
    private final Connection connection;

    /**
     * Create a new database instance to the given {@code dbFile} sqlite
     * database.
     * @param dbFile The path of the sqlite database.
     * @throws SQLException SQL error when failing to open the database.
     * @throws IOException IO error when failing to read the schema file.
     */
    public Database(@Nullable String dbFile) throws SQLException, IOException {
        if (dbFile == null) {
            dbFile = Database.getDefaultPath();
        }

        String url
            = "jdbc:sqlite:"
            + dbFile;

        this.connection = DriverManager.getConnection(url);
        connection.createStatement().execute("PRAGMA foreign_keys = ON"); // Make sure foreign keys are enforced

        this.connection.setAutoCommit(true);

        this.createTables();
    }

    /**
     * Create the default tables, if needed.
     * @throws IOException IO error when failing to read the schema file.
     * @throws SQLException SQL error when failing to write or read the
     * database.
     */
    private void createTables() throws IOException, SQLException {
        var stream = this.getClass().getResourceAsStream("/tables.sql");
        var bytes = stream.readAllBytes();
        var statements = new String(bytes).split(";");

        // HACK
        for (var text : statements) {
            text = text.trim();
            if (text.isEmpty()) {
                continue;
            }

            var stmt = this.connection.prepareStatement(text);
            stmt.execute();
        }
    }

    /**
     * Add the given watch to the database.
     * @param watch The watch to add.
     * @throws SQLException SQL error when failing to write or read the
     * database.
     */
    public void addWatch(@NotNull Smartwatch watch) throws SQLException {
        var command = "INSERT INTO smartwatch(id, name) VALUES(?, ?)";

        var stmt = this.connection.prepareStatement(command);
        stmt.setString(1, watch.getUID());
        stmt.setString(2, watch.getName());
        stmt.executeUpdate();
    }

    /**
     * Convert the given {@link ResultSet} to a {@link Smartwatch}.
     * @param rs The {@link ResultSet} to convert.
     * @return {@code rs} converted to a {@link Smartwatch}.
     * @throws SQLException SQL error when {@code rs} was in an invalid format.
     */
    private Smartwatch watchFromResult(ResultSet rs) throws SQLException {
        var id = rs.getString(1);
        var name = rs.getString(2);

        var watch = new Smartwatch(id, null);
        watch.setName(name);

        return watch;
    }

    /**
     * Retrieve all {@link Smartwatch} instances from the current database
     * instance.
     * @return A list of all {@link Smartwatch} instances.
     * @throws SQLException SQL error when failing to read the database.
     */
    public WatchList getAllWatches() throws SQLException {
        var res = new WatchList();
        var stmt = this.connection.prepareStatement("SELECT * FROM smartwatch");

        ResultSet rs = stmt.executeQuery();
        while (rs.next()) {
            var watch = this.watchFromResult(rs);
            res.add(watch);
        }
        rs.close();

        return res;
    }

    public void addDatapoint(Datapoint point, String watchUid) throws SQLException {
        var stmt = this.connection.prepareStatement(
            "INSERT INTO datapoint(watch_id, `date`, amount) values(?, ?, ?)"
        );
        stmt.setString(1, watchUid);
        stmt.setLong(2, point.getInstant().toEpochMilli());
        stmt.setLong(3, point.getData().length);
        stmt.executeUpdate();

        var keys = stmt.getGeneratedKeys();
        keys.next();
        var id = keys.getLong(1);

        for (int i = 0; i < point.getData().length; i++) {
            stmt = this.connection.prepareStatement(
                "INSERT INTO datapoint_entry(datapoint_id, i, type, value) values(?, ?, ?, ?)"
            );
            stmt.setLong(1, id);
            stmt.setInt(2, i);
            stmt.setInt(3, 0); // TODO
            stmt.setDouble(4, point.getData()[i]); // TODO
            stmt.executeUpdate();
        }
    }
}
