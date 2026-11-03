package ssatr.service;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul trebuie sincronizat.
 */
public class Dashboard {

    private static class DeviceState {
        double lastTemperature = Double.NaN;
        long lastMessageMs = 0;     // System.currentTimeMillis() la SOSIREA ultimului mesaj
        int alarmCount = 0;
    }

    // TreeMap: device-urile apar sortate alfabetic în tabel
    private final Map<String, DeviceState> devices = new TreeMap<>();

    private DeviceState stateOf(String deviceId) {
        return devices.computeIfAbsent(deviceId, id -> new DeviceState());
    }

    public synchronized void onTelemetry(String deviceId, double temperature) {
        DeviceState s = stateOf(deviceId);
        s.lastTemperature = temperature;
        s.lastMessageMs = System.currentTimeMillis();
    }

    public synchronized void onAlarm(String deviceId, double temperature) {
        DeviceState s = stateOf(deviceId);
        s.lastTemperature = temperature;
        s.alarmCount++;
        s.lastMessageMs = System.currentTimeMillis();
    }

    public synchronized void onStatus(String deviceId) {
        stateOf(deviceId).lastMessageMs = System.currentTimeMillis();
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        if (devices.isEmpty()) {
            System.out.println("(niciun device nu a trimis încă mesaje)");
            return;
        }
        long now = System.currentTimeMillis();
        System.out.printf("%-14s %8s   %-16s %s%n", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME");
        for (Map.Entry<String, DeviceState> e : devices.entrySet()) {
            DeviceState s = e.getValue();
            String temp = Double.isNaN(s.lastTemperature)
                    ? "-" : String.format(Locale.US, "%.2f", s.lastTemperature);
            String age = "acum " + String.format(Locale.US, "%.1f", (now - s.lastMessageMs) / 1000.0) + " s";
            System.out.printf("%-14s %8s   %-16s %d%n", e.getKey(), temp, age, s.alarmCount);
        }
    }
}