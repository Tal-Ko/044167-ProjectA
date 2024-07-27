# 044167-ProjectA
**Heart rate variability statistical analysis**

Heart rate is a commonly measured physiological parameter in real time. [Heart rate variability](https://en.wikipedia.org/wiki/Heart_rate_variability) is where the amount of time between your heartbeats fluctuates slightly. Even though these fluctuations are undetectable except with specialized devices, they can still indicate current or future health problems, including heart conditions and mental health issues like anxiety and depression[^1]. In this project we will build an [Arduino](https://www.arduino.cc/) based electrocardiogram ([ECG](https://en.wikipedia.org/wiki/Electrocardiography)) acquisition and analysis system, to measure heart rate variability over long time periods.

# 044167-ProjectB
**Heart rate variability statistical analysis**

Following the previous project, we introduced an Android application to control the internal state machine of the Arduino via Bluetooth Low Energy (BLE), fetch measurements and display them to the user. In addition, the Arduino's code was updated to handle adaptive thresholds for RR detection. The circuit was minimized and the Arduino board was replaced from the Arduino Due to the Arduino Nano BLE 33 Rev2.

[^1]: [Heart Rate Variability (HRV)](https://my.clevelandclinic.org/health/symptoms/21773-heart-rate-variability-hrv)
