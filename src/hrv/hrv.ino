#include <ArduinoBLE.h>

void SET_LED_WHITE() {
    digitalWrite(LEDR, LOW);
    digitalWrite(LEDG, LOW);
    digitalWrite(LEDB, LOW);
}

void SET_LED_RED() {
    digitalWrite(LEDR, LOW);
    digitalWrite(LEDG, HIGH);
    digitalWrite(LEDB, HIGH);
}

void SET_LED_GREEN() {
    digitalWrite(LEDR, HIGH);
    digitalWrite(LEDG, LOW);
    digitalWrite(LEDB, HIGH);
}

void SET_LED_BLUE() {
    digitalWrite(LEDR, HIGH);
    digitalWrite(LEDG, HIGH);
    digitalWrite(LEDB, LOW);
}

void SET_LED_YELLOW() {
    digitalWrite(LEDR, LOW);
    digitalWrite(LEDG, LOW);
    digitalWrite(LEDB, HIGH);
}

void SET_LED_MAGENTA() {
    digitalWrite(LEDR, LOW);
    digitalWrite(LEDG, HIGH);
    digitalWrite(LEDB, LOW);
}

void SET_LED_CYAN() {
    digitalWrite(LEDR, HIGH);
    digitalWrite(LEDG, LOW);
    digitalWrite(LEDB, LOW);
}

///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////// ECG /////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
bool alreadyPeaked = false;

int ecgOffset = 0;
int lowerThreshold = 600;
int upperThreshold = 900;

const int ecgPin = A0;

///////////////////////////////////////////////////////////////////////////////
////////////////////////////////////// RR /////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
#define BPM_HIST_NUM_BINS (220)
#define RR_HIST_NUM_BINS (1500)
#define MIN_RR_INTERVAL (250)       // Matches BPM of 240
#define MAX_RR_INTERVAL (1500)      // Matches BPM of 40

unsigned int rrIntervalsHistogram[RR_HIST_NUM_BINS + 1] = {};
unsigned int bpmHistogram[BPM_HIST_NUM_BINS + 1] = {};

unsigned long firstPeakTime = 0;
unsigned long prevrrInterval = 0;
unsigned long rrInterval = 0;
unsigned long secondPeakTime = 0;

///////////////////////////////////////////////////////////////////////////////
//////////////////////////////////// RMSSD ////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
unsigned long rmssdSum = 0;

///////////////////////////////////////////////////////////////////////////////
//////////////////////////////////// SDANN ////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
// A-NN == Average NN (RR)
#define SDANN_INTERVAL (5 * 60 * 1000)  // 5min = 5 * 60[s] * 1000[ms]

// Number of samples in a 24hour period
#define ANN_ARRAY_SIZE ((24 * 60 * 60 * 1000) / SDANN_INTERVAL)
double ann[ANN_ARRAY_SIZE] = {};
unsigned int annIndex = 0;
unsigned int annSum = 0;
unsigned int annCount = 0;
unsigned long annTime = 0;

///////////////////////////////////////////////////////////////////////////////
//////////////////////////////// MEASUREMENTS /////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
// Source: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5624990/
/**
 * @brief The root mean square of successive differences between normal
 * heartbeats (RMSSD) is obtained by first calculating each successive time
 * difference between heartbeats in ms. Then, each of the values is squared and
 * the result is averaged before the square root of the total is obtained.
 */
double rmssd = 0.0;

/**
 * @brief The standard deviation of the average normal-to-normal (NN) intervals
 * for each of the 5 min segments during a 24 h recording (SDANN) is measured
 * and reported in ms.
 */
double sdann = 0.0;

/**
 * @brief The HTI is a geometric measure based on 24 h recordings which
 * calculates the integral of the density of the RR interval histogram divided
 * by its height.
 */
double hti = 0.0;

///////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMMUNICATION /////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

byte prevAction = 0;

// Service
const char* hrvServiceUUID = "0777dfa9-204b-11ef-8fea-646ee0fcbb46";

// Commands
const char* hrvCommandCharacteristicUUID = "07dba383-204b-11ef-a096-646ee0fcbb46";

// Responses
const char* hrvResponseCharacteristicUUID = "5f0b1b60-2177-11ef-971d-646ee0fcbb46";

// Monitor
const char* hrvBPMCharacteristicUUID = "45ed7702-21d5-11ef-8771-646ee0fcbb46";

// Live
const char* hrvLiveSignalCharacteristicUUID = "f0a7ba94-2426-11ef-bb71-646ee0fcbb46";
const char* hrvLiveRRCharacteristicUUID = "f187ef45-2426-11ef-bb71-646ee0fcbb46";

