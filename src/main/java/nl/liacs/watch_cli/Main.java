package nl.liacs.watch_cli;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.util.SortedMap;
import java.util.TreeMap;

import org.jetbrains.annotations.NotNull;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.TerminalBuilder;

import nl.liacs.watch.protocol.server.ConnectionManager;
import nl.liacs.watch.protocol.tcpserver.SSLServer;
import nl.liacs.watch.protocol.tcpserver.Server;
import nl.liacs.watch.protocol.types.Constants;
import nl.liacs.watch.protocol.types.Datapoint;
import nl.liacs.watch_cli.commands.Arguments;
import nl.liacs.watch_cli.commands.Command;
import nl.liacs.watch_cli.commands.Devices;
import nl.liacs.watch_cli.commands.Export;
import nl.liacs.watch_cli.commands.GetKey;
import nl.liacs.watch_cli.commands.Help;
import nl.liacs.watch_cli.commands.Live;
import nl.liacs.watch_cli.commands.Logs;
import nl.liacs.watch_cli.commands.Retrieve;
import nl.liacs.watch_cli.commands.SetKey;
import nl.liacs.watch_cli.commands.Tree;

public class Main {
    public static ConnectionManager connectionManager;
    public static WatchList watches;
    public static Logger<String> logger = new Logger<>();
    public static SortedMap<String, Command> commands = new TreeMap<>();
    public static Database database;

    public static LineReader makeReader() throws IOException {
        var terminal = TerminalBuilder.builder().build();
        var parser = new DefaultParser();

        return LineReaderBuilder.builder()
            .terminal(terminal)
            //.completer(systemRegistry.completer())
            .parser(parser)
            .build();
    }

    public static void executeLine(ParsedLine line) {
        var words = line.words();
        var cmd = words.get(0);

        var args = new Arguments(words.subList(1, words.size()));


        if (!commands.containsKey(cmd)) {
            System.err.printf("unknown command: %s\n", cmd);
            return;
        }

        if (!commands.get(cmd).checkArguments(args)) {
            System.err.println("incorrect amount of arguments provided");
            return;
        }

        commands.get(cmd).run(args);
    }

    public static void addWatch(@NotNull Smartwatch watch) {
        watches.add(watch);
        try {
            database.addWatch(watch);
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void main(String[] args)
        throws
            IOException,
            SQLException,
            UnrecoverableKeyException,
            KeyManagementException,
            KeyStoreException,
            CertificateException,
            NoSuchAlgorithmException
    {
        commands.put("devices", new Devices());
        commands.put("get", new GetKey());
        commands.put("set", new SetKey());
        commands.put("tree", new Tree());
        commands.put("logs", new Logs());
        commands.put("help", new Help());
        commands.put("live", new Live());
        commands.put("export", new Export());
        commands.put("retrieve", new Retrieve());

        Main.database = new Database(null);
        Main.watches = Main.database.getAllWatches();
        logger.info(String.format("loaded %d watches from database", Main.watches.size()));

        ServerSocket server;
        var keystorePath = System.getProperty("keystore.path");
        if (keystorePath == null) {
            var msg = "keystore path is not given, unencrypted connections will be used.";
            logger.info(msg);
            System.err.println(msg);

            server = Server.createServer(Constants.TcpPort);
            logger.info("running tcp server on port " + Constants.TcpPort);
        } else {
            var msg = String.format("keystore path is '%s', encrypted connections will be used.", keystorePath);
            logger.info(msg);
            System.err.println(msg);

            var fs = new FileInputStream(keystorePath);

            System.out.print("enter password of keystore: ");
            var password = System.console().readPassword();

            server = SSLServer.createServer(
                Constants.TcpPort,
                "TLSv1.2",
                fs,
                password
            );
            logger.info("running encrypted tcp server on port " + Constants.TcpPort);
        }

        Main.connectionManager = new ConnectionManager(server);
        logger.info("started connection manager");

        Main.connectionManager.addConnectionConsumer(conn -> {
            logger.info("Got a new watch connection");
            try {
                var uidFut = conn.getValues("system.uid");
                logger.info("Asked watch for UID");

                uidFut.thenAccept(params -> {
                    var uid = params[0].asString().getValue();

                    logger.info("Got watch ID: " + uid);

                    // try reusing watch
                    final var oldWatch = watches.getWithID(uid);
                    if (oldWatch != null) {
                        try {
                            oldWatch.addConnection(conn);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        return;
                    }

                    final var newWatch = new Smartwatch(uid, conn);
                    newWatch.setName(String.format("Watch %d", watches.size()));
                    addWatch(newWatch);
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        Main.connectionManager.addConnectionFailureConsumer(e -> {
            // TODO: better exception handler, don't just dump the whole
            // stacktrace on stderr.
            e.printStackTrace();

            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.error(sw.toString());
        });

        var reader = makeReader();
        while (true) {
            ParsedLine line;
            try {
                reader.readLine("> ");
                line = reader.getParsedLine();
            } catch (EndOfFileException e) {
                break;
            }

            if (line.line().isBlank()) {
                continue;
            }

            try {
                executeLine(line);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Main.connectionManager.close();
    }
}
