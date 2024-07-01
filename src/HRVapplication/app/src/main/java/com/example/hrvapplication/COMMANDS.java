package com.example.hrvapplication;

public enum COMMANDS {
    // Standby
    STANDBY(0),

    // Functional
    START(1),
    PAUSE(2),
    RESET(3),

    // Monitoring
    DUMP_RMSSD(10),
    DUMP_SDANN(11),
    DUMP_HTI(12);

    private final int value;

    COMMANDS(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
