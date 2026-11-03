package ssatr.exemple.mqtt;

/**
 * Setările comune pentru exemplele MQTT.
 *
 * Brokerul este partajat de toți studenții, așa că fiecare folosește un topic
 * propriu. Înlocuiți STUDENT cu numele vostru (fără spații sau diacritice).
 */
public class MqttConfig {

    public static final String BROKER = "tcp://control.aut.utcluj.ro:11188";

    public static final String STUDENT = "cretu-giovani-gabriel";

    public static final String TOPIC = "ssatr/" + STUDENT + "/exemplu/salut";
}
