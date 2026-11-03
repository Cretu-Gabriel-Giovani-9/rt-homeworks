package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.function.DoubleSupplier;
/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;
    private final DoubleSupplier threshold;   // pragul curent, citit de la taskul periodic

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt,
                             BlockingQueue<Double> alarmEvents, DoubleSupplier threshold) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
        this.threshold = threshold;
    }

    @Override
    public void run() {
        long minNs = MIN_INTERARRIVAL_MS * 1_000_000L;
        long lastReleaseNs = 0;
        boolean hasReleased = false;   // până la prima alarmă nu există "ultima eliberare"

        while (!Thread.currentThread().isInterrupted()) {
            double temperature;
            try {
                temperature = alarmEvents.take();          // blochează până sosește un eveniment
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long arrivalNs = System.nanoTime();            // momentul sosirii (eliberarea jobului)

            // Impunem intervalul minim între sosiri: respingem, nu amânăm.
            if (hasReleased && arrivalNs - lastReleaseNs < minNs) {
                double sinceMs = (arrivalNs - lastReleaseNs) / 1e6;
                System.out.printf(Locale.US,
                        "[sporadic] eveniment RESPINS (T=%.2f): au trecut doar %.0f ms < %d ms%n",
                        temperature, sinceMs, MIN_INTERARRIVAL_MS);
                continue;
            }

            // Eveniment acceptat: aceasta este o eliberare a taskului sporadic.
            lastReleaseNs = arrivalNs;
            hasReleased = true;

            double th = threshold.getAsDouble();
            String message = deviceId + ";" + System.currentTimeMillis() + ";"
                    + String.format(Locale.US, "%.2f", temperature) + ";"
                    + String.format(Locale.US, "%.2f", th);
            mqtt.publish(Config.alarmTopic(deviceId), message);

            double rMs = (System.nanoTime() - arrivalNs) / 1e6;   // timp de răspuns R
            System.out.printf(Locale.US,
                    "[sporadic] ALARMA publicată (T=%.2f, prag=%.2f), R=%.2f ms%n",
                    temperature, th, rMs);
        }
    }
}