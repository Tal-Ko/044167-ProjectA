// Comment the following line if you want to run with real ECG signal
//#define SIMULATION
//#define CONTINUOUS_MODE

///////////////////////////////////////////////////////////////////////////////
////////////////////////////// 7 SEGMENT DISPLAY //////////////////////////////
///////////////////////////////////////////////////////////////////////////////
// Credit: https://github.com/DeanIsMe/SevSeg
#include "SevSeg.h"

SevSeg sevseg;
byte numDigits = 3;
byte digitPins[] = {23, 24, 25};
byte segmentPins[] = {32, 30, 41, 43, 44, 34, 40, 42};

bool resistorsOnSegments = true;
bool updateWithDelaysIn = true;
byte hardwareConfig = COMMON_CATHODE;

///////////////////////////////////////////////////////////////////////////////
//////////////////////////////////// STATE ////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

const int buttonPin = 8;
int buttonState = 0;

bool monitoringDone = false;
#ifdef SIMULATION
bool simulationDone = false;
#endif  // SIMULATION

///////////////////////////////////////////////////////////////////////////////
///////////////////////////////////// ECG /////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
bool alreadyPeaked = false;

// TODO: Figure out these values for real ECG Signal
#ifdef SIMULATION
int ecgOffset = 0;
int lowerThreshold = -400;
int upperThreshold = 400;
#else // !SIMULATION
int ecgOffset = 0;
int lowerThreshold = 400;
int upperThreshold = 900;
#endif

#ifdef SIMULATION
#define ECG_DATA_SIZE (10000)
int parsedSimulatedECGDataIndex = 0;
int ecgDataIndex = 0;
int ecgData[ECG_DATA_SIZE];
#else   // !SIMULATION
const int ecgPin = A0;
#endif  // SIMULATION

///////////////////////////////////////////////////////////////////////////////
////////////////////////////////////// RR /////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////
#define BPM_HIST_NUM_BINS (220)
#define RR_HIST_NUM_BINS (1200)
#define MIN_RR_INTERVAL (250)       // Matches BPM of 240
#define MAX_RR_INTERVAL (1500)      // Matches BPM of 40

unsigned int rrIntervalsHistogram[RR_HIST_NUM_BINS] = {};
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
double ann[ANN_ARRAY_SIZE];
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
////////////////////////////////// ANALYSIS ///////////////////////////////////
///////////////////////////////////////////////////////////////////////////////

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

void dumpRMSSD() {
    if (rmssd == 0.0) {
        // Calculate the RMS
        int numRRDifferences = 0;
        for (int i = 0; i < RR_HIST_NUM_BINS; ++i) {
            numRRDifferences += rrIntervalsHistogram[i];
        }

        // We want number of differences not number of intervals, so minus 1
        numRRDifferences--;
        rmssd = sqrt(rmssdSum / numRRDifferences);
    }

    SerialUSB.println(rmssd);
    SerialUSB.println("Done");
}

void dumpSDANN() {
    if (sdann == 0.0 && annIndex != 0) {
        // Calculate the mean
        unsigned long sum = 0;
        for (int i = 0; i < annIndex; ++i) {
            sum += ann[i];
        }

        double mean = sum / annIndex;

        // Calculate the standard deviation
        for (int i = 0; i < annIndex; ++i) {
            sdann += pow(ann[i] - mean, 2);
        }

        sdann = sqrt(sdann / annIndex);
    }

    SerialUSB.println(sdann);
    SerialUSB.println("Done");
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

        hti = rrIntervalHistogramDensity / rrIntervalHistogramHeight;
    }

    SerialUSB.println(hti);
    SerialUSB.println("Done");
}

void updateRRHistogram(unsigned long rrInterval) {
    rrIntervalsHistogram[rrInterval]++;
}

void updateBPMHistogram(unsigned long rrInterval) {
    // Calculate the beats per minute, rrInterval is measured in
    // milliseconds so we must multiply by 1000
    float beatsPerMinute = (1.0/rrInterval) * 60.0 * 1000;
    int bpmi = min((int)(floor(beatsPerMinute)), BPM_HIST_NUM_BINS);
    bpmHistogram[bpmi]++;
    sevseg.setNumber(bpmi);
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
    // If at least SDANN_INTERVAL milliseconds passed since annTime
    if (secondPeakTime - annTime >= SDANN_INTERVAL) {
        // Save the average NN over the current time period
        ann[(annIndex++) % ANN_ARRAY_SIZE] = annSum / annCount;

        // Reset parameters
        annTime = millis();
        annSum = 0;
        annCount = 0;
    }

    annSum += rrInterval;
    annCount++;
}

void dispatchCommand() {
    int action = SerialUSB.parseInt();
    switch (action) {
        case 1:
            dumpRRIntervalHistogram();
            break;
        case 2:
            dumpBPMHistogram();
            break;
        case 3:
            dumpRMSSD();
            break;
        case 4:
            dumpSDANN();
            break;
        case 5:
            dumpHTI();
            break;
        default:
            break;
    }
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
         SerialUSB.println(buttonState);
    } while (buttonState == LOW);

#ifdef SIMULATION
    digitalWrite(LED_BUILTIN, HIGH);

    while (SerialUSB.available() <= 0) {
        continue;
    }

    int reading = 0;
    do {
        reading = SerialUSB.parseInt() - ecgOffset;
        ecgData[parsedSimulatedECGDataIndex++] = reading;
    } while (reading != 1000);
#else   // !SIMULATION
    // Wait a reasonable amount of time for the button press
    // to be released.
    delay(1500);
    SerialUSB.println("Starting to monitor");
#endif  // SIMULATION

    digitalWrite(LED_BUILTIN, LOW);
    annTime = millis();
}

void loop() {
    // Detect if button was pressed.
    // If it was, stop collecting data and dump the results so far
    buttonState = digitalRead(buttonPin);
    if (buttonState == HIGH && !monitoringDone) {
        monitoringDone = true;
        digitalWrite(LED_BUILTIN, HIGH);
        SerialUSB.println("Monitoring done!");
        sevseg.blank();
    }

    if (monitoringDone) {
        if (SerialUSB.available() > 0) {
            dispatchCommand();
        }

        delay(10);
        return;
    }

#ifdef SIMULATION
#ifdef CONTINUOUS_MODE
    // Keep reading the same data over and over again
    if (ecgDataIndex >= ECG_DATA_SIZE) {
        ecgDataIndex = 0;
    }

    int ecgReading = ecgData[ecgDataIndex++];
#else   // !CONTINUOUS_MODE
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
#endif  // CONTINUOUS_MODE
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

            // Probably noise
            if (rrInterval < MIN_RR_INTERVAL || rrInterval > MAX_RR_INTERVAL) {
                // Reset the firstPeakTime since the current peak is invalid.
                // This way we perform a soft reset on the monitoring process
                // without invalidating the data we collected thus far.
                firstPeakTime = 0;
                goto refresh;
            }

            updateRRHistogram(rrInterval);
            updateBPMHistogram(rrInterval);
            updateRMSSDSum(rrInterval);
            updateSDANNSum(rrInterval);

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

refresh:
    sevseg.refreshDisplay();
    delay(1);
}
