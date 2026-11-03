package ssatr.device;

import java.util.concurrent.BlockingQueue;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }


    @Override
    public void run() {
        // Grila se măsoară cu timp monoton (nanoTime), nu cu ceasul de perete.
        long nextRelease = System.nanoTime();   // r_0 = acum
        long jobIndex = 0;

        while (!Thread.currentThread().isInterrupted()) {
            // --- așteptare ABSOLUTĂ până la eliberarea r_k ---
            long waitNs = nextRelease - System.nanoTime();
            if (waitNs > 0) {
                try {
                    Thread.sleep(waitNs / 1_000_000, (int) (waitNs % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            long startNs = System.nanoTime();
            double latenessMs = (startNs - nextRelease) / 1e6;   // întârzierea față de grilă

            // --- jobul propriu-zis ---
            double t = sensor.read();
            long timestamp = System.currentTimeMillis();
            String message = deviceId + ";" + timestamp + ";" + String.format(Locale.US, "%.2f", t);
            mqtt.publish(Config.telemetryTopic(deviceId), message);

            if (t > threshold) {
                // offer: nu blochează niciodată taskul periodic dacă coada e plină
                if (!alarmEvents.offer(t)) {
                    System.out.println("[periodic] coada de alarme plină, eveniment pierdut");
                }
            }

            long finishNs = System.nanoTime();
            System.out.printf(Locale.US,
                    "[periodic] job #%d  L=%.2f ms  C=%.2f ms  T=%.2f%s%n",
                    jobIndex, latenessMs, (finishNs - startNs) / 1e6, t,
                    t > threshold ? "  (> prag)" : "");

            // --- următoarea eliberare pe grilă: r_{k+1} = r_k + T ---
            // periodMs e citit la fiecare job, deci SET_INTERVAL are efect imediat
            long periodNs = periodMs * 1_000_000L;
            nextRelease += periodNs;
            jobIndex++;

            // Dacă jobul a depășit perioada (am pierdut eliberări), refacem grila
            // ca să nu rulăm joburi în rafală ca să "recuperăm".
            if (System.nanoTime() > nextRelease) {
                System.out.printf("[periodic] job #%d a depășit perioada, grila se resetează%n", jobIndex - 1);
                nextRelease = System.nanoTime();
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}
