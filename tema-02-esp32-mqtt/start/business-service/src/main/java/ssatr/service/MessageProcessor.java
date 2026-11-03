package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            String message;
            try {
                message = inbox.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                handle(message);
            } catch (Exception e) {
                // mesaj cu format invalid: îl raportăm și continuăm, thread-ul nu se oprește
                System.out.println("[" + kind + "] mesaj invalid ignorat: \"" + message + "\" (" + e + ")");
            }
        }
    }

    private void handle(String message) {
        // parsăm ÎNAINTE de salvare, ca un mesaj greșit să nu intre în CSV
        String[] f = message.trim().split(";", -1);

        switch (kind) {
            case "telemetry": {
                // deviceId;timestamp;temperatura
                requireFields(f, 3);
                double temperature = Double.parseDouble(f[2]);
                storage.append(kind, message);
                dashboard.onTelemetry(f[0], temperature);
                break;
            }
            case "alarm": {
                // deviceId;timestamp;temperatura;prag
                requireFields(f, 4);
                double temperature = Double.parseDouble(f[2]);
                double threshold = Double.parseDouble(f[3]);
                storage.append(kind, message);
                dashboard.onAlarm(f[0], temperature);
                System.out.printf(java.util.Locale.US,
                        "[ALARMA] %s: temperatura %.2f depășește pragul %.2f%n", f[0], temperature, threshold);
                break;
            }
            case "status": {
                // deviceId;timestamp;text
                requireFields(f, 3);
                storage.append(kind, message);
                dashboard.onStatus(f[0]);
                System.out.println("[STATUS] " + f[0] + ": " + f[2]);
                break;
            }
            default:
                throw new IllegalArgumentException("tip necunoscut: " + kind);
        }
    }

    private static void requireFields(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException("se așteptau " + expected + " câmpuri, sunt " + fields.length);
        }
        if (fields[0].isEmpty()) {
            throw new IllegalArgumentException("deviceId lipsește");
        }
    }
}
