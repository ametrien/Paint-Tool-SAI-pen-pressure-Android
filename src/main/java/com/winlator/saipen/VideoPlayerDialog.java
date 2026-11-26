package com.winlator.saipen;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import com.winlator.R;

import java.io.File;

/**
 * A timelapse on the whole screen, inside the app: it starts at once, with
 * the usual play, pause and seek bar (a tap shows them). ✕ or Back closes it;
 * "Other app" still hands the video to the device's player.
 */
final class VideoPlayerDialog {
    private VideoPlayerDialog() {}

    static void show(Activity activity, File video, Runnable otherApp) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        float dp = activity.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);

        VideoView view = new VideoView(activity);
        root.addView(view, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        MediaController controls = new MediaController(activity);
        controls.setAnchorView(view);
        view.setMediaController(controls);
        view.setVideoPath(video.getPath());
        view.setOnPreparedListener(mp -> {
            view.start();
            controls.show(2500);
        });
        view.setOnErrorListener((mp, what, extra) -> {
            dialog.dismiss();
            if (otherApp != null) otherApp.run();
            return true;
        });

        TextView close = pill(activity, "✕", dp);
        close.setContentDescription(activity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> dialog.dismiss());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, (int) (16 * dp), (int) (16 * dp), 0);
        root.addView(close, cp);

        if (otherApp != null) {
            TextView other = pill(activity, activity.getString(R.string.saipen_video_other_app), dp);
            other.setOnClickListener(v -> {
                dialog.dismiss();
                otherApp.run();
            });
            FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            op.setMargins((int) (16 * dp), (int) (16 * dp), 0, 0);
            root.addView(other, op);
        }

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> view.stopPlayback());
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        dialog.show();
    }

    private static TextView pill(Activity a, String text, float dp) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(18 * dp);
        t.setBackground(bg);
        t.setClickable(true);
        t.setFocusable(true);
        t.setVisibility(View.VISIBLE);
        return t;
    }
}
