package nl.liacs.watch_cli.commands;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.stream.Collectors;

import nl.liacs.watch_cli.Main;
import nl.liacs.watch_cli.Smartwatch;

public class Export implements Command {
    private interface Formatter {
        void format(Collection<Smartwatch> watches, OutputStream out);
    }

    // TODO: escape strings that contain a tab char.
    private class TSV implements Formatter {
        TSV() {}
        public void format(Collection<Smartwatch> watches, OutputStream out) {
            var ps = new PrintStream(out);

            ps.println("Watch UID\tSensor\tDate\tData...");
            for (var watch : watches) {
                var datapoints = watch.getSortedDatapoints();
                for (var point : datapoints) {
                    ps.print(watch.getUID());
                    ps.print('\t');
                    ps.print(point.getSensor());
                    ps.print('\t');
                    ps.print(point.getInstant().toString());

                    var data = point.getData();
                    for (int i = 0; i < data.length; i++) {
                        ps.print('\t');
                        ps.print(data[i]);
                    }

                    ps.println();
                }
            }

            ps.flush();
        }
    }

    // TODO: escape strings that contain a comma char.
    private class CSV implements Formatter {
        CSV() {}
        public void format(Collection<Smartwatch> watches, OutputStream out) {
            var ps = new PrintStream(out);

            ps.println("Watch UID,Sensor,Date,Data...");
            for (var watch : watches) {
                var datapoints = watch.getSortedDatapoints();
                for (var point : datapoints) {
                    ps.print(watch.getUID());
                    ps.print(',');
                    ps.print(point.getSensor());
                    ps.print(',');
                    ps.print(point.getInstant().toString());

                    var data = point.getData();
                    for (int i = 0; i < data.length; i++) {
                        ps.print(',');
                        ps.print(data[i]);
                    }

                    ps.println();
                }
            }

            ps.flush();
        }
    }


    public String getDescription() {
        return "Export the datapoints of the watches with given IDs (or all watches if no IDs are given).\n  --format accepts 'tsv' or 'csv'.\n  --out is optional, if given the output will be written to the given path instead of stdout.";
    }

    public String getUsage() {
        return "--format <tsv|csv> [watches...]";
    }

    public boolean checkArguments(Arguments args) {
        return true;
    }

    public void run(Arguments args) {
        var format = args.getString("format");
        if (format == null) {
            format = "";
        }

        Formatter formatter;
        if (format.equals("tsv")) {
            formatter = new TSV();
        } else if (format.equals("csv")) {
            formatter = new CSV();
        } else if (format.isBlank()) {
            System.err.println("--format is required");
            return;
        } else {
            System.err.printf("unknown format '%s'\n", format);
            return;
        }

        var outPath = args.getString("out");
        if (outPath == null) {
            outPath = args.getString("output");
        }

        var watches = Utils.getWatchesFromIndicesOrAll(args.getRest());

        OutputStream out;
        boolean mustClose;
        if (outPath != null) {
            // use output file
            mustClose = true;
            try {
                out = new FileOutputStream(outPath);
            } catch (FileNotFoundException e) {
                System.err.printf("file not found: %s\n", outPath);
                return;
            }
        } else {
            // use stdout
            mustClose = false;
            out = System.out;
        }

        formatter.format(watches, out);

        if (mustClose) {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }
    }
}
