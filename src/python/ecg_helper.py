# Based on http://www.mikeburdis.com/wp/notes/plotting-serial-port-data-using-python-and-matplotlib/
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
import argparse
import enum
import matplotlib.pyplot as plt
import pandas as pd
import serial
import statistics
import time
import tkinter as tk

class Actions(enum.IntEnum):
    RR_INTERVALS_HISTOGRAM = 1
    BPM_HISTOGRAM = 2

def convert_to_plot_hist(raw_hist_data):
    hist_data = []
    for i in range(1, len(raw_hist_data)):
        hist_data.extend([i] * raw_hist_data[i])

    return hist_data

def get_rr_intervals_histogram(ser):
    ser.write("s{}".format(Actions.RR_INTERVALS_HISTOGRAM.value).encode())

    # Give time for data to be dumped
    time.sleep(2)
    rr_intervals_hist_raw = ser.read_all().strip().split(b'\r\n')
    rr_intervals_hist_raw = rr_intervals_hist_raw[:-1]
    rr_intervals_hist = [int(val) for val in rr_intervals_hist_raw]

    return convert_to_plot_hist(rr_intervals_hist)

def get_bpm_histogram(ser):
    # Get BPM Histogram
    ser.write("s{}".format(Actions.BPM_HISTOGRAM.value).encode())

    # Give time for data to be dumped
    time.sleep(2)
    bpm_hist_raw = ser.read_all().strip().split(b'\r\n')
    bpm_hist_raw = bpm_hist_raw[:-1]
    bpm_hist = [int(val) for val in bpm_hist_raw]

    return convert_to_plot_hist(bpm_hist)

def main():
    global data_from_arduino, parsed_data

    parser = argparse.ArgumentParser()
    parser.add_argument('-s', '--samples', type=argparse.FileType('r'), required=True)
    parser.add_argument('-c', '--com', type=int, required=True)
    args = parser.parse_args()

    # Read data from ECG samples
    # Taken from https://archive.physionet.org/cgi-bin/atm/ATM
    # Database: PTB Diagnostic ECG Database (ptbdb)
    # https://www.kaggle.com/datasets/shayanfazeli/heartbeat?resource=download&select=ptbdb_normal.csv
    # https://physionet.org/content/ecgiddb/1.0.0/#files-panel
    # https://www.physionet.org/physiobank/database/ecgiddb/biometric.shtml
    raw_csv = args.samples.readlines()
    raw_csv_ecg = raw_csv[2:]
    ecg_data = [float(raw_csv_ecg_item.strip().split(',')[1]) for raw_csv_ecg_item in raw_csv_ecg]

    # Initialize serial port
    ser = serial.Serial()
    ser.port = f'COM{args.com}'
    ser.baudrate = 115200
    ser.timeout = 10 # Timeout when using readline()
    ser.open()
    if ser.is_open:
        print("\nAll right, serial port now open. Configuration:\n")
        print(ser, "\n")

    print("[*] Writing samples data to CSV...")
    print("[*] Please press the (matrix) button to start")

    # https://stackoverflow.com/questions/71659042/sending-int-from-python-to-arduino-but-there-is-an-upper-limit-how-do-i-solve
    # Convert from mV to V
    ecg_data_for_serial = [f"s{int(ecg_sample * 1000)}".encode() for ecg_sample in ecg_data]
    ecg_data_for_serial.append(b's1000')
    for ecg_sample_serial in ecg_data_for_serial:
        ser.write(ecg_sample_serial)

    print("[*] Waiting for simulation to complete...")

    # Simulation length is ~10seconds
    time_buffer = 1
    simulation_time = 10
    time.sleep(simulation_time + time_buffer)

    # Wait for simulation to complete
    while True:
        res = ser.readline()
        if res.strip() == b'Simulation done!':
            break

    print("[*] Simulation done!")
    print("[*] Please press the (matrix) button to stop monitoring")

    # Wait for user input to press the button
    while True:
        try:
            res = ser.readline()
            if res.strip() == b'Monitoring done!':
                break
        except:
            continue

    print("[*] Monitoring done!")

    rr_hist = get_rr_intervals_histogram(ser)
    bpm_hist = get_bpm_histogram(ser)

    # Start Button BG
    root = tk.Tk()
    root.title("HRV Project")

    # Define Data for GUI
    df1 = pd.DataFrame(rr_hist)
    df2 = pd.DataFrame(bpm_hist)

    def start_function():
        # print("The start button has been clicked!")

        # Plot BG defintion
        root_plot = tk.Tk()
        root_plot.title("HRV Project")

        # Set the window size to be 400x300 pixels
        window_width = 1200
        window_height = 600

        # Get the screen width and height
        screen_width = root.winfo_screenwidth()
        screen_height = root.winfo_screenheight()

        # Calculate the x and y coordinates to center the window
        x = (screen_width / 2) - (window_width / 2)
        y = (screen_height / 2) - (window_height / 2)

        # Set the window size and position
        root_plot.geometry('%dx%d+%d+%d' % (window_width, window_height, x, y))

        # HRV Histogram Plot
        figure1 = plt.Figure(figsize=(6, 5), dpi=100)
        ax1 = figure1.add_subplot(111)
        bar1 = FigureCanvasTkAgg(figure1, root_plot)
        bar1.get_tk_widget().pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        ax1.hist(df1, bins=50)
        ax1.grid(axis='y')
        ax1.set_title('HRV Histogram Distribution')

        # Calc HVR
        # TODO: Move to Arduino code
        try:
            RR_mean = round(statistics.mean(rr_hist), 3)
            RR_sd = round(statistics.stdev(rr_hist), 3)
            text = "Your RR intervals are distributed with mean " + str(RR_mean) + " and variance " + str(RR_sd)
            ax1.text(0.5, -0.1, text, transform=ax1.transAxes, ha='center')
            ax1.axvline(RR_mean, color='k', linestyle='dashed', linewidth=1)
        except:
            pass

        # BPM Plot
        figure2 = plt.Figure(figsize=(5, 4), dpi=100)
        ax2 = figure2.add_subplot(111)
        line2 = FigureCanvasTkAgg(figure2, root_plot)
        line2.get_tk_widget().pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        ax2.hist(df2, color='green', bins=50)
        ax2.grid(axis='y')
        ax2.set_title('BPM Distribution')

        # Calc Avg BPM
        avg_bpm = round(sum(bpm_hist) / len(bpm_hist), 3)
        text2 = "The Avarge Bpm is: " + str(avg_bpm)
        ax2.text(0.5, -0.1, text2, transform=ax2.transAxes, ha='center')

        try:
            BPM_mean = round(statistics.mean(bpm_hist), 3)
            ax2.axvline(BPM_mean, color='k', linestyle='dashed', linewidth=1)
        except:
            pass

    def exit_program():
        exit()

    # Open Window buttons
    start_button = tk.Button(root, text="Start", width=15, height=5, bg="purple", fg="yellow", command=start_function)
    start_button.pack(pady=10)

    exit_button = tk.Button(root, text="Exit", command=exit_program)
    exit_button.pack()

    root.mainloop()

if __name__ == "__main__":
    main()
