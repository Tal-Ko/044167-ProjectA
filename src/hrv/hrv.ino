// Credit: https://github.com/DeanIsMe/SevSeg
#include "SevSeg.h"

SevSeg sevseg;
byte numDigits = 3;
byte digitPins[] = {22, 23, 24, 25};
byte segmentPins[] = {32, 30, 41, 43, 44, 34, 40, 42};

bool resistorsOnSegments = true;
bool updateWithDelaysIn = true;
byte hardwareConfig = COMMON_CATHODE;

// Comment this line if you want to run with real ECG signal
#define SIMULATION

#define BPM_HIST_NUM_BINS (220)

#ifdef SIMULATION
#define RR_HIST_NUM_BINS (1200)
int parsedSimulatedECGDataIndex = 0;
int ecgDataIndex = 0;
int ecgData[10000];
bool simulationDone = false;
#else   // !SIMULATION
#define RR_HIST_NUM_BINS (1200)
const int ecgPin = A0;
#endif  // SIMULATION

const int buttonPin = 2;
int buttonState = 0;

bool monitoringDone = false;

bool alreadyPeaked = false;
float beatsPerMinute = 0.0;
int bpmi = 0;

// TODO: Figure out these values for real ECG Signal
int ecgOffset = 0;
int lowerThreshold = -400;
int upperThreshold = 400;

unsigned int rrIntervalsHistogram[RR_HIST_NUM_BINS] = {};
unsigned int bpmHistogram[BPM_HIST_NUM_BINS + 1] = {};

unsigned long firstPeakTime = 0;
unsigned long rrInterval = 0;
unsigned long secondPeakTime = 0;

void dumpRRIntervalHistogram() {
    // Dump RR histogram
    for (int i = 0; i < RR_HIST_NUM_BINS; ++i) {
        SerialUSB.println(rrIntervalsHistogram[i]);
    }

    SerialUSB.println("Done");
}

void dumpBPMHistogram() {
    // Dump BPM histogram
    for (int i = 0; i < BPM_HIST_NUM_BINS; ++i) {
        SerialUSB.println(bpmHistogram[i]);
    }

    SerialUSB.println("Done");
}

void setup() {
    sevseg.begin(hardwareConfig, numDigits, digitPins, segmentPins, resistorsOnSegments);
    sevseg.setBrightness(90);

    SerialUSB.begin(115200);
    pinMode(LED_BUILTIN, OUTPUT);
    pinMode(buttonPin, INPUT);

    // Wait until the button is pressed before starting the program
    do {
         buttonState = digitalRead(buttonPin);
         if (buttonState == HIGH) {
            break;
         }
    } while (buttonState == LOW);

    digitalWrite(LED_BUILTIN, HIGH);

#ifdef SIMULATION
    while (SerialUSB.available() <= 0) {
        continue;
    }

    int reading = 0;
    do {
        reading = SerialUSB.parseInt() - ecgOffset;
        ecgData[parsedSimulatedECGDataIndex++] = reading;
    } while (reading != 1000);
#endif  // SIMULATION

    digitalWrite(LED_BUILTIN, LOW);
}

void loop() {
    // Detect if button was pressed.
    // If it was, stop collecting data and dump the results so far
    buttonState = digitalRead(buttonPin);
    if (buttonState == HIGH && !monitoringDone) {
        monitoringDone = true;
        digitalWrite(LED_BUILTIN, HIGH);
        SerialUSB.println("Monitoring done!");
    }

    if (monitoringDone) {
        if (SerialUSB.available() > 0) {
            int action = SerialUSB.parseInt();
            switch (action) {
                case 1:
                    dumpRRIntervalHistogram();
                    break;
                case 2:
                    dumpBPMHistogram();
                    break;
                default:
                    break;
            }
        }

        delay(10);
        return;
    }

#ifdef SIMULATION
    // Check if the simulation is finished
    if (ecgDataIndex > parsedSimulatedECGDataIndex || simulationDone) {
        // Print only once
        if (!simulationDone) {
            SerialUSB.println("Simulation done!");
            simulationDone = true;
            digitalWrite(LED_BUILTIN, LOW);
        }

        delay(10);
        return;
    }

    // Otherwise parse the next sample
    int ecgReading = ecgData[ecgDataIndex++];
#else   // !SIMULATION
    int ecgReading = analogRead(ecgPin) - ecgOffset;
#endif  // SIMULATION

    // Measure the ECG reading minus an offset to bring it into the same
    // range as the heart rate (i.e. around 60 to 100 bpm)
    if (ecgReading > upperThreshold && alreadyPeaked == false) {
        // Check if the ECG reading is above the upper threshold and that
        // we aren't already in an existing peak
        if (firstPeakTime == 0) {
            // If this is the very first peak, set the first peak time
            firstPeakTime = millis();
            digitalWrite(LED_BUILTIN, HIGH);
        }
        else {
            // Otherwise set the second peak time and calculate the
            // R-to-R interval. Once calculated we shift the second
            // peak to become our first peak and start the process
            // again
            secondPeakTime = millis();
            rrInterval = secondPeakTime - firstPeakTime;
            rrIntervalsHistogram[rrInterval]++;

            // Calculate the beats per minute, rrInterval is measured in
            // milliseconds so we must multiply by 1000
            beatsPerMinute = (1.0/rrInterval) * 60.0 * 1000;
            bpmi = min((int)(floor(beatsPerMinute)), BPM_HIST_NUM_BINS);
            bpmHistogram[bpmi]++;

            sevseg.setNumber(bpmi);

            firstPeakTime = secondPeakTime;
            digitalWrite(LED_BUILTIN, HIGH);
        }
        alreadyPeaked = true;
    }

    if (ecgReading < lowerThreshold) {
        // Check if the ECG reading has fallen below the lower threshold
        // and if we are ready to detect another peak
        alreadyPeaked = false;
        digitalWrite(LED_BUILTIN, LOW);
    }

    sevseg.refreshDisplay();
    delay(1);
}
