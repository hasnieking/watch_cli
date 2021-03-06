package nl.liacs.watch_cli.commands;

import java.io.IOException;
import java.util.Collections;

import nl.liacs.watch.protocol.types.MessageParameterLong;
import nl.liacs.watch_cli.WatchConnector;

public class Live implements Command {
    private boolean isRunning;

    public String getDescription() {
        return "Show a live view of all enabled sensors with the given interval of the given devices.\nDevice IDs are comma delimited.\nIf the store flag is set to true, the datapoints will be added to the watch datapoint list, which can be exported using the export command.";
    }

    public String getUsage() {
        return "[--store=false] <device ids...> <interval>";
    }

    public boolean checkArguments(Arguments args) {
        return args.getRest().size() == 2;
    }

    public void run(Arguments args) {
        var _store = args.getBoolean("store");
        final var store = _store != null ? _store : false;

        var deviceIds = args.getRest().get(0).split(",");
        var interval = Integer.parseInt(args.getRest().get(1));

        WatchConnector[] connectors = new WatchConnector[deviceIds.length];
        for (int i = 0; i < deviceIds.length; i++) {
            var connector = Utils.getConnector(deviceIds[i]);

            if (connector.isClosed()) {
                System.err.printf("watch with id %s not connected\n", deviceIds[i]);
                return;
            }

            connectors[i] = connector;

            try {
                var conn = connector.getConnection();
                if (conn == null) {
                    System.err.printf("watch with id %s not connected\n", deviceIds[i]);
                    return;
                }
                conn.setValues("live.interval", new MessageParameterLong(interval));
            } catch (IOException e) {
                System.out.println(e.getStackTrace());
                System.exit(1);
            }
        }

        this.isRunning = true;

        System.err.println("showing a live view of datapoints, press any key to stop.");

        for (var i = 0; i < connectors.length; i++) {
            var connector = connectors[i];
            var watch = Utils.getWatch(deviceIds[i]);

            connector.addIncrementConsumer(point -> {
                if (this.isRunning) {
                    System.out.printf("(%s) %s\n", watch.getUID(), point);

                    if (store) {
                        var list = Collections.singletonList(point);
                        watch.addDatapoints(list);
                    }
                }
            });
        }

        try {
            System.in.read();
        } catch (IOException e) {
            System.out.println(e.getStackTrace());
            System.exit(1);
        }

        this.isRunning = false;
    }
}