BLEService hrvService(hrvServiceUUID);
BLEIntCharacteristic hrvCommandCharacteristic(hrvCommandCharacteristicUUID, BLEWrite);
BLEDoubleCharacteristic hrvResponseCharacteristic(hrvResponseCharacteristicUUID, BLERead | BLENotify);
BLEIntCharacteristic hrvBPMCharacteristic(hrvBPMCharacteristicUUID, BLERead | BLENotify);
BLEIntCharacteristic hrvLiveSignalCharacteristic(hrvLiveSignalCharacteristicUUID, BLERead | BLENotify);
BLEIntCharacteristic hrvLiveRRCharacteristic(hrvLiveRRCharacteristicUUID, BLERead | BLENotify);

bool g_isConnected = false;
bool g_running = false;

enum COMMANDS {
    STANDBY = 0,

    // Functional
    START = 1,
    PAUSE = 2,
    RESET = 3,

    // Monitoring
    DUMP_RMSSD = 10,
    DUMP_SDANN = 11,
    DUMP_HTI = 12,
};

int lastSigTimestamp = 0;
const int sigDeltaT = 50;

///////////////////////////////////////////////////////////////////////////////
////////////////////////////////// ANALYSIS ///////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

void dumpRMSSD() {
    if (rmssd == 0.0) {
        // Calculate the RMS
        int numRRDifferences = 0;
        for (int i = 0; i < RR_HIST_NUM_BINS; ++i) {
            numRRDifferences += rrIntervalsHistogram[i];
        }

        // We want number of differences not number of intervals, so minus 1
        numRRDifferences--;

        if (numRRDifferences != 0) {
            rmssd = sqrt(rmssdSum / numRRDifferences);
        }
    }

    hrvResponseCharacteristic.writeValue(rmssd);
}

void dumpSDANN() {
    if (sdann == 0.0 && annIndex != 0) {
        // Calculate the mean
        unsigned long sum = 0;
        for (int i = 0; i < annIndex; ++i) {
            sum += ann[i];
        }

        double mean = sum / (double)annIndex;

        // Calculate the standard deviation
        for (int i = 0; i < annIndex; ++i) {
            sdann += pow(ann[i] - mean, 2);
        }

        sdann = sqrt(sdann / annIndex);
    }

    hrvResponseCharacteristic.writeValue(sdann);
    Serial.print("sdann: ");
    Serial.println(sdann);
}

void dumpHTI() {
    if (hti == 0.0) {
        unsigned long rrIntervalHistogramDensity = 0;
        unsigned long rrIntervalHistogramHeight = 0;

        for (int i = 0; i < RR_HIST_NUM_BINS; ++i) {
            // There are rrIntervalsHistogram[i] instances of the RR interval i
            rrIntervalHistogramDensity += rrIntervalsHistogram[i];
            if (rrIntervalsHistogram[i] > rrIntervalHistogramHeight) {
                rrIntervalHistogramHeight = rrIntervalsHistogram[i];
            }
        }

        if (rrIntervalHistogramHeight != 0) {
            hti = rrIntervalHistogramDensity / rrIntervalHistogramHeight;
        }
    }

    hrvResponseCharacteristic.writeValue(hti);
    Serial.print("hti: ");
    Serial.println(hti);
}

void updateRRHistogram(unsigned long rrInterval) {
    rrIntervalsHistogram[rrInterval]++;
}

void updateBPMHistogram(unsigned long rrInterval) {
    // Calculate the beats per minute, rrInterval is measured in
    // milliseconds so we must multiply by 1000
    float beatsPerMinute = (1.0 / rrInterval) * 60.0 * 1000;
    int bpmi = min((int)(floor(beatsPerMinute)), BPM_HIST_NUM_BINS);
    bpmHistogram[bpmi]++;

    hrvBPMCharacteristic.writeValue(bpmi);
    Serial.print("bpmi: ");
    Serial.println(bpmi);
}

void updateRMSSDSum(unsigned long rrInterval) {
    // Add the current RR interval difference square to the RMSSD sum
    if (prevrrInterval == 0) {
        prevrrInterval = rrInterval;
    } else {
        unsigned long rrIntervalDifference = rrInterval - prevrrInterval;
        prevrrInterval = rrInterval;
        rmssdSum += (rrIntervalDifference * rrIntervalDifference);
    }
}

void updateSDANNSum(unsigned long rrInterval) {
    annSum += rrInterval;
    annCount++;

    // If at least SDANN_INTERVAL milliseconds passed since annTime
    if (secondPeakTime - annTime >= SDANN_INTERVAL) {
        // Save the average NN over the current time period
        ann[(annIndex++) % ANN_ARRAY_SIZE] = annSum / annCount;

        // Reset parameters
        annTime = millis();
        annSum = 0;
        annCount = 0;
    }
}

