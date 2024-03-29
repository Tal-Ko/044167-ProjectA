# Based on http://www.mikeburdis.com/wp/notes/plotting-serial-port-data-using-python-and-matplotlib/
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
from matplotlib.figure import Figure
from scipy.stats import norm
import argparse
import enum
import json
import numpy as np
import pandas as pd
import serial
import statistics
import time
import tkinter as tk

class Actions(enum.IntEnum):
    RR_INTERVALS_HISTOGRAM = 1
    BPM_HISTOGRAM = 2
    RMSSD = 3
    SDANN = 4
    HTI = 5

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

def get_rmssd(ser):
    ser.write("s{}".format(Actions.RMSSD.value).encode())

    # Give time for data to be dumped
    time.sleep(2)
    return float(ser.read_all().strip().split(b'\r\n')[0])

def get_sdann(ser):
    ser.write("s{}".format(Actions.SDANN.value).encode())

    # Give time for data to be dumped
    time.sleep(2)
    return float(ser.read_all().strip().split(b'\r\n')[0])

def get_hti(ser):
    ser.write("s{}".format(Actions.HTI.value).encode())

    # Give time for data to be dumped
    time.sleep(2)
    return float(ser.read_all().strip().split(b'\r\n')[0])

def exit_program():
        exit()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('-s', '--samples', type=argparse.FileType('r'))
    parser.add_argument('-c', '--com', type=int, required=True)
    parser.add_argument('--cm', action='store_true')
    parser.add_argument('--simulation', action='store_true')
    args = parser.parse_args()

    # Initialize serial port
    ser = serial.Serial()
    ser.port = f'COM{args.com}'
    ser.baudrate = 115200
    ser.timeout = 10 # Timeout when using readline()
    ser.open()
    if ser.is_open:
        print("\nAll right, serial port now open. Configuration:\n")
        print(ser, "\n")

    if args.simulation:
        # Read data from ECG samples
        # Taken from https://archive.physionet.org/cgi-bin/atm/ATM
        # Database: PTB Diagnostic ECG Database (ptbdb)
        # https://www.kaggle.com/datasets/shayanfazeli/heartbeat?resource=download&select=ptbdb_normal.csv
        # https://physionet.org/content/ecgiddb/1.0.0/#files-panel
        # https://www.physionet.org/physiobank/database/ecgiddb/biometric.shtml
        raw_csv = args.samples.readlines()
        raw_csv_ecg = raw_csv[2:]
        ecg_data = [float(raw_csv_ecg_item.strip().split(',')[1]) for raw_csv_ecg_item in raw_csv_ecg]

        print("[*] Writing samples data to CSV...")

    print("[*] Please press the (matrix) button to start")

    if args.simulation:
        # https://stackoverflow.com/questions/71659042/sending-int-from-python-to-arduino-but-there-is-an-upper-limit-how-do-i-solve
        # Convert from mV to V
        ecg_data_for_serial = [f"s{int(ecg_sample * 1000)}".encode() for ecg_sample in ecg_data]
        ecg_data_for_serial.append(b's1000')
        for ecg_sample_serial in ecg_data_for_serial:
            ser.write(ecg_sample_serial)

        # Check if we're not in continuous mode
        if not args.cm:
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
    else:
        # Wait for button press
        while True:
            res = ser.readline()
            if res.strip() == b'Starting to monitor':
                break

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
    rmssd = get_rmssd(ser)
    sdann = get_sdann(ser)
    hti = get_hti(ser)

    with open('measurements.json', 'w') as output:
        res = {
            "RR": rr_hist,
            "BPM": bpm_hist,
            "RMSSD": rmssd,
            "SDANN": sdann,
            "HTI": hti
        }
        output.write(json.dumps(res))

    print(f"[*] RMSSD=[{rmssd}] SDANN=[{sdann}] HTI=[{hti}]")

    # Define Data for GUI - if more graphs needed - insert here
    df1 = pd.DataFrame({"RR Histogram Distribution": rr_hist})
    df2 = pd.DataFrame({"BPM Distribution": bpm_hist})
    df_all = (df1, df2)

    # Plot BG defintion
    root_plot = tk.Tk()
    root_plot.title("HRV Project")

    # Set the window size
    window_width = 1500
    window_height = 450

    # Get the screen width and height
    screen_width = root_plot.winfo_screenwidth()
    screen_height = root_plot.winfo_screenheight()

    # Calculate the x and y coordinates to center the window
    x = int((screen_width / 2) - (window_width / 2))
    y = int((screen_height / 2) - (window_height / 2))

    # Set the window size and position
    root_plot.geometry(f'{window_width}x{window_height}+{x}+{y}')
    root_plot.resizable(False, False)

    # Calculate HVR and BMP
    RR_mean = -1
    RR_sd = -1
    avg_bpm = -1
    try:
        RR_mean = round(statistics.mean(rr_hist), 3)
        RR_sd = round(statistics.stdev(rr_hist), 3)
        avg_bpm = round(sum(bpm_hist) / len(bpm_hist), 3)
    except:
        pass

    # If mean is not needed, write '-1' in the relevant cell
    mean_all_graphs = [RR_mean, avg_bpm]
    ds_all_graphs = [RR_sd, -1]
    xlabels = ['RR [ms]', 'BPM [number of beats/minute]']

    # Plot for every parameter in name_of_graphs
    class Graph(tk.Frame):
        def __init__(self, master=None, df="", mean=-1, sd=-1, xlabel=None, *args, **kwargs):
            super().__init__(master, *args, **kwargs)
            self.fig = Figure(figsize=(5, 4))
            ax = self.fig.add_subplot(111)
            df.hist(ax=ax, bins=50, color='purple')

            if mean != -1:
                ax.axvline(mean, color='k', linestyle='dashed', linewidth=1)

            if sd != -1:
                column_data = df.iloc[:, 0]
                x = np.linspace(column_data.min(), column_data.max(), len(column_data))
                y = norm.pdf(x, mean, sd) * len(column_data) * (column_data.max() - column_data.min()) / 50
                ax.plot(x, y, color='blue', linewidth=1)

            if xlabel is None:
                ax.set_xlabel('Values')
            else:
                ax.set_xlabel(xlabel)
            ax.set_ylabel('Amount of Samples')

            self.canvas = FigureCanvasTkAgg(self.fig, master=self)
            self.canvas.draw()
            self.canvas.get_tk_widget().grid(row=1, sticky="nesw")

    # Create the graphs
    for i in range(0, len(df_all)):
        Graph(root_plot,
              df=df_all[i],
              mean=mean_all_graphs[i],
              sd=ds_all_graphs[i],
              xlabel=xlabels[i]
        ).grid(row=i // 2, column=i % 2)

    # Write all information to display
    text = f"RR intervals are distributed with mean {str(RR_mean)} and variance {str(RR_sd)}\n"
    text += f"The Avarge Bpm is: {str(avg_bpm)}\n"
    text += f"RMSSD is: {str(rmssd)}ms\nSDANN is: {str(sdann)}ms\nHTI is: {str(hti)}ms\n"
    text += "Note that HTI is only valid if the measurement is atleast 24h!"

    # Display results
    text_box = tk.Text(root_plot, width=60, height=10, wrap=tk.WORD, font='caliberi')
    text_box.grid(row=0, column=2, sticky='nesw')
    text_box.delete(0.0, "end")
    text_box.insert(5.0, "Results:\n" + text)

    # Exit button
    exit_button = tk.Button(root_plot, text="Exit", command=exit_program, width=7, height=1)
    exit_button.place(relx=0.5, rely=0.95, anchor=tk.CENTER)

    root_plot.mainloop()

if __name__ == "__main__":
    main()
