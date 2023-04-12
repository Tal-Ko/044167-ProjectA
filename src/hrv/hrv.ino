const int ecgPin = A0;
int upperThreshold = 400;
int lowerThreshold = -400;
int ecgOffset = 0;
float beatsPerMinute = 0.0;
bool alreadyPeaked = false;
unsigned long firstPeakTime = 0;
unsigned long secondPeakTime = 0;
unsigned long rrInterval = 0;

// Array for the Histogram
// TODO: Change to a more fitting size
#define HIST_NUM_BINS (1000)

unsigned int intervalsHisotgram[HIST_NUM_BINS] = {};

// Variables for ECG data simulation
int parsedSimulatedECGDataIndex = 0;
int ecgDataIndex = 0;
int ecgData[10000];

void setup() {
    SerialUSB.begin(115200);
    pinMode(LED_BUILTIN, OUTPUT);

    while (SerialUSB.available() <= 0) {
        continue;
    }

    int reading = 0;
    do {
        reading = SerialUSB.parseInt() - ecgOffset;
        ecgData[parsedSimulatedECGDataIndex++] = reading;
    } while (reading != 1000);
}

void loop() {
    // Until we work with actual analog signal
    if (ecgDataIndex > parsedSimulatedECGDataIndex) {
        SerialUSB.println("Done data");
        digitalWrite(LED_BUILTIN, LOW);

        for (int i = 0; i < HIST_NUM_BINS; ++i) {
            SerialUSB.println(intervalsHisotgram[i]);
        }

        SerialUSB.println("Done hist");
        return;
    }

    int ecgReading = ecgData[ecgDataIndex++];
    // int ecgReading = SerialUSB.parseInt() - ecgOffset;
    // int ecgReading = analogRead(ecgPin) - ecgOffset;

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
            intervalsHisotgram[rrInterval]++;
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

    // Calculate the beats per minute, rrInterval is measured in
    // milliseconds so we must multiply by 1000
    if (rrInterval != 0) {
        beatsPerMinute = (1.0/rrInterval) * 60.0 * 1000;
    }

    // Print the final values to be read by the serial plotter
    SerialUSB.print(ecgReading);
    SerialUSB.print(",");
    SerialUSB.println(beatsPerMinute);

    // delay(5);
}
