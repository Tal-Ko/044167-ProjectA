# Based on http://www.mikeburdis.com/wp/notes/plotting-serial-port-data-using-python-and-matplotlib/
import argparse
import functools
import matplotlib.animation as animation
import matplotlib.pyplot as plt
import serial
import threading
import time
import pandas as pd
import tkinter as tk
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
import statistics

data_from_arduino = b''
parsed_data = []
histogram_data = []

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

    # Create figure for plotting
    fig = plt.figure(1)
    ax = fig.add_subplot(2, 1, 1)
    bpm_ax = fig.add_subplot(2, 1, 2)
    xs = []
    bpm = []
    ecg = []

    # https://stackoverflow.com/questions/71659042/sending-int-from-python-to-arduino-but-there-is-an-upper-limit-how-do-i-solve
    # Convert from mV to V
    ecg_data_for_serial = [f"s{int(ecg_sample * 1000)}".encode() for ecg_sample in ecg_data]
    ecg_data_for_serial.append(b's1000')
    for ecg_sample_serial in ecg_data_for_serial:
        ser.write(ecg_sample_serial)

    def read_from_serial():
        global data_from_arduino, parsed_data, histogram_data
        while True:
            new_data = ser.read_all()
            data_from_arduino += new_data
            if data_from_arduino == b'':
                continue

            last_data_index = data_from_arduino.rfind(b'\r\n')
            parsed_data.extend(data_from_arduino[:last_data_index].strip().split(b'\r\n'))
            data_from_arduino = data_from_arduino[last_data_index:]

            if parsed_data[-1] == b'Done data':
                break

        parsed_data = list(filter(lambda val: val != b'', parsed_data))

        data_from_arduino = data_from_arduino[data_from_arduino.rfind(b'Done data'):]

        # Parse histrogram data
        while True:
            new_data = ser.read_all()
            data_from_arduino += new_data
            if data_from_arduino == b'':
                continue

            last_data_index = data_from_arduino.rfind(b'\r\n')
            histogram_data.extend(data_from_arduino[:last_data_index].strip().split(b'\r\n'))
            data_from_arduino = data_from_arduino[last_data_index:]

            if histogram_data[-1] == b'Done hist':
                break

        histogram_data = list(filter(lambda val: val != b'', histogram_data))[:-1]
        histogram_data = [int(x) for x in histogram_data]
        print(histogram_data)

    data_thread = threading.Thread(target=read_from_serial)

    # Give some startup time
    time.sleep(1)
    data_thread.start()

    print("Waiting for data from Arduino...")
    data_thread.join()

    print("Displaying results:")
    hist_fig = plt.figure(2)
    hist_ax = hist_fig.add_subplot(1, 1, 1)
    hist_values = []
    for i in range(1, len(histogram_data)):
        hist_values.extend([i] * histogram_data[i])

    hist_ax.hist(hist_values)

    # This function is called periodically from FuncAnimation
    def animate(frame, xs, bpm, ecg):
        frame_idx = len(xs)

        # Aquire and parse data from serial port
        line = parsed_data[frame_idx]
        if line == b'Done data':
            return

        ecgReading, beatsPerMinute = line.strip().split(b',')
        ecgReading = float(ecgReading)
        beatsPerMinute = float(beatsPerMinute)

        xs.append(frame_idx)
        bpm.append(beatsPerMinute)
        ecg.append(ecgReading)

        ax.clear()
        ax.plot(xs, ecg, label="Raw ECG Signal")
        ax.legend()

        bpm_ax.clear()
        bpm_ax.plot(xs, bpm, label="BPM")
        bpm_ax.legend()

    # Set up plot to call animate() function periodically
    ani = animation.FuncAnimation(fig, functools.partial(animate, xs=xs, bpm=bpm, ecg=ecg), interval=1)

    try:
        plt.show()
    except KeyboardInterrupt:
        ser.close()

    ## Form here - what i add

    # For test only - should be remove in integrtion
    #hist_values = random.normal(loc=45, scale=20, size=(600))
    #bpm = random.normal(loc=70, scale=3, size=(600))

    # Start Button BG
    root = tk.Tk()
    root.title("HRV Project")

    # Define Data for GUI
    data1 = hist_values
    df1 = pd.DataFrame(data1)
    data2 = bpm
    df2 = pd.DataFrame(data2)

    def start_function():
        print("The start button has been clicked!")

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

        # calc HVR
        RR_mean = round(statistics.mean(hist_values), 3)
        RR_sd = round(statistics.stdev(hist_values), 3)
        text = "Your RR intervals are distributed with mean " + str(RR_mean) + " and variance " + str(RR_sd)
        ax1.text(0.5, -0.1, text, transform=ax1.transAxes, ha='center')

        # BPM Plot
        figure2 = plt.Figure(figsize=(5, 4), dpi=100)
        ax2 = figure2.add_subplot(111)
        line2 = FigureCanvasTkAgg(figure2, root_plot)
        line2.get_tk_widget().pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        ax2.hist(df2, color='green', bins=50)
        ax2.grid(axis='y')
        ax2.set_title('BPM Distribution')

        # Calc Avg BPM
        avg_bpm = round(sum(bpm) / len(bpm), 3)
        text2 = "The Avarge Bpm is: " + str(avg_bpm)
        ax2.text(0.5, -0.1, text2, transform=ax2.transAxes, ha='center')

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