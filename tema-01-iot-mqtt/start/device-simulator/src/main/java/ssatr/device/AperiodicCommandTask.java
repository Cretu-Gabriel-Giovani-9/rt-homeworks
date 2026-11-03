package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }
    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            String command;
            try {
                command = commands.take();              // blochează până sosește o comandă
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long startNs = System.nanoTime();           // începutul execuției jobului
            String reply;
            try {
                reply = execute(command);
            } catch (Exception e) {
                // orice eroare neprevăzută: taskul NU se oprește
                reply = "ERROR " + e.getMessage();
            }

            // câmpurile mesajului sunt separate prin ';', deci textul nu are voie să le conțină
            reply = reply.replace(';', ',');
            mqtt.publish(Config.statusTopic(deviceId),
                    deviceId + ";" + System.currentTimeMillis() + ";" + reply);

            System.out.printf(Locale.US, "[aperiodic] \"%s\" -> %s  (C=%.2f ms, în coadă au rămas %d)%n",
                    command.trim(), reply, (System.nanoTime() - startNs) / 1e6, commands.size());
        }
    }

    /** Execută o comandă și întoarce textul confirmării ("OK ..." sau "ERROR ..."). */
    private String execute(String command) {
        String[] parts = command.trim().split("\\s+");
        String name = parts[0].toUpperCase(Locale.ROOT);

        switch (name) {
            case "SET_INTERVAL": {
                if (parts.length != 2) return "ERROR SET_INTERVAL necesita un parametru: SET_INTERVAL <ms>";
                long ms;
                try {
                    ms = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    return "ERROR parametru invalid pentru SET_INTERVAL: " + parts[1];
                }
                if (ms < 100) return "ERROR intervalul trebuie sa fie cel putin 100 ms";
                periodic.setPeriodMs(ms);
                return "OK SET_INTERVAL " + ms;
            }
            case "SET_THRESHOLD": {
                if (parts.length != 2) return "ERROR SET_THRESHOLD necesita un parametru: SET_THRESHOLD <valoare>";
                double value;
                try {
                    value = Double.parseDouble(parts[1]);
                } catch (NumberFormatException e) {
                    return "ERROR parametru invalid pentru SET_THRESHOLD: " + parts[1];
                }
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    return "ERROR valoare invalida pentru SET_THRESHOLD: " + parts[1];
                }
                periodic.setThreshold(value);
                return "OK SET_THRESHOLD " + String.format(Locale.US, "%.2f", value);
            }
            case "RESET":
                if (parts.length != 1) return "ERROR RESET nu are parametri";
                sensor.reset();
                return "OK RESET";
            case "STATUS":
                if (parts.length != 1) return "ERROR STATUS nu are parametri";
                return "OK STATUS period=" + periodic.getPeriodMs()
                        + " threshold=" + String.format(Locale.US, "%.2f", periodic.getThreshold());
            default:
                return "ERROR comanda necunoscuta: " + parts[0];
        }
    }
}
