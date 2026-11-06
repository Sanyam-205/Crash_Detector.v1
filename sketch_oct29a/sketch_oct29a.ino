#include <Wire.h>
#include <math.h>
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled!
#endif

BluetoothSerial SerialBT;

#define SDA_PIN 21
#define SCL_PIN 22
#define LIMIT_SWITCH_PIN 4 // Limit switch NO terminal here, COM to GND

#define MPU_ADDR       0x68
#define REG_SMPLRT_DIV 0x19
#define REG_CONFIG     0x1A
#define REG_GYRO_CFG   0x1B
#define REG_ACCEL_CFG  0x1C
#define REG_PWR_MGMT_1 0x6B
#define REG_WHO_AM_I   0x75
#define REG_ACCEL_OUT  0x3B

const char* deviceName = "ESP32_CrashLogger";
const unsigned long SAMPLE_INTERVAL_MS = 20; 
unsigned long nextSampleTime = 0;

bool sensorReady = false;
bool clientConnected = false;

// --- CRASH DETECTION PARAMETERS ---
const float IMPACT_THRESHOLD = 2.0;
const float TILT_THRESHOLD = 65.0;
const float STILLNESS_THRESHOLD = 5.0;
const unsigned long VERIFICATION_DELAY = 3000;

enum SystemState { STATE_NORMAL, STATE_EVENT, STATE_CRASH_PENDING };
SystemState state = STATE_NORMAL;
unsigned long eventTime = 0;

uint8_t readReg(uint8_t reg) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.endTransmission(false);
  Wire.requestFrom((uint8_t)MPU_ADDR, (uint8_t)1);
  return Wire.available() ? Wire.read() : 0xFF;
}

