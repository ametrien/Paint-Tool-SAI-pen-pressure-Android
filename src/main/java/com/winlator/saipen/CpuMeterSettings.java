package com.winlator.saipen;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.winlator.R;

/**
 * The processor meter's settings (CpuMeter): its switch and what the limit
 * means. In the settings over SAI as a tab of its own (GesturesOverlay,
 * Cores), and on SAI Pen's screen under Fast cores.
 */
public final class CpuMeterSettings {
    private CpuMeterSettings() {}

    /** Fills `body`; `changed` runs after a change. */
    public static void build(Activity a, LinearLayout body, SharedPreferences prefs, Runnable changed) {
        float dp = a.getResources().getDisplayMetrics().density;
        TypedValue tv = new TypedValue();
        int textColor = a.getTheme().resolveAttribute(R.attr.colorPrimaryText, tv, true) ? tv.data : Color.WHITE;
        body.removeAllViews();
        Switch s = new Switch(a);
        s.setText(R.string.saipen_cpu_meter);
        s.setTextColor(textColor);
        s.setMinHeight((int) (44 * dp));
        s.setChecked(prefs.getBoolean(CpuMeter.PREF_ON, CpuMeter.ON_BY_DEFAULT));
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(CpuMeter.PREF_ON, on).apply();
            changed.run();
        });
        body.addView(s);
        int cores = CpuMeter.coresFor(a);
        TextView note = new TextView(a, null, 0, R.style.SaiPenNote);
        note.setText(a.getString(R.string.saipen_cpu_meter_note,
            BusySai.limitPercent(cores), 100 * cores, (int) (BusySai.FULL_FOR_MS / 1000)));
        body.addView(note);
    }
}