void dispatchCommand() {
    int action = hrvCommandCharacteristic.value();
    if (action == prevAction) {
        return;
    }

    prevAction = action;
    Serial.print("Received command: ");

    switch (action) {
        case STANDBY:
            Serial.println("STANDBY");
            SET_LED_CYAN();
            break;
        case START:
            Serial.println("START");
            g_running = true;
            lastSigTimestamp = millis();
            SET_LED_GREEN();
            break;
        case PAUSE:
            Serial.println("PAUSE");
            g_running = false;
            SET_LED_RED();
            break;
        case RESET:
            Serial.println("RESET");
            resetAll();
            break;
        case DUMP_RMSSD:
            Serial.println("DUMP_RMSSD");
            dumpRMSSD();
            break;
        case DUMP_SDANN:
            Serial.println("DUMP_SDANN");
            dumpSDANN();
            break;
        case DUMP_HTI:
            Serial.println("DUMP_HTI");
            dumpHTI();
            break;
        default:
            Serial.print("Unknown command : ");
            Serial.println(action);
            break;
    }

    Serial.println("Finished handling command");
}

void resetAll() {
    memset(rrIntervalsHistogram, 0, sizeof(rrIntervalsHistogram));
    memset(bpmHistogram, 0, sizeof(bpmHistogram));
    firstPeakTime = 0;
    prevrrInterval = 0;
    rrInterval = 0;
    secondPeakTime = 0;

    rmssdSum = 0;

    memset(ann, 0, sizeof(ann));
    annIndex = 0;
    annSum = 0;
    annCount = 0;
    annTime = 0;

    rmssd = 0;
    sdann = 0;
    hti = 0;

    annTime = millis();

    pinMode(LEDR, OUTPUT);
    pinMode(LEDG, OUTPUT);
    pinMode(LEDB, OUTPUT);

    SET_LED_WHITE();
}

void setupSerial() {
    Serial.begin(9600);
    while (!Serial);
    Serial.println("Serial connected");
}

void setupBLE() {
    // begin initialization
    if (!BLE.begin()) {
        Serial.println("Starting Bluetooth® Low Energy failed!");
        SET_LED_BLUE();
        while (1);
    }

    // set advertised local name and service UUID:
    BLE.setLocalName("Nano 33 BLE Rev2 HRV");
    BLE.setAdvertisedService(hrvService);

    // add the characteristic to the service
    hrvService.addCharacteristic(hrvCommandCharacteristic);
    hrvService.addCharacteristic(hrvResponseCharacteristic);
    hrvService.addCharacteristic(hrvBPMCharacteristic);
    hrvService.addCharacteristic(hrvLiveSignalCharacteristic);
    hrvService.addCharacteristic(hrvLiveRRCharacteristic);

    // add service
    BLE.addService(hrvService);

    // set the initial value for the characteristic:
    hrvCommandCharacteristic.writeValue(STANDBY);

    // start advertising
    BLE.advertise();

    Serial.println("BLE HRV Peripheral Ready");
}

void setup() {
    setupSerial();
    setupBLE();
    resetAll();
}

void loop() {
    BLEDevice central = BLE.central();

    if (central && !g_isConnected) {
        Serial.print("Connected to central: ");
        Serial.println(central.address());
    }

    if (g_isConnected && (!central || !central.connected())) {
        Serial.println("Disconnected from central");
        BLE.disconnect();
    }

    g_isConnected = central.connected();
    if (g_isConnected) {
        dispatchCommand();
    }

    if (!g_running) {
        delay(100);
        return;
    }

    int ecgReading = analogRead(ecgPin) - ecgOffset;

    // We might've started running and then disconnected from the central
    if (g_isConnected) {
        if (millis() - lastSigTimestamp > sigDeltaT) {
            hrvLiveSignalCharacteristic.writeValue(ecgReading);
            lastSigTimestamp = millis();
        }
    }

    // Measure the ECG reading minus an offset to bring it into the same
    // range as the heart rate (i.e. around 60 to 100 bpm)
    if (ecgReading > upperThreshold && !alreadyPeaked) {
        // Check if the ECG reading is above the upper threshold and that
        // we aren't already in an existing peak
        if (firstPeakTime == 0) {
            // If this is the very first peak, set the first peak time
            firstPeakTime = millis();
        } else {
            // Otherwise set the second peak time and calculate the
            // R-to-R interval. Once calculated we shift the second
            // peak to become our first peak and start the process
            // again
            secondPeakTime = millis();
            rrInterval = secondPeakTime - firstPeakTime;

            // Probably noise
            if (rrInterval < MIN_RR_INTERVAL || rrInterval > MAX_RR_INTERVAL) {
                // Reset the firstPeakTime since the current peak is invalid.
                // This way we perform a soft reset on the monitoring process
                // without invalidating the data we collected thus far.
                firstPeakTime = 0;
                goto refresh;
            }

            // We might've started running and then disconnected from the central
            if (g_isConnected) {
                hrvLiveRRCharacteristic.writeValue(rrInterval);
            }

            updateRRHistogram(rrInterval);
            updateBPMHistogram(rrInterval);
            updateRMSSDSum(rrInterval);
            updateSDANNSum(rrInterval);

            firstPeakTime = secondPeakTime;
        }

        alreadyPeaked = true;
    }

    if (ecgReading < lowerThreshold) {
        // Check if the ECG reading has fallen below the lower threshold
        // and if we are ready to detect another peak
        alreadyPeaked = false;
    }

refresh:
    delay(1);
}