void writeReg(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

bool initSensor() {
  Wire.beginTransmission(MPU_ADDR);
  if (Wire.endTransmission() != 0) return false;

  uint8_t id = readReg(REG_WHO_AM_I);
  if (id != 0x68 && id != 0x72 && id != 0x70) return false;

  writeReg(REG_PWR_MGMT_1, 0x01);
  delay(50);
  writeReg(REG_CONFIG, 0x03);
  writeReg(REG_GYRO_CFG, 0x18);   // +/-2000 dps
  writeReg(REG_ACCEL_CFG, 0x18);  // +/-16g
  writeReg(REG_SMPLRT_DIV, 0x04);

  return true;
}

void setup() {
  Serial.begin(115200);
  delay(500);

  // Enable internal pull-up resistor for the limit switch
  pinMode(LIMIT_SWITCH_PIN, INPUT_PULLUP);

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(400000);

  SerialBT.begin(deviceName);
  Serial.println("Bluetooth started.");

  if (initSensor()) {
    sensorReady = true;
    Serial.println("MPU6050 ready.");
  }

  nextSampleTime = millis();
}

void loop() {
  // Check Bluetooth client connection state
  if (SerialBT.hasClient()) {
    if (!clientConnected) {
      clientConnected = true;
      SerialBT.println("timestamp_ms,ax_g,ay_g,az_g,magnitude,tilt,status");
    }
  } else {
    if (clientConnected) {
      clientConnected = false;
    }
    delay(10);
    return;
  }

  // --- READ INCOMING TEST COMMANDS FROM PHONE TERMINAL ---
  if (SerialBT.available()) {
    String incomingCmd = SerialBT.readStringUntil('\n');
    incomingCmd.trim(); // strip \r or whitespace

    if (incomingCmd.equalsIgnoreCase("TEST_CRASH")) {
      state = STATE_CRASH_PENDING;
      SerialBT.println("CMD:CRASH_DETECTED");
      Serial.println(">> FAKE CRASH INJECTED VIA BLUETOOTH");
    } 
    else if (incomingCmd.equalsIgnoreCase("TEST_CANCEL")) {
      state = STATE_NORMAL;
      SerialBT.println("CMD:SOS_CANCELLED");
      Serial.println(">> SOS CANCEL INJECTED VIA BLUETOOTH");
    }
  }

  // --- LIMIT SWITCH PHYSICAL TRIGGER ---
  // When limit switch is clicked, NO connects to COM, pulling pin LOW
  if (state == STATE_CRASH_PENDING && digitalRead(LIMIT_SWITCH_PIN) == LOW) {
    state = STATE_NORMAL;
    SerialBT.println("CMD:SOS_CANCELLED");
    Serial.println(">> LIMIT SWITCH CLICKED: SOS CANCELLED");
    delay(200); // Simple non-blocking-style debounce window
  }

  // Sensor recovery
  if (!sensorReady) {
    if (millis() - nextSampleTime >= 500) {
      nextSampleTime = millis();
      if (initSensor()) sensorReady = true;
    }
    return;
  }

  if ((long)(millis() - nextSampleTime) < 0) return;
  nextSampleTime += SAMPLE_INTERVAL_MS;

  // Burst read 14 bytes
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(REG_ACCEL_OUT);
  if (Wire.endTransmission(false) != 0) { sensorReady = false; return; }
  Wire.requestFrom((uint8_t)MPU_ADDR, (uint8_t)14);
  if (Wire.available() < 14) { sensorReady = false; return; }

  uint8_t buf[14];
  for (int i = 0; i < 14; i++) buf[i] = Wire.read();

  float ax = ((int16_t)((buf[0] << 8) | buf[1])) / 2048.0;
  float ay = ((int16_t)((buf[2] << 8) | buf[3])) / 2048.0;
  float az = ((int16_t)((buf[4] << 8) | buf[5])) / 2048.0;
  float gx = ((int16_t)((buf[8] << 8) | buf[9])) / 16.4;
  float gy = ((int16_t)((buf[10] << 8) | buf[11])) / 16.4;
  float gz = ((int16_t)((buf[12] << 8) | buf[13])) / 16.4;

  float magnitude = sqrt(ax * ax + ay * ay + az * az);
  float gyro_rate = sqrt(gx * gx + gy * gy + gz * gz);
  float roll = atan2(ay, az) * 180.0 / PI;
  float pitch = atan2(-ax, sqrt(ay * ay + az * az)) * 180.0 / PI;
  float max_tilt = max(abs(roll), abs(pitch));

  // --- AUTOMATIC SENSOR STATE MACHINE ---
  const char* statusStr = "NORMAL";

  switch (state) {
    case STATE_NORMAL:
      // Trigger 1: Sudden high-G collision
      if (magnitude > IMPACT_THRESHOLD) {
        state = STATE_EVENT;
        eventTime = millis();
        statusStr = "IMPACT_DETECTED";
      } 
      // Trigger 2: Slow tip-over or low-side slide
      else if (max_tilt > TILT_THRESHOLD && gyro_rate < STILLNESS_THRESHOLD) {
        state = STATE_EVENT;
        eventTime = millis();
        statusStr = "TIP_OVER_DETECTED";
      }
      break;

    case STATE_EVENT:
      statusStr = "VERIFYING";
      if (millis() - eventTime >= VERIFICATION_DELAY) {
        if (max_tilt > TILT_THRESHOLD && gyro_rate < STILLNESS_THRESHOLD) {
          state = STATE_CRASH_PENDING;
          SerialBT.println("CMD:CRASH_DETECTED");
          statusStr = "CRASH_TRIGGERED";
        } else {
          state = STATE_NORMAL;
          statusStr = "FALSE_ALARM";
        }
      }
      break;

    case STATE_CRASH_PENDING:
      statusStr = "SOS_ACTIVE";
      break;
  }

  // Continuous stream every 100ms
  static unsigned long lastStream = 0;
  if (millis() - lastStream >= 100) {
    lastStream = millis();
    SerialBT.printf("%lu,%.2f,%.2f,%.2f,%.2f,%.1f,%s\n",
                    millis(), ax, ay, az, magnitude, max_tilt, statusStr);
  }
}