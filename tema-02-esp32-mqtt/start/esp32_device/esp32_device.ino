// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                               |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()

#include "config.h"
#include "connection.h"
#include <string.h>
#include <stdlib.h>

// Structuri pentru elementele din cozi (transmise prin valoare)
#define COMMAND_MAX_LEN 64

struct CommandItem {
    char text[COMMAND_MAX_LEN];
};

struct AlarmItem {
    float temp;
    float threshold;
};

// Cozile FreeRTOS
static QueueHandle_t alarm_queue = NULL;
static QueueHandle_t command_queue = NULL;

// Parametri partajați între taskuri (modificați de aperiodic, citiți de periodic)
static volatile uint32_t current_period_ms = PERIOD_MS_INITIAL;
static volatile float current_threshold = THRESHOLD_INITIAL;

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
float read_temperature(void) {
    return temperatureRead();
}

// TODO 1: TASK PERIODIC
void periodic_telemetry_task(void* pvParameters) {
    TickType_t xLastWakeTime = xTaskGetTickCount();
    char payload[128];

    for (;;) {
        // Așteptare absolută pe grila r_k = r_0 + k*T
        uint32_t period = current_period_ms;
        xTaskDelayUntil(&xLastWakeTime, pdMS_TO_TICKS(period));

        float temp = read_temperature();
        int64_t now_ms = epoch_ms();
        float threshold = current_threshold;

        // Publică telemetria: deviceId;timestamp;temperatura
        snprintf(payload, sizeof(payload), "%s;%lld;%.2f", DEVICE_ID, (long long)now_ms, temp);
        mqtt_publish("telemetry", payload);
        Serial.printf("[periodic] Telemetrie publicată: %s\n", payload);

        // Dacă temperatura depășește pragul, trimite în coada de alarme fără blocare (timeout 0)
        if (temp > threshold) {
            AlarmItem alarm;
            alarm.temp = temp;
            alarm.threshold = threshold;
            if (xQueueSend(alarm_queue, &alarm, 0) != pdTRUE) {
                Serial.println("[periodic] AVERTISMENT: Coada de alarme este plină!");
            }
        }
    }
}

// TODO 2: TASK SPORADIC
void sporadic_alarm_task(void* pvParameters) {
    AlarmItem alarm;
    uint32_t last_alarm_mono_ms = 0;
    char payload[128];

    for (;;) {
        // Așteaptă un eveniment din coada de alarme
        if (xQueueReceive(alarm_queue, &alarm, portMAX_DELAY) == pdTRUE) {
            uint32_t now_mono = millis();

            // Verifică intervalul minim între sosiri (MIN_INTERARRIVAL_MS)
            if (last_alarm_mono_ms != 0 && (now_mono - last_alarm_mono_ms) < MIN_INTERARRIVAL_MS) {
                Serial.printf("[sporadic] Alarmă RESPINSĂ: T=%.2f > prag=%.2f (au trecut doar %u ms < %u ms)\n",
                              alarm.temp, alarm.threshold, (now_mono - last_alarm_mono_ms), MIN_INTERARRIVAL_MS);
            } else {
                last_alarm_mono_ms = now_mono;
                int64_t now_ms = epoch_ms();

                // Publică alarma: deviceId;timestamp;temperatura;prag
                snprintf(payload, sizeof(payload), "%s;%lld;%.2f;%.2f",
                         DEVICE_ID, (long long)now_ms, alarm.temp, alarm.threshold);
                mqtt_publish("alarm", payload);
                Serial.printf("[sporadic] ALARMĂ publicată: %s\n", payload);
            }
        }
    }
}

// TODO 3: TASK APERIODIC
void aperiodic_command_task(void* pvParameters) {
    CommandItem cmd;
    char response[96];
    char status_payload[160];

    for (;;) {
        // Așteaptă o comandă din coadă
        if (xQueueReceive(command_queue, &cmd, portMAX_DELAY) == pdTRUE) {
            Serial.printf("[aperiodic] Execuție comandă: '%s'\n", cmd.text);

            if (strncmp(cmd.text, "SET_INTERVAL ", 13) == 0) {
                int val = atoi(cmd.text + 13);
                if (val > 0) {
                    current_period_ms = (uint32_t)val;
                    snprintf(response, sizeof(response), "OK SET_INTERVAL %d", val);
                } else {
                    snprintf(response, sizeof(response), "ERROR interval invalid: %s", cmd.text + 13);
                }
            } else if (strncmp(cmd.text, "SET_THRESHOLD ", 14) == 0) {
                float th = atof(cmd.text + 14);
                current_threshold = th;
                snprintf(response, sizeof(response), "OK SET_THRESHOLD %.2f", th);
            } else if (strcmp(cmd.text, "RESET") == 0) {
                current_period_ms = PERIOD_MS_INITIAL;
                current_threshold = THRESHOLD_INITIAL;
                snprintf(response, sizeof(response), "OK RESET");
            } else if (strcmp(cmd.text, "STATUS") == 0) {
                snprintf(response, sizeof(response), "OK STATUS period=%u threshold=%.2f",
                         (unsigned int)current_period_ms, current_threshold);
            } else {
                snprintf(response, sizeof(response), "ERROR comanda necunoscuta: %s", cmd.text);
            }

            // Publică confirmarea pe status: deviceId;timestamp;raspuns
            int64_t now_ms = epoch_ms();
            snprintf(status_payload, sizeof(status_payload), "%s;%lld;%s",
                     DEVICE_ID, (long long)now_ms, response);
            mqtt_publish("status", status_payload);
            Serial.printf("[aperiodic] Confirmare status trimisă: %s\n", response);
        }
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    if (command_queue != NULL && text != NULL) {
        CommandItem item;
        strncpy(item.text, text, COMMAND_MAX_LEN - 1);
        item.text[COMMAND_MAX_LEN - 1] = '\0';
        // TODO 3: punem în coadă fără blocare (timeout 0)
        xQueueSend(command_queue, &item, 0);
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    // TODO 0 (a): Crearea cozilor FreeRTOS înainte de mqtt_start()
    alarm_queue = xQueueCreate(10, sizeof(AlarmItem));
    command_queue = xQueueCreate(10, sizeof(CommandItem));

    mqtt_start();

    // TODO 0 (b): Pornirea taskurilor după mqtt_start()
    // Priorități: Sporadic (4) > Periodic (3) > Aperiodic (2) (toate între 2 și 19)
    // Dimensiune stivă: 4096 octeți
    xTaskCreate(sporadic_alarm_task,    "SporadicAlarm", 4096, NULL, 4, NULL);
    xTaskCreate(periodic_telemetry_task,  "PeriodicTelem", 4096, NULL, 3, NULL);
    xTaskCreate(aperiodic_command_task,   "AperiodicCmd",  4096, NULL, 2, NULL);

    Serial.println("[setup] Toate cozile și taskurile FreeRTOS au fost inițializate.");
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}