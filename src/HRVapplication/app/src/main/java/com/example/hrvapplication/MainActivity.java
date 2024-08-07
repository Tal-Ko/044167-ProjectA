package com.example.hrvapplication;

import static java.lang.Math.round;
import static java.lang.Thread.sleep;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class MainActivity extends AppCompatActivity implements BLEControllerListener {
    // UI Components
    Button startButton;
    Button pauseButton;
    Button finishButton;
    Button btButton;
    Button switchToLiveViewButton;
    Button switchToRRHistViewButton;
    Button switchToBPMHistViewButton;

    TextView pulseTextView;
    TextView htiParameter;
    TextView RMSSDParameter;
    TextView SDANNParameter;
    TextView graphTitle;
    TextView ylabel;

    // Charts and Data
    private LineChart liveECGSignalchart;
    private LineData lineData;
    private LineDataSet lineDataSet;
    private BarChart histogramChart;

    // Flags and State
    private boolean permissionsGranted = false;
    private boolean isRunning = false;
    private boolean isFinished = false;
    private boolean paused = false;

    // BLE Controller and Device Info
    private BLEController bleController;
    private String deviceAddress;

    // Handler and Runnable
    private final Handler handler = new Handler();
    Runnable updateTextView;

    // Constants
    private static final int BPM_HIST_NUM_BINS = 220;
    private static final int RR_HIST_NUM_BINS = 1200;

    // Histograms
    private int[] rrIntervalsHistogram = new int[RR_HIST_NUM_BINS];
    private int[] bpmHistogram = new int[BPM_HIST_NUM_BINS + 1];

    // Other Parameters
    private int lastBpm = 0;
    private COMMANDS lastCmd = COMMANDS.STANDBY;
    private int sampleCount;
    private int xIndex;

    @SuppressLint({"MissingInflatedId", "SetTextI18n"})
    @Override
    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Initialize TextViews
        pulseTextView = findViewById(R.id.Pulse);
        htiParameter = findViewById(R.id.HTILabel);
        RMSSDParameter = findViewById(R.id.RMSSDLabel);
        SDANNParameter = findViewById(R.id.SDANNLabel);
        graphTitle = findViewById(R.id.graphTitle);
        ylabel = findViewById(R.id.liveSignalYLabel);

        graphTitle.setText("Live Signal");

        // Initialize buttons
        startButton = findViewById(R.id.start_button);
        pauseButton = findViewById(R.id.pause_button);
        finishButton = findViewById(R.id.finish_button);
        btButton = findViewById(R.id.BT_button);
        switchToLiveViewButton = findViewById(R.id.switch_Signalview_button);
        switchToRRHistViewButton = findViewById(R.id.switch_RRview_button);
        switchToBPMHistViewButton = findViewById(R.id.switch_BPMview_button);

        // Disable buttons initially
        disableButtons();

        // Set up button click listeners
        setButtonListeners();

        // Additional setup for Bluetooth button
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btButton.setOnClickListener(v -> connectToArduinoBT());
        }

        // Adjust padding for system bars
        adjustForSystemBars();

        // Initialize BLE controller
        bleController = BLEController.getInstance(this);

        // Check for BLE support and permissions
        checkBLESupport();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkPermissions();
        }

        // Initialize the Runnable that updates the TextView with a blinking effect
        setupTextViewUpdater();

        // Initialize the graph
        initializeGraph();
    }

    // Helper method to disable buttons
    private void disableButtons() {
        startButton.setEnabled(false);
        pauseButton.setEnabled(false);
        finishButton.setEnabled(false);
        switchToLiveViewButton.setEnabled(false);
        switchToRRHistViewButton.setEnabled(false);
        switchToBPMHistViewButton.setEnabled(false);
    }

    // Helper method to set button listeners
    @SuppressLint("SetTextI18n")
    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"})
    private void setButtonListeners() {
        // State buttons
        startButton.setOnClickListener(v -> {
            try {
                startHRVMeasurement();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        pauseButton.setOnClickListener(v -> {
            try {
                pauseHRVMeasurement();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        finishButton.setOnClickListener(v -> {
            try {
                finishHRVMeasurement();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        // Graph buttons
        switchToLiveViewButton.setOnClickListener(v -> switchToLiveView());
        switchToRRHistViewButton.setOnClickListener(v -> {
            graphTitle.setText("RR Histogram");
            updateRRHistograms();
            switchToHistView();
        });
        switchToBPMHistViewButton.setOnClickListener(v -> {
            graphTitle.setText("BPM Histogram");
            updateBPMHistograms();
            switchToHistView();
        });
    }

    // Helper method to adjust padding for system bars
    private void adjustForSystemBars() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    // Helper method to set up TextView updater with blinking effect
    private void setupTextViewUpdater() {
        updateTextView = new Runnable() {
            @Override
            public void run() {
                if (!pauseButton.isEnabled()) {
                    handler.postDelayed(this, 1000);
                    return;
                }

                // Update the TextView with the current number
                if (lastBpm == 0) {
                    pulseTextView.setText("Calibrating");
                    pulseTextView.setTextSize(22f);
                } else {
                    pulseTextView.setText(String.valueOf(lastBpm));
                    pulseTextView.setTextSize(48f);
                }

                // Create the blink animation
                Animation blink = new AlphaAnimation(0.0f, 1.0f);
                blink.setDuration(1500);
                blink.setRepeatMode(Animation.REVERSE);
                blink.setRepeatCount(1);

                // Start the animation
                pulseTextView.startAnimation(blink);

                // Schedule the next update after 1 second
                handler.postDelayed(this, 1000);
            }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    protected void onStart() {
        super.onStart();

        if (!BluetoothAdapter.getDefaultAdapter().isEnabled()){
            Intent enableBTIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBTIntent, 1);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        isRunning = false;
        bleController.removeBLEControllerListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        bleController.addBLEControllerListener(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), 1001);
        } else {
            permissionsGranted = true;
        }
    }

    private void checkBLESupport() {
        // Check if BLE is supported on the device.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE not supported!", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionsGranted = (Arrays.stream(grantResults).sum() == 0);
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    private void sendCommand(COMMANDS cmd) throws InterruptedException {
        byte[] cmdBytes = new byte[]{ (byte) cmd.getValue() };
        lastCmd = cmd;
        bleController.sendCommand(cmdBytes);
        sleep(500);
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    private void startHRVMeasurement() throws InterruptedException {
        startButton.setEnabled(false);
        pauseButton.setEnabled(true);
        finishButton.setEnabled(true);
        switchToLiveViewButton.setEnabled(false);
        switchToRRHistViewButton.setEnabled(false);
        switchToBPMHistViewButton.setEnabled(false);

        htiParameter.setVisibility(View.INVISIBLE);
        RMSSDParameter.setVisibility(View.INVISIBLE);
        SDANNParameter.setVisibility(View.INVISIBLE);

        handler.removeCallbacksAndMessages(null);

        // Start the initial update
        handler.post(updateTextView);

        switchToLiveView();

        isRunning = true;
        paused = false;

        if (isFinished) {
            lastBpm = 0;
            clearGraphData();
            sendCommand(COMMANDS.RESET);
            isFinished = false;
        }

        sendCommand(COMMANDS.START);
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    private void pauseHRVMeasurement() throws InterruptedException {
        startButton.setEnabled(true);
        pauseButton.setEnabled(false);
        finishButton.setEnabled(true);

        paused = true;
        sendCommand(COMMANDS.PAUSE);
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    private void finishHRVMeasurement() throws InterruptedException {
        startButton.setEnabled(true);
        pauseButton.setEnabled(false);
        finishButton.setEnabled(false);
        switchToLiveViewButton.setEnabled(true);
        switchToRRHistViewButton.setEnabled(true);
        switchToBPMHistViewButton.setEnabled(true);

        htiParameter.setVisibility(View.VISIBLE);
        RMSSDParameter.setVisibility(View.VISIBLE);
        SDANNParameter.setVisibility(View.VISIBLE);

        paused = true;
        sendCommand(COMMANDS.PAUSE);
        sendCommand(COMMANDS.DUMP_RMSSD);
        sendCommand(COMMANDS.DUMP_SDANN);
        sendCommand(COMMANDS.DUMP_HTI);

        isFinished = true;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"})
    private void connectToArduinoBT() {
        if (deviceAddress != null) {
            disableButtons();
            btButton.setEnabled(false);
            bleController.disconnect();
            return;
        }

        if (!permissionsGranted) {
            checkPermissions();
            return;
        }

        bleController.addBLEControllerListener(this);
        bleController.init();

        handler.post(new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                btButton.setEnabled(false);
                btButton.setText("Scanning...");
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void switchToLiveView() {
        if (liveECGSignalchart.getVisibility() == View.INVISIBLE) {
            liveECGSignalchart.setVisibility(View.VISIBLE);
            histogramChart.setVisibility(View.INVISIBLE);
            graphTitle.setText("Live Signal");
            ylabel.setVisibility(View.VISIBLE);
        }
    }

    private void switchToHistView() {
        if (histogramChart.getVisibility() == View.INVISIBLE) {
            liveECGSignalchart.setVisibility(View.INVISIBLE);
            histogramChart.setVisibility(View.VISIBLE);
            ylabel.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void BLEControllerConnected() {
        Log.d("BLE", "BLEController connected");

        handler.post(new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                startButton.setEnabled(true);
                btButton.setEnabled(true);
                btButton.setText("BT Disconnect");
            }
        });
    }

    @Override
    public void BLEControllerDisconnected() {
        this.deviceAddress = null;
        Log.d("BLE", "BLEController disconnected");

        isRunning = false;
        disableButtons();

        handler.postDelayed(new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                btButton.setEnabled(true);
                btButton.setText("BT Connect");

                if (isFinished) {
                    switchToLiveViewButton.setEnabled(true);
                    switchToRRHistViewButton.setEnabled(true);
                    switchToBPMHistViewButton.setEnabled(true);
                }
            }
        }, 2500);
    }

    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"})
    @Override
    public void BLEDeviceFound(String name, String address) {
        this.deviceAddress = address;
        Log.d("BLE", "Device found: " + name + " (" + address + ")");

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startButton.setEnabled(false);
            }
        });

        bleController.connectToDevice(deviceAddress);
    }

    @Override
    public void BLEDataReceived(byte[] data) {
        Log.d("BLE", "Received data: " + Arrays.toString(data));
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void BLEHRVParametersReceived(double value) {
        switch (lastCmd) {
            case DUMP_RMSSD:
                RMSSDParameter.setText("RMSSD: " + round(value));
                break;
            case DUMP_SDANN:
                SDANNParameter.setText("SDANN: " + round(value));
                break;
            case DUMP_HTI:
                htiParameter.setText("HTI: " + round(value));
                break;
        }
    }

    @Override
    public void BLELiveDataReceived(int data, String characteristic) {
        if (paused) {
            return;
        }

        // Detect already running
        if (!isRunning) {
            isRunning = true;
            handler.post(new Runnable() {
                @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
                @Override
                public void run() {
                    try {
                        startHRVMeasurement();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        switch (characteristic) {
            case "BPM":
                if (data < BPM_HIST_NUM_BINS) {
                    lastBpm = data;
                    Log.d("BLE", "BPMi: " + data);
                    bpmHistogram[data]++;
                }
                break;
            case "SIG":
                updateGraph(data);
                break;
            case "RR":
                if (data < RR_HIST_NUM_BINS) {
                    rrIntervalsHistogram[data]++;
                }
                break;
        }
    }

    private void configureGraphTextColor(Chart<?> chart) {
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (nightModeFlags) {
            case Configuration.UI_MODE_NIGHT_YES:
                // Dark theme
                chart.getXAxis().setTextColor(Color.WHITE);
                if (chart instanceof LineChart) {
                    ((LineChart) chart).getAxisLeft().setTextColor(Color.WHITE);
                    ((LineChart) chart).getAxisRight().setTextColor(Color.WHITE);
                } else {
                    ((BarChart) chart).getAxisLeft().setTextColor(Color.WHITE);
                    ((BarChart) chart).getAxisRight().setTextColor(Color.WHITE);
                }
                chart.getLegend().setTextColor(Color.WHITE);
                chart.getDescription().setTextColor(Color.WHITE);
                break;

            case Configuration.UI_MODE_NIGHT_NO:
            case Configuration.UI_MODE_NIGHT_UNDEFINED:
                // Light theme
                chart.getXAxis().setTextColor(Color.BLACK);
                if (chart instanceof LineChart) {
                    ((LineChart) chart).getAxisLeft().setTextColor(Color.BLACK);
                    ((LineChart) chart).getAxisRight().setTextColor(Color.BLACK);
                } else {
                    ((BarChart) chart).getAxisLeft().setTextColor(Color.BLACK);
                    ((BarChart) chart).getAxisRight().setTextColor(Color.BLACK);
                }
                chart.getLegend().setTextColor(Color.BLACK);
                chart.getDescription().setTextColor(Color.BLACK);
                break;
        }
    }

    private void initializeGraph() {
        histogramChart = findViewById(R.id.histogramChart);
        liveECGSignalchart = findViewById(R.id.ECGLiveSignal);
        lineDataSet = new LineDataSet(null, "Live Signal");
        lineDataSet.setDrawValues(false);
        lineDataSet.setDrawCircles(false);
        lineData = new LineData(lineDataSet);
        liveECGSignalchart.setData(lineData);

        configureGraphTextColor(liveECGSignalchart);
        configureGraphTextColor(histogramChart);

        // Customize the legend
        Legend legend = liveECGSignalchart.getLegend();
        legend.setEnabled(true);
        legend.setTextSize(12f);
        legend.setForm(Legend.LegendForm.LINE);

        // Customize X axis
        XAxis xAxis = liveECGSignalchart.getXAxis();
        xAxis.setDrawLabels(false);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setGranularity(1f);

        // Customize Y axis
        YAxis leftAxis = liveECGSignalchart.getAxisLeft();
        leftAxis.setAxisMaximum(1024f);
        leftAxis.setDrawGridLines(false);

        YAxis rightAxis = liveECGSignalchart.getAxisRight();
        rightAxis.setEnabled(false);

        liveECGSignalchart.getDescription().setEnabled(false);

        // Initial chart refresh
        liveECGSignalchart.invalidate();

        // Initialize sample count and xIndex
        sampleCount = 0;
        xIndex = 0;

        // Initialize histograms
        rrIntervalsHistogram = new int[RR_HIST_NUM_BINS]; // Ensure HISTOGRAM_SIZE is defined
        bpmHistogram = new int[BPM_HIST_NUM_BINS]; // Ensure HISTOGRAM_SIZE is defined
    }

    private void updateGraph(final int value) {
        if (isFinished) return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (sampleCount >= 1000) {
                    lineDataSet.removeEntry(0); // Remove the oldest entry
                } else {
                    sampleCount++;
                }

                lineDataSet.addEntry(new Entry(xIndex++, value));
                lineData.notifyDataChanged();
                liveECGSignalchart.notifyDataSetChanged();
                liveECGSignalchart.invalidate();
                liveECGSignalchart.setVisibleXRangeMaximum(100);
                liveECGSignalchart.moveViewToX(xIndex);
            }
        });
    }

    private void clearGraphData() {
        handler.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                Log.d("ECGGraph", "Clearing graph data");

                lineData.clearValues();
                lineDataSet = new LineDataSet(null, "Live Signal");
                lineDataSet.setDrawValues(false);
                lineDataSet.setDrawCircles(false);
                lineData.addDataSet(lineDataSet);
                lineData.notifyDataChanged();               // Notify the data has changed
                liveECGSignalchart.notifyDataSetChanged();  // Notify the chart data has changed
                liveECGSignalchart.invalidate();            // Refresh the chart
                liveECGSignalchart.moveViewToX(0);

                Log.d("ECGGraph", "Graph data cleared");
            }
        });

        xIndex = 0;                                 // Reset the xIndex
        sampleCount = 0;                            // Reset the sample count
    }

    private void updateBPMHistograms() {
        // Create BarEntries for BPM histogram
        List<BarEntry> bpmEntries = new ArrayList<>();
        for (int i = 0; i < bpmHistogram.length; i++) {
            bpmEntries.add(new BarEntry(i, bpmHistogram[i]));
        }

        // Create BarDataSet for BPM histogram
        BarDataSet bpmDataSet = new BarDataSet(bpmEntries, "BPM Histogram");
        bpmDataSet.setColor(Color.GREEN);

        // Create BarData object and set it to the BarChart
        BarData barData = new BarData(bpmDataSet);
        histogramChart.setData(barData);

        histogramChart.getDescription().setEnabled(false);

        // Refresh chart
        histogramChart.invalidate();
    }

    private void updateRRHistograms() {
        // Create BarEntries for RR histogram
        List<BarEntry> rrEntries = new ArrayList<>();
        for (int i = 0; i < rrIntervalsHistogram.length; i++) {
            rrEntries.add(new BarEntry(i, rrIntervalsHistogram[i]));
        }

        // Create BarDataSet for RR histogram
        BarDataSet rrDataSet = new BarDataSet(rrEntries, "RR Histogram");
        rrDataSet.setColor(Color.rgb(255, 69, 0));

        // Create BarData object and set it to the BarChart
        BarData barData = new BarData(rrDataSet);
        histogramChart.setData(barData);

        histogramChart.getDescription().setEnabled(false);

        // Refresh chart
        histogramChart.invalidate();
    }
}
