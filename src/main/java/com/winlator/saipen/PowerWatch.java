package com.winlator.saipen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import com.winlator.R;

import java.util.function.Consumer;

/**
 * Battery saver and heat while SAI is open: both slow the processor down, and either can come
 * on in the middle of drawing (Samsung turns battery saver on by itself at a set charge, a long
 * session warms the tablet up). DeviceReadiness tells before Start SAI; this tells during:
 * a line in the speed log for every change, and a short message on the screen when it gets
 * slower (battery saver on, or the device hot).
 */
public final class PowerWatch {
    public enum Change { NONE, SAVER_ON, SAVER_OFF, WARM, HOT, COOLED }

    /** What a change from (wasSaver, wasThermal) to (saver, thermal) means. Thermal is
     *  PowerManager's THERMAL_STATUS_*, -1 where the device does not tell. */
    public static Change change(boolean wasSaver, int wasThermal, boolean saver, int thermal) {
        if (saver != wasSaver) return saver ? Change.SAVER_ON : Change.SAVER_OFF;
        int was = heat(wasThermal), now = heat(thermal);
        if (now == was) return Change.NONE;
        if (now < was) return Change.COOLED;
        return now == 2 ? Change.HOT : Change.WARM;
    }

    /** 0 fine (or unknown), 1 warm, 2 hot: DeviceReadiness's levels. */
    static int heat(int thermal) {
        return thermal >= DeviceReadiness.THERMAL_SEVERE ? 2 : thermal >= DeviceReadiness.THERMAL_MODERATE ? 1 : 0;
    }

    /** Shown on the screen: only the changes that make SAI slower. */
    public static boolean worthAMessage(Change c) {
        return c == Change.SAVER_ON || c == Change.HOT;
    }

    public static String words(boolean saver, int thermal) {
        return "battery saver " + (saver ? "on" : "off") + ", heat " + (thermal < 0 ? "?" : thermal)
               + " (0 none, 1 light, 2 moderate, 3 severe)";
    }

    private final Context context;
    private final Consumer<String> log;
    private final PowerManager pm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean saver;
    private int thermal;
    private BroadcastReceiver receiver;
    private PowerManager.OnThermalStatusChangedListener thermalListener;

    private PowerWatch(Context context, Consumer<String> log) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.pm = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        saver = pm != null && pm.isPowerSaveMode();
        thermal = pm != null && Build.VERSION.SDK_INT >= 29 ? pm.getCurrentThermalStatus() : -1;
    }

    /** Starts watching; log gets the state now and then every change. Null without a PowerManager. */
    public static PowerWatch start(Context context, Consumer<String> log) {
        PowerWatch w = new PowerWatch(context, log);
        if (w.pm == null) return null;
        log.accept(words(w.saver, w.thermal));
        w.receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                w.update(w.pm.isPowerSaveMode(), w.thermal);
            }
        };
        w.context.registerReceiver(w.receiver, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        if (Build.VERSION.SDK_INT >= 29) {
            w.thermalListener = status -> w.update(w.saver, status);
            w.pm.addThermalStatusListener(w.context.getMainExecutor(), w.thermalListener);
        }
        return w;
    }

    private void update(boolean newSaver, int newThermal) {
        Change c = change(saver, thermal, newSaver, newThermal);
        saver = newSaver;
        thermal = newThermal;
        if (c == Change.NONE) return;
        log.accept(words(saver, thermal));
        if (!worthAMessage(c)) return;
        int text = c == Change.SAVER_ON ? R.string.saipen_power_saver_on : R.string.saipen_power_hot;
        main.post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

    public void stop() {
        try {
            if (receiver != null) context.unregisterReceiver(receiver);
        }
        catch (IllegalArgumentException ignored) {}
        if (thermalListener != null && Build.VERSION.SDK_INT >= 29) pm.removeThermalStatusListener(thermalListener);
        receiver = null;
        thermalListener = null;
    }
}
