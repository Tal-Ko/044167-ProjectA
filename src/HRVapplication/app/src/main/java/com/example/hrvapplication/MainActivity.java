package com.example.hrvapplication;

import static java.lang.Thread.sleep;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.charts.BarChart;
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
    Button startButton;
    Button pauseButton;
    Button finishButton;
    Button btButton;
    Button switchToLiveViewButton;
    Button switchToRRHistViewButton;
    Button switchToBPMHistViewButton;

    private BLEController bleController;
    private String deviceAddress;

    private final Handler handler = new Handler();

    private TextView pulseTextView;
    private TextView htiParameter;
    private TextView RMSSDParameter;
    private TextView SDANNParameter;

    private Runnable updateTextView;

    private static final int BPM_HIST_NUM_BINS = 220;
    private static final int RR_HIST_NUM_BINS = 1200;

    private int[] rrIntervalsHistogram = new int[RR_HIST_NUM_BINS];
    private int[] bpmHistogram = new int[BPM_HIST_NUM_BINS + 1];
    private int lastBpm = 0;
    private int lastCmd = 0;
    private boolean isRunning = false;
    private boolean isFinished = false;

    private LineChart liveECGSignalchart;
    private LineData lineData;
    private LineDataSet lineDataSet;
    private int sampleCount;
    private int xIndex;
    BarChart histogramChart;

    TextView graphTitle;
    TextView ylabel;

    @SuppressLint("MissingInflatedId")
    @Override
    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        pulseTextView = findViewById(R.id.Pulse);
        htiParameter = findViewById(R.id.HTILabel);
        RMSSDParameter = findViewById(R.id.RMSSDLabel);
        SDANNParameter = findViewById(R.id.SDANNLabel);
        graphTitle = findViewById(R.id.graphTitle);
        graphTitle.setText("Live Signal");
        ylabel = findViewById(R.id.liveSignalYLabel);

        // Initialize buttons
        startButton = findViewById(R.id.start_button);
        pauseButton = findViewById(R.id.pause_button);
        finishButton = findViewById(R.id.finish_button);
        btButton = findViewById(R.id.BT_button);
        switchToLiveViewButton = findViewById(R.id.switch_Signalview_button);
        switchToRRHistViewButton = findViewById(R.id.switch_RRview_button);
        switchToBPMHistViewButton = findViewById(R.id.switch_BPMview_button);

        // Disable buttons initially
        startButton.setEnabled(false);
        pauseButton.setEnabled(false);
        finishButton.setEnabled(false);
        switchToLiveViewButton.setEnabled(false);
        switchToRRHistViewButton.setEnabled(false);
        switchToBPMHistViewButton.setEnabled(false);

        startButton.setOnClickListener(v -> {
            try {
                startHRVMeasurement();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        pauseButton.setOnClickListener(v -> pauseHRVMeasurement());
        finishButton.setOnClickListener(v -> {
            try {
                finishHRVMeasurement();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btButton.setOnClickListener(v -> connectToArduinoBT());
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bleController = BLEController.getInstance(this);

        checkBLESupport();
        checkPermissions();

        // Define the Runnable that updates the TextView with a blinking effect
        updateTextView = new Runnable() {
            @Override
            public void run() {
                if (!pauseButton.isEnabled()) {
                    handler.postDelayed(this, 1000);
                    return;
                }

                // Update the TextView with the current number
                pulseTextView.setText(String.valueOf(lastBpm));

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

        // Start the initial update
        handler.post(updateTextView);

        initializeGraph();

        // Get BarChart from layout
        histogramChart = findViewById(R.id.histogramChart);
    }

    private void initializeGraph() {
        liveECGSignalchart = findViewById(R.id.ECGLiveSignal);
        lineDataSet = new LineDataSet(null, "Live Signal");
        lineDataSet.setDrawValues(false);
        lineData = new LineData(lineDataSet);
        liveECGSignalchart.setData(lineData);

        // Customize the legend
        Legend legend = liveECGSignalchart.getLegend();
        legend.setEnabled(true);
        legend.setTextSize(12f);
        legend.setForm(Legend.LegendForm.LINE);

        // Customize X axis
        XAxis xAxis = liveECGSignalchart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setLabelRotationAngle(45);
        xAxis.setDrawGridLines(false);

        Description description = new Description();
        description.setText("Sample Index");
        liveECGSignalchart.setDescription(description);

        // Customize Y axis
        YAxis leftAxis = liveECGSignalchart.getAxisLeft();
        leftAxis.setAxisMaximum(1500f);
        leftAxis.setDrawGridLines(false);

        YAxis rightAxis = liveECGSignalchart.getAxisRight();
        rightAxis.setEnabled(false);

        sampleCount = 0;
        xIndex = 0;

        Arrays.fill(rrIntervalsHistogram, 0);
        Arrays.fill(bpmHistogram, 0);
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
    protected void onResume() {
        super.onResume();

        this.deviceAddress = null;
        this.bleController = BLEController.getInstance(this);
        this.bleController.addBLEControllerListener(this);
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            this.bleController.init();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        isRunning = false;
        this.bleController.removeBLEControllerListener(this);
    }

    private void checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    42);
        }
    }

    private void checkBLESupport() {
        // Check if BLE is supported on the device.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE not supported!", Toast.LENGTH_SHORT).show();
            finish();
        }
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

        switchToLiveView();

        if (isFinished) {
            xIndex = 0;
            sampleCount = 0;
            lastBpm = 0;

            initializeGraph();

            byte[] clearCmd = new byte[]{3};
            lastCmd = 3;
            bleController.sendCommand(clearCmd);

            sleep(500);
            isFinished = false;
        }

        byte[] startCmd = new byte[] {1};
        lastCmd = 1;
        bleController.sendCommand(startCmd);
        isRunning = true;
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    private void pauseHRVMeasurement() {
        startButton.setEnabled(true);
        pauseButton.setEnabled(false);
        finishButton.setEnabled(true);

        byte[] pauseCmd = new byte[] {2};
        lastCmd = 2;
        bleController.sendCommand(pauseCmd);
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

        {
            byte[] pauseCmd = new byte[]{2};
            lastCmd = 2;
            bleController.sendCommand(pauseCmd);
            sleep(500);
        }

        {
            byte[] rmssdCmd = new byte[]{10};
            lastCmd = 10;
            bleController.sendCommand(rmssdCmd);
            sleep(500);
        }

        {
            byte[] sdannCmd = new byte[]{11};
            lastCmd = 11;
            bleController.sendCommand(sdannCmd);
            sleep(500);
        }

        {
            byte[] htiCmd = new byte[]{12};
            lastCmd = 12;
            bleController.sendCommand(htiCmd);
            sleep(500);
        }

        isFinished = true;
    }

    @RequiresPermission(allOf = {"android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT"})
    private void connectToArduinoBT() {
        bleController.connectToDevice(deviceAddress);
    }

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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startButton.setEnabled(true);
            }
        });
    }

    @Override
    public void BLEControllerDisconnected() {
        startButton.setEnabled(false);
        pauseButton.setEnabled(false);
        finishButton.setEnabled(false);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btButton.setEnabled(true);
            }
        });
    }

    @Override
    public void BLEDeviceFound(String name, String address) {
        this.deviceAddress = address;
        startButton.setEnabled(false);
    }

    @Override
    public void BLEDataReceived(byte[] data) {
        String dataStr = new String(data);
    }

    @Override
    public void BLEHRVParametersReceived(double value) {
        switch (lastCmd) {
            case 10:
                htiParameter.setText("HTI: " + value);
                break;
            case 11:
                RMSSDParameter.setText("RMSSD: " + value);
                break;
            case 12:
                SDANNParameter.setText("SDANN: " + value);
                break;
        }
    }

    @Override
    public void BLELiveDataReceived(int data, String characteristic) {
        // Detect already running
        if (!isRunning) {
            isRunning = true;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startButton.callOnClick();
                }
            });
        }

        switch (characteristic) {
            case "BPM":
                lastBpm = data;
                bpmHistogram[data]++;
                break;
            case "SIG":
                updateGraph(data);
                break;
            case "RR":
                rrIntervalsHistogram[data]++;
                break;
        }
    }

    private void updateGraph(final int value) {
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
                liveECGSignalchart.setVisibleXRangeMaximum(50);
                liveECGSignalchart.moveViewToX(xIndex);
            }
        });
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

        // Set description
        Description description = new Description();
        description.setText("RR Histograms");
        histogramChart.setDescription(description);

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
        rrDataSet.setColor(Color.BLUE);

        // Create BarData object and set it to the BarChart
        BarData barData = new BarData(rrDataSet);
        histogramChart.setData(barData);

        // Set description
        Description description = new Description();
        description.setText("RR Histograms");
        histogramChart.setDescription(description);

        // Refresh chart
        histogramChart.invalidate();
    }
}
