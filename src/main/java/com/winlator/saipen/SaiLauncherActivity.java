package com.winlator.saipen;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's only screen. Opening SAI Pen starts SAI right away when it is
 * ready; this screen shows only on the first run (Wine is unpacked, SAI is
 * looked for), when SAI is missing or incomplete, and after SAI exits.
 *
 * Winlator's own screens (containers, shortcuts, settings) stay reachable
 * through the small "Winlator settings" link, for us and for debugging.
 */
public class SaiLauncherActivity extends AppCompatActivity {
    private static final int REQUEST_STORAGE = 1;
    private static final int REQUEST_FOLDER = 2;
    private static final int REQUEST_LICENCE = 3;
    private static final int REQUEST_BRUSHES = 4;
    private static final int REQUEST_STATS_EXPORT = 5;
    private static final int REQUEST_PC_SETTINGS = 6;

    /** The brushes card's tiles, in SettingsContents' kind order. */
    private static final int[] KIND_TILES = {R.id.SaiPenKind0, R.id.SaiPenKind1, R.id.SaiPenKind2,
                                             R.id.SaiPenKind3, R.id.SaiPenKind4};
    private static final int[] KIND_COUNTS = {R.id.SaiPenKind0Count, R.id.SaiPenKind1Count, R.id.SaiPenKind2Count,
                                              R.id.SaiPenKind3Count, R.id.SaiPenKind4Count};
    private static final String PREF_SAI_DIR = "saipen_sai_dir";
    private static final String PREF_CONTAINER_ID = "saipen_container_id";
    /** Wine's screen height chosen on the screen (Sharpness); width follows
     *  the display's shape. */
    private static final String PREF_SCREEN_HEIGHT = "saipen_screen_height";
    /** The first-run question about SAI's licence was answered. */
    private static final String PREF_LICENCE_ASKED = "saipen_licence_asked";
    private static final String LICENCE_SITE = "https://www.systemax.jp/en/license.html";
    /** Allowed to look for sai*.slc in Downloads and SAI's folder (asked on
     *  the first run). Without it no certificate is taken by itself. */
    private static final String PREF_LICENCE_SEARCH = "saipen_licence_search";
    /** When this install made its container, and so its own System ID
     *  (DeviceSerial). Not there for a container made before that: those
     *  keep their System ID and every certificate on the device may fit. */
    private static final String PREF_OWN_ID_SINCE = "saipen_own_id_since";
    /** Start SAI as soon as SAI Pen opens. Off unless the person turns it on:
     *  the screen with the settings must be reachable (the owner, 2026-11-19). */
    private static final String PREF_AUTOSTART = "saipen_autostart";
    /** A light dialog with dark text, set outright: under Winlator's dark
     *  app theme the dialogs came out grey on white, the title unreadable
     *  (seen on the Galaxy S24 Ultra, 2026-11-18). */
    private static final String TAG = "SaiPen";
    static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    private static final String SAI_SITE = "https://www.systemax.jp/en/sai/devdept.html";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Reads the drawings' thumbnails; apart from `worker`, which may be busy unpacking Wine. */
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    private int recentRound;
    /** A drawing tapped in the row of recent ones: SAI opens it (launchNow). */
    private File drawingToOpen;
    private SharedPreferences prefs;
    private TextView tvStatus, tvSaiPath, tvLicenceHint;
    /** The licence line: its number behind a spoiler, so a screenshot of
     *  the screen does not give it away (the owner, 2026-11-20). */
    private TextView tvLicence;
    private View btnLicence;
    private TextView btnLicenceTitle;
    private ProgressBar progress;
    private Button btnVideo;
    private Button btnDiscardFrames;
    private boolean makingVideo;
    /** Run once SAI is really about to start (launchNow): the Drawings
     *  window stays up, saying "Starting SAI…", until then. */
    private Runnable onSaiStarting;
    private Button btnStart, btnUnzip, btnChoose, btnSite, btnSearch;
    /** How SAI is found: "auto" (search the device) or "manual" (the person
     *  picks the folder). Unset until the first start asks. */
    private static final String PREF_SEARCH = "saipen_search";
    /** Record a timelapse of the canvas (our DLL inside SAI does it). */
    private static final String PREF_TIMELAPSE = "saipen_timelapse";
    private static final int[] FAST_CORES_NAMES = {
        R.string.saipen_cores_drawing, R.string.saipen_cores_all, R.string.saipen_cores_off};
    private static final int[] FAST_CORES_WHAT = {
        R.string.saipen_cores_drawing_what, R.string.saipen_cores_all_what, R.string.saipen_cores_off_what};
    /** When the DLL takes a frame: after the pen rests this long. It never
     *  captures while the pen is down, only in the gaps between strokes.
     *  Its default 1000 ms, then 500 ms (0.4.1), put a run of quick strokes
     *  into one frame; the owner wants every stroke (2026-11-19). 40 ms is
     *  the DLL's lowest (its settle time), above its 30 ms stroke-end
     *  debounce, so "every stroke" means every stroke with a lift between. */
    private static final String PREF_TIMELAPSE_PAUSE = "saipen_timelapse_pause";
    private static final int[] TIMELAPSE_PAUSES_MS = {40, 300, 1000};
    private static final int[] TIMELAPSE_PAUSE_NAMES = {
        R.string.saipen_frame_every_stroke, R.string.saipen_frame_short_pause, R.string.saipen_frame_long_pause};
    /** Drawing without pauses: a frame at the first gap after this long. The
     *  DLL's default is 10 s, its lowest 1000 ms. A capture of the tablet's
     *  512 px canvas takes 13 to 25 ms (log, 2026-11-19). */
    private static final String TIMELAPSE_MAX_WAIT_MS = "1000";

    private Container container;
    private File saiDir;          /* a complete SAI folder, or null */
    private File zip;             /* a SAI zip to offer unpacking, or null */
    private boolean autoLaunch;
    private AlertDialog licenceDialog;
    private TextView tvBrushes;
    private View btnOpenBrushes;
    private View brushesActions;
    private View btnStats;
    private String statsCsv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        boolean afterSettings = intent.hasExtra("selected_menu_item_id");
        if (afterSettings) {
            /* Winlator restarts the app after some of its settings and
             * means its own screen: hand the restart on to it */
            Intent main = new Intent(this, MainActivity.class);
            main.putExtras(intent);
            startActivity(main);
            finish();
            return;
        }
        boolean afterSession = intent.hasExtra("container_id");
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoLaunch = savedInstanceState == null && prefs.getBoolean(PREF_AUTOSTART, FirstRun.AUTOSTART)
            && SaiLocator.shouldAutoLaunch(true, afterSession, false, true);

        setContentView(R.layout.saipen_launcher);
        tvStatus = findViewById(R.id.SaiPenStatus);
        tvSaiPath = findViewById(R.id.SaiPenPath);
        progress = findViewById(R.id.SaiPenProgress);
        btnStart = findViewById(R.id.SaiPenStart);
        btnUnzip = findViewById(R.id.SaiPenUnzip);
        btnChoose = findViewById(R.id.SaiPenChoose);
        btnSite = findViewById(R.id.SaiPenSite);
        btnSearch = findViewById(R.id.SaiPenSearch);
        /* the button itself is the yes to a search */
        btnSearch.setOnClickListener(v -> scanDevice());
        tvLicence = findViewById(R.id.SaiPenLicenceStatus);
        tvLicenceHint = findViewById(R.id.SaiPenLicenceHint);
        btnLicence = findViewById(R.id.SaiPenAddLicence);
        btnLicenceTitle = findViewById(R.id.SaiPenAddLicenceTitle);
        btnLicence.setOnClickListener(v -> askAboutLicence());
        tvBrushes = findViewById(R.id.SaiPenBrushes);
        btnStats = findViewById(R.id.SaiPenOpenStats);
        btnStats.setOnClickListener(v -> openStats());
        brushesActions = findViewById(R.id.SaiPenBrushesActions);
        findViewById(R.id.SaiPenBrushesInfo).setOnClickListener(v -> brushesInfo());
        /* what each of the two rows does, behind its info button instead of under it */
        findViewById(R.id.SaiPenPcSettingsInfo).setOnClickListener(v -> rowInfo(R.string.saipen_pc_settings, R.string.saipen_pc_settings_short));
        findViewById(R.id.SaiPenAddBrushesInfo).setOnClickListener(v -> rowInfo(R.string.saipen_add_pack, R.string.saipen_add_pack_short));
        findViewById(R.id.SaiPenLicenceInfo).setOnClickListener(v -> licenceInfo());
        /* the timelapse is experimental: the yellow triangle says so, just the text */
        findViewById(R.id.SaiPenTimelapseWarn).setOnClickListener(v ->
            new AlertDialog.Builder(this, DIALOG_THEME).setMessage(R.string.saipen_timelapse_experimental)
                .setPositiveButton(android.R.string.ok, null).show());
        for (int k = 0; k < SettingsContents.KINDS; k++) {
            final int kind = k;
            findViewById(KIND_TILES[k]).setOnClickListener(v -> openInSai(kind));
        }
        findViewById(R.id.SaiPenBrowseAllRow).setOnClickListener(v -> openBrowser(BrowseDialog.ALL));
        findViewById(R.id.SaiPenPcSettings).setOnClickListener(v -> askForPcSettings());
        findViewById(R.id.SaiPenAddBrushes).setOnClickListener(v -> pickBrushes());
        btnOpenBrushes = findViewById(R.id.SaiPenOpenBrushes);
        findViewById(R.id.SaiPenOpenBrushesRow).setOnClickListener(v -> openBrushesFolder());
        findViewById(R.id.SaiPenOpenDrawings).setOnClickListener(v -> openDrawingsFolder());
        findViewById(R.id.SaiPenDrivesInfo).setOnClickListener(v -> {
            List<String> cards = removableVolumes();
            String now = cards.isEmpty() ? getString(R.string.saipen_drives_info_none)
                                         : getString(R.string.saipen_drives_info_some, cards.size());
            new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_drives_info_title)
                .setMessage(getString(R.string.saipen_drives_info_text, now))
                .setPositiveButton(android.R.string.ok, null).show();
        });
        findViewById(R.id.SaiPenAllDrawings).setOnClickListener(v -> openAllDrawings());
        android.widget.CompoundButton autostart = findViewById(R.id.SaiPenAutostart);
        autostart.setChecked(prefs.getBoolean(PREF_AUTOSTART, FirstRun.AUTOSTART));
        autostart.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(PREF_AUTOSTART, on).apply());
        setUpSharpness();
        setUpLanguage();
        setUpFastCores();
        setUpProcessors();
        setUpScreenBuffer();
        android.widget.CompoundButton timelapse = findViewById(R.id.SaiPenTimelapse);
        timelapse.setChecked(prefs.getBoolean(PREF_TIMELAPSE, FirstRun.TIMELAPSE));
        setUpTimelapsePause(timelapse);
        btnVideo = findViewById(R.id.SaiPenMakeVideo);
        btnVideo.setOnClickListener(v -> makeVideo());
        btnDiscardFrames = findViewById(R.id.SaiPenDiscardFrames);
        btnDiscardFrames.setOnClickListener(v -> askDiscardFrames());
        findViewById(R.id.SaiPenOpenTimelapses).setOnClickListener(v -> openTimelapses());

        btnStart.setOnClickListener(v -> {
            drawingToOpen = null;
            launch();
        });
        btnUnzip.setOnClickListener(v -> unzip());
        /* one button for "another SAI": with SAI found it lists every SAI
         * on the device and "choose the folder myself"; without, it is the
         * folder picker next to "Find SAI on the device" */
        btnChoose.setOnClickListener(v -> {
            if (saiDir != null) changeSai();
            else chooseFolder();
        });
        btnSite.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SAI_SITE))));

        TextView credits = findViewById(R.id.SaiPenCredits);
        credits.setText(Html.fromHtml(getString(R.string.saipen_credits), Html.FROM_HTML_MODE_LEGACY));
        credits.setMovementMethod(LinkMovementMethod.getInstance());
        findViewById(R.id.SaiPenAbout).setOnClickListener(v -> showNotice());
        findViewById(R.id.SaiPenLicences).setOnClickListener(v -> showLicences());
        findViewById(R.id.SaiPenGestures).setOnClickListener(v -> new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_gestures).setMessage(R.string.saipen_gestures_text)
            .setPositiveButton(android.R.string.ok, null).show());
        findViewById(R.id.SaiPenCheatSheet).setOnClickListener(v -> CheatSheetDialog.show(this, prefs));
        findViewById(R.id.SaiPenSettings).setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));

        TextView tvVersion = findViewById(R.id.SaiPenVersion);
        if (tvVersion != null) {
            try {
                String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                tvVersion.setText(getString(R.string.saipen_version, versionName));
            }
            catch (Exception ignored) {}
        }

        fold(R.id.SaiPenLanguageRow, R.id.SaiPenLanguageBody, R.id.SaiPenLanguageArrow);
        fold(R.id.SaiPenSharpnessRow, R.id.SaiPenSharpnessBody, R.id.SaiPenSharpnessArrow);
        fold(R.id.SaiPenCoresRow, R.id.SaiPenCoresBody, R.id.SaiPenCoresArrow);
        fold(R.id.SaiPenProcessorsRow, R.id.SaiPenProcessorsBody, R.id.SaiPenProcessorsArrow);
        fold(R.id.SaiPenBufferRow, R.id.SaiPenBufferBody, R.id.SaiPenBufferArrow);
        fold(R.id.SaiPenPressureRow, R.id.SaiPenPressureBody, R.id.SaiPenPressureArrow);
        fold(R.id.SaiPenPalmRow, R.id.SaiPenPalmBody, R.id.SaiPenPalmArrow);
        fold(R.id.SaiPenHomeRow, R.id.SaiPenHomeBody, R.id.SaiPenHomeArrow);
        fold(R.id.SaiPenKeysRow, R.id.SaiPenKeysBody, R.id.SaiPenKeysArrow);
        fold(R.id.SaiPenGesturesRow, R.id.SaiPenGesturesBody, R.id.SaiPenGesturesArrow);

        /* the cheat sheet by itself the first time this screen opens (not
         * when SAI starts right away: then the screen is not seen) */
        if (savedInstanceState == null && !autoLaunch
                && !prefs.getBoolean(CheatSheet.PREF_CHEAT_SHEET_SEEN, FirstRun.CHEAT_SHEET_SEEN))
            CheatSheetDialog.show(this, prefs);

        startup();
    }

    /** A settings row that opens or closes what is under it; the arrow turns down when open. */
    private void fold(int rowId, int bodyId, int arrowId) {
        View body = findViewById(bodyId), arrow = findViewById(arrowId);
        findViewById(rowId).setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(open ? 90 : 0).setDuration(150).start();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        SaiPenSetup.scanDrawings(this);
        showVideoButton();
        autoMakeVideo();
        showStats();
        showReadiness();
        showRecent();
        /* back from SAI: go on making the browser's previews */
        File settings = SaiPenSetup.settingsFolder();
        if (container != null && BrushImport.hasSaiSettings(settings)) BrowseThumbs.get(this).start(settings, false);
        registerReceiver(powerSaveChanged, new android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
    }

    /* the browser's previews are made only while this screen is shown:
       never while SAI draws (BrowseThumbs) */
    @Override
    protected void onPause() {
        super.onPause();
        BrowseThumbs.get(this).pause();
        try {
            unregisterReceiver(powerSaveChanged);
        }
        catch (IllegalArgumentException ignored) {}
    }

    /** Battery saver turned on or off while this screen is open (or from its settings page). */
    private final android.content.BroadcastReceiver powerSaveChanged = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            showReadiness();
        }
    };

    /** Is the device ready to draw (DeviceReadiness): a summary line and a
     *  bar above Start SAI, every check folded under it. */
    private boolean readyListOpen = false;

    private void showReadiness() {
        View card = findViewById(R.id.SaiPenReadyCard);
        if (card == null) return;
        /* only once SAI is found: during the first setup it is one thing
         * too many to read (the owner, 2026-11-23) */
        if (saiDir == null || SaiLocator.exeBits(new File(saiDir, "sai2.exe")) == 32) {
            card.setVisibility(View.GONE);   /* nor for a 32-bit SAI: it won't start anyway */
            return;
        }
        DeviceReadiness.Input in = new DeviceReadiness.Input();
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            in.availMb = mi.availMem >> 20;
            in.totalMb = mi.totalMem >> 20;
            in.lowMemory = mi.lowMemory;
        }
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        in.powerSave = pm != null && pm.isPowerSaveMode();
        in.thermal = pm != null && android.os.Build.VERSION.SDK_INT >= 29 ? pm.getCurrentThermalStatus() : -1;
        try {
            in.freeDiskMb = new android.os.StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes() >> 20;
        } catch (RuntimeException ignored) {}
        in.sharpness = screenHeight();
        SpeedHistory history = SpeedHistory.fromFolder(new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), SpeedSession.FOLDER));
        in.bySharpness = history.bySharpness;
        in.pastMs = history.recentMs;
        List<DeviceReadiness.Check> checks = DeviceReadiness.checks(in);

        DeviceReadiness.Level overall = DeviceReadiness.overall(checks);
        int n = DeviceReadiness.toImprove(checks);
        TextView title = findViewById(R.id.SaiPenReadyTitle), sub = findViewById(R.id.SaiPenReadySub);
        switch (overall) {
            case LAG:
                title.setText(R.string.saipen_ready_title_lag);
                sub.setText(getResources().getQuantityString(R.plurals.saipen_ready_sub_lag, n, n));
                break;
            case BETTER:
                title.setText(R.string.saipen_ready_title_better);
                sub.setText(getResources().getQuantityString(R.plurals.saipen_ready_sub_better, n, n));
                break;
            default:
                title.setText(R.string.saipen_ready_title_ok);
                sub.setText(R.string.saipen_ready_sub_ok);
        }

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout bar = findViewById(R.id.SaiPenReadyBar);
        bar.removeAllViews();
        for (int i = 0; i < checks.size(); i++) {
            View piece = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) lp.setMarginStart((int) (3 * dp));
            piece.setLayoutParams(lp);
            piece.setBackground(rounded(levelColor(checks.get(i).level), 2 * dp));
            bar.addView(piece);
        }

        LinearLayout list = findViewById(R.id.SaiPenReadyList);
        list.removeAllViews();
        java.util.Locale loc = java.util.Locale.getDefault();
        for (DeviceReadiness.Check c : checks) list.addView(readyRow(c, in, loc, dp));
        list.setVisibility(readyListOpen ? View.VISIBLE : View.GONE);
        TextView arrow = findViewById(R.id.SaiPenReadyArrow);
        arrow.setRotation(readyListOpen ? 90 : 0);
        findViewById(R.id.SaiPenReadyHead).setOnClickListener(v -> {
            readyListOpen = !readyListOpen;
            list.setVisibility(readyListOpen ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(readyListOpen ? 90 : 0).setDuration(150).start();
        });
        card.setVisibility(View.VISIBLE);
    }

    private static int levelColor(DeviceReadiness.Level level) {
        switch (level) {
            case OK: return 0xFF4CAF50;
            case BETTER: return 0xFFFFB74D;
            case LAG: return 0xFFE57373;
            default: return 0xFF4A5058;
        }
    }

    private static android.graphics.drawable.GradientDrawable rounded(int color, float radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** One check: a dot, its name, its number, and what helps if it isn't fine. */
    private View readyRow(DeviceReadiness.Check c, DeviceReadiness.Input in, java.util.Locale loc, float dp) {
        String name, value, help = null;
        switch (c.kind) {
            case MEMORY:
                name = getString(R.string.saipen_ready_memory_name);
                value = c.level == DeviceReadiness.Level.UNKNOWN ? getString(R.string.saipen_ready_unknown)
                    : getString(R.string.saipen_ready_memory_value, gb(in.availMb, loc), gb(in.totalMb, loc));
                help = getString(R.string.saipen_ready_memory_help);
                break;
            case BATTERY:
                name = getString(R.string.saipen_ready_battery_name);
                value = getString(in.powerSave ? R.string.saipen_ready_battery_saver : R.string.saipen_ready_battery_ok);
                help = getString(R.string.saipen_ready_battery_help);
                break;
            case TEMPERATURE:
                name = getString(R.string.saipen_ready_heat_name);
                value = getString(c.level == DeviceReadiness.Level.UNKNOWN ? R.string.saipen_ready_unknown
                    : c.level == DeviceReadiness.Level.LAG ? R.string.saipen_ready_heat_hot
                    : c.level == DeviceReadiness.Level.BETTER ? R.string.saipen_ready_heat_warm : R.string.saipen_ready_heat_ok);
                help = getString(R.string.saipen_ready_heat_help);
                break;
            case STORAGE:
                name = getString(R.string.saipen_ready_storage_name);
                value = c.level == DeviceReadiness.Level.UNKNOWN ? getString(R.string.saipen_ready_unknown)
                    : getString(R.string.saipen_ready_storage_value, gb(in.freeDiskMb, loc));
                help = getString(R.string.saipen_ready_storage_help);
                break;
            case SHARPNESS:
                name = getString(R.string.saipen_ready_sharp_name);
                value = c.currentMs >= 0 ? getString(R.string.saipen_ready_sharp_ms, in.sharpness, c.currentMs)
                    : getString(R.string.saipen_ready_sharp_value, in.sharpness);
                if (c.level == DeviceReadiness.Level.BETTER)
                    help = getString(R.string.saipen_ready_sharp_help, c.fasterSharpness, c.fasterMs, c.currentMs);
                break;
            default:
                name = getString(R.string.saipen_ready_past_name);
                value = in.pastMs >= 0 ? getString(R.string.saipen_ready_past_value, in.pastMs)
                    : getString(R.string.saipen_ready_past_none);
                help = getString(R.string.saipen_ready_past_help);
        }
        boolean showHelp = c.level == DeviceReadiness.Level.BETTER || c.level == DeviceReadiness.Level.LAG;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (8 * dp), 0, (int) (2 * dp));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (9 * dp), (int) (9 * dp));
        dl.setMarginEnd((int) (10 * dp));
        dot.setLayoutParams(dl);
        dot.setBackground(rounded(levelColor(c.level), 4.5f * dp));
        row.addView(dot);
        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(14);
        nameView.setTextColor(0xFFFFFFFF);
        row.addView(nameView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(13);
        valueView.setTextColor(0xB3FFFFFF);
        row.addView(valueView);
        box.addView(row);
        if (showHelp && help != null) {
            TextView helpView = new TextView(this);
            if (c.kind == DeviceReadiness.Kind.BATTERY && in.powerSave) {
                /* the app may not turn it off, but it can open the page where you do */
                helpView.setText(help + " " + getString(R.string.saipen_ready_battery_open));
                helpView.setOnClickListener(v -> openBatterySaverSettings());
            }
            else helpView.setText(help);
            helpView.setTextSize(12);
            helpView.setTextColor(levelColor(c.level));
            helpView.setPadding((int) (19 * dp), (int) (2 * dp), 0, 0);
            box.addView(helpView);
        }
        return box;
    }

    private void openBatterySaverSettings() {
        for (String action : new String[]{android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS,
                                          android.provider.Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new android.content.Intent(action));
                return;
            }
            catch (android.content.ActivityNotFoundException ignored) {}
        }
    }

    private static String gb(long mb, java.util.Locale loc) {
        return String.format(loc, "%.1f", mb / 1024f);
    }

    /* ---- how much you draw (DrawingStats) ---- */

    /** Today, the last 7 days and the days in a row on the card's tiles
     *  ("—" before anything was drawn); read off the screen's thread. */
    private void showStats() {
        File file = new File(getFilesDir(), DrawingStats.FILE);
        worker.execute(() -> {
            java.util.Map<String, DrawingStats.Day> days = DrawingStats.load(file).days();
            java.time.LocalDate today = java.time.LocalDate.now();
            DrawingStats.Day day = DrawingStats.lastDays(days, today, 1);
            DrawingStats.Day week = DrawingStats.lastDays(days, today, 7);
            int streak = DrawingStats.streak(days, today);
            ui.post(() -> {
                if (isFinishing()) return;
                ((TextView) findViewById(R.id.SaiPenStatToday)).setText(tile(day));
                ((TextView) findViewById(R.id.SaiPenStatWeek)).setText(tile(week));
                ((TextView) findViewById(R.id.SaiPenStatStreak)).setText(streak == 0 ? "—"
                    : getResources().getQuantityString(R.plurals.saipen_days, streak, streak));
            });
        });
    }

    /** A tile's time: short enough for a third of a phone's width. */
    private String tile(DrawingStats.Day d) {
        if (d.strokes == 0) return "—";
        return d.activeSeconds < 60 ? getString(R.string.saipen_under_minute_short) : duration(d.activeSeconds);
    }

    private String duration(double seconds) {
        int m = (int) (seconds / 60);
        if (m < 1) return getString(R.string.saipen_stats_under_minute);
        if (m < 60) return getString(R.string.saipen_stats_min, m);
        return m % 60 == 0 ? getString(R.string.saipen_stats_h, m / 60)
                           : getString(R.string.saipen_stats_h_min, m / 60, m % 60);
    }

    /** The full port of the macOS Statistics tab, loaded off the screen thread. */
    private void openStats() {
        File file = new File(getFilesDir(), DrawingStats.FILE);
        worker.execute(() -> {
            java.util.Map<String, DrawingStats.Day> days = DrawingStats.load(file).days();
            ui.post(() -> {
                if (!isFinishing()) StatisticsDialog.show(this, days, this::exportStatsCsv);
            });
        });
    }

    private void exportStatsCsv(String csv) {
        exportStatsCsv(csv, "Drawing statistics.csv");
    }

    /** Also one drawing's statistics, from its Statistics window (DrawingsDialog). */
    void exportStatsCsv(String csv, String fileName) {
        statsCsv = csv;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(intent, REQUEST_STATS_EXPORT);
    }

    /* ---- timelapse: frames from SAI into a video ---- */

    /** The button shows when SAI has recorded frames. */
    private void showVideoButton() {
        if (btnVideo == null || container == null || makingVideo) return;
        int n = TimelapseVideo.countAll(container.getRootDir());
        btnVideo.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        btnVideo.setEnabled(true);
        /* about how long the video would be: the frames at 12 a second */
        btnVideo.setText(getString(R.string.saipen_make_video, n, Math.max(1, Math.round(n / (float) TimelapseVideo.FPS))));
        btnDiscardFrames.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        btnDiscardFrames.setEnabled(true);
    }

    /** No video this time: the frames go, and the next timelapse starts from scratch. */
    private void askDiscardFrames() {
        if (container == null || makingVideo) return;
        File root = container.getRootDir();
        int n = TimelapseVideo.countAll(root);
        if (n == 0) { showVideoButton(); return; }
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setMessage(getString(R.string.saipen_discard_frames_ask, n))
            .setPositiveButton(R.string.saipen_discard_frames_yes, (d, w) -> {
                makingVideo = true;   /* keeps both buttons still while the files go */
                btnVideo.setEnabled(false);
                btnDiscardFrames.setEnabled(false);
                worker.execute(() -> {
                    int left = TimelapseVideo.discardAll(root);
                    if (left > 0) Log.w(TAG, "timelapse: " + left + " file(s) could not be deleted");
                    runOnUiThread(() -> {
                        makingVideo = false;
                        showVideoButton();
                    });
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Back from SAI with frames recorded: the video is made by itself, no
     *  button needed (the Mac makes it as frames come; here it is made once
     *  SAI has closed, from frames already packed while drawing). Not while
     *  SAI still runs: SAI Pen's screen can be opened over a running SAI. */
    private void autoMakeVideo() {
        if (container == null || makingVideo) return;
        File root = container.getRootDir();
        worker.execute(() -> {
            if (SaiProcessWatch.isRunning(new File("/proc"), SaiLocator.EXE)) return;
            if (TimelapseVideo.countAll(root) == 0) return;
            runOnUiThread(() -> {
                if (!isFinishing() && !makingVideo) makeVideo(true);
            });
        });
    }

    private void makeVideo() {
        makeVideo(false);
    }

    /** `auto`: made by itself (autoMakeVideo): a short note at the end
     *  instead of a dialog to close; a same-drawing question still asks. */
    private void makeVideo(boolean auto) {
        if (container == null || makingVideo) return;
        makingVideo = true;
        btnVideo.setEnabled(false);
        btnDiscardFrames.setEnabled(false);
        File root = container.getRootDir();
        worker.execute(() -> {
            String message;
            DrawingLibrary.Pending question = null;
            DrawingLibrary library = null;
            try {
                TimelapseVideo.Made result = TimelapseVideo.makeAll(root, (done, total) -> runOnUiThread(
                    () -> btnVideo.setText(getString(R.string.saipen_making_video, done, total))),
                    CanvasTime.load(new File(getFilesDir(), CanvasTime.FILE)));
                List<File> made = result.videos;
                String[] paths = new String[made.size()];
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < paths.length; i++) {
                    paths[i] = made.get(i).getAbsolutePath();
                    names.append(i > 0 ? "\n" : "").append(made.get(i).getName());
                }
                /* so the gallery and the Files app see them at once */
                if (paths.length > 0) android.media.MediaScannerConnection.scanFile(this, paths, null, null);
                message = paths.length > 0 ? getString(R.string.saipen_video_done, names) : "";
                if (!result.skipped.isEmpty()) {
                    StringBuilder skipped = new StringBuilder();
                    for (String s : result.skipped) skipped.append(skipped.length() > 0 ? ", " : "").append(s);
                    message += (message.isEmpty() ? "" : "\n\n") + getString(R.string.saipen_video_skipped, skipped);
                }
                library = result.library;
                if (library != null) question = library.questionAbout(result.filed);
            }
            catch (Exception e) {
                Log.e(TAG, "timelapse video", e);
                message = getString(R.string.saipen_video_failed, String.valueOf(e.getMessage()));
            }
            final String text = message;
            final DrawingLibrary.Pending q = question;
            final DrawingLibrary lib = library;
            runOnUiThread(() -> {
                makingVideo = false;
                showVideoButton();
                showRecent();
                if (isFinishing()) return;
                if (auto) {
                    if (!text.isEmpty()) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                    if (q != null) askContinues(lib, q, null);
                    return;
                }
                new AlertDialog.Builder(this, DIALOG_THEME).setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.saipen_my_timelapses, (d, w) -> openTimelapses())
                    .setOnDismissListener(d -> { if (q != null) askContinues(lib, q, null); })
                    .show();
            });
        });
    }

    /** The Mac's question: a session that may continue a drawing was filed
     *  as its own; one tap joins it, "no" is never asked again. */
    void askContinues(DrawingLibrary lib, DrawingLibrary.Pending q, Runnable after) {
        DrawingLibrary.Drawing to = lib.drawing(q.candidateId);
        if (to == null || isFinishing()) return;
        /* the two pictures the question is about, side by side: how the
           earlier drawing ended, and how this session began */
        DrawingLibrary.Drawing from = lib.drawing(q.drawingId);
        int px = (int) (220 * getResources().getDisplayMetrics().density);
        worker.execute(() -> {
            File stillFile = TimelapseShelf.still(to);
            android.graphics.Bitmap ended = stillFile.isFile() ? pictureFile(stillFile, px) : null;
            android.graphics.Bitmap began = null;
            if (from != null) {
                for (DrawingLibrary.Piece p : from.pieces) {
                    if (!p.file.equals(q.pieceFile)) continue;
                    android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                    try {
                        r.setDataSource(TimelapseShelf.pieceFile(from, p).getPath());
                        began = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    }
                    catch (RuntimeException e) {
                        Log.w(TAG, "timelapse: no first frame for the question: " + e.getMessage());
                    }
                    finally {
                        try { r.release(); } catch (Exception ignored) {}
                    }
                }
            }
            android.graphics.Bitmap first = began;
            ui.post(() -> showContinues(lib, q, to, ended, first, after));
        });
    }

    /** One picture with its caption, for the same-drawing question. */
    private View captioned(android.graphics.Bitmap b, String caption) {
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.ImageView img = new android.widget.ImageView(this);
        img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(0xFF24282C);
        if (b != null) img.setImageBitmap(b);
        col.addView(img, new android.widget.LinearLayout.LayoutParams(-1, (int) (180 * dp)));
        TextView t = new TextView(this);
        t.setText(caption);
        t.setTextSize(13);
        t.setTextColor(StatisticsDialog.TEXT2);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, (int) (6 * dp), 0, 0);
        col.addView(t);
        return col;
    }

    private void showContinues(DrawingLibrary lib, DrawingLibrary.Pending q, DrawingLibrary.Drawing to,
                               android.graphics.Bitmap ended, android.graphics.Bitmap began, Runnable after) {
        if (isFinishing()) return;
        float dp = getResources().getDisplayMetrics().density;
        String name = to.title.isEmpty() ? to.folder : to.title;
        String when = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            .format(new java.util.Date(DrawingLibrary.lastDrawn(to)));
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, (int) (8 * dp), pad, 0);
        TextView msg = new TextView(this);
        msg.setText(getString(R.string.saipen_continues, name, when));
        msg.setTextSize(15);
        msg.setTextColor(StatisticsDialog.TEXT);
        box.addView(msg);
        android.widget.LinearLayout pics = new android.widget.LinearLayout(this);
        android.widget.LinearLayout.LayoutParams half = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        half.setMargins(0, (int) (12 * dp), (int) (6 * dp), 0);
        pics.addView(captioned(ended, getString(R.string.saipen_continues_ended, name)), half);
        android.widget.LinearLayout.LayoutParams half2 = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        half2.setMargins((int) (6 * dp), (int) (12 * dp), 0, 0);
        pics.addView(captioned(began, getString(R.string.saipen_continues_began)), half2);
        box.addView(pics);
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_continues_title)
            .setView(box)
            .setPositiveButton(R.string.saipen_continues_yes, (d, w) -> worker.execute(() -> {
                try {
                    DrawingLibrary.Drawing joined = TimelapseShelf.accept(lib, q);
                    List<String> paths = new ArrayList<>();
                    for (int i = 0; joined != null && i < 8; i++) {
                        File j = TimelapseShelf.joined(joined, i);
                        if (j.isFile()) paths.add(j.getAbsolutePath());
                    }
                    if (!paths.isEmpty()) android.media.MediaScannerConnection.scanFile(this, paths.toArray(new String[0]), null, null);
                }
                catch (Exception e) {
                    Log.e(TAG, "timelapse: joining", e);
                }
                if (after != null) runOnUiThread(after);
            }))
            .setNegativeButton(R.string.saipen_continues_no, (d, w) -> worker.execute(() -> {
                try {
                    TimelapseShelf.refuse(lib, q);
                }
                catch (Exception e) {
                    Log.e(TAG, "timelapse: refusing", e);
                }
                if (after != null) runOnUiThread(after);
            }))
            .show();
    }

    /** Every drawing's timelapse, as the Mac's Videos tab. */
    private void openTimelapses() {
        worker.execute(() -> {
            DrawingLibrary lib = TimelapseShelf.load();
            ui.post(() -> {
                if (!isFinishing()) TimelapsesDialog.show(this, lib, worker);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
        thumbs.shutdownNow();
    }

    /* ---- first run: files permission, Wine, the container ---- */

    private void startup() {
        if (!hasStoragePermission()) {
            String[] permissions = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this, permissions, REQUEST_STORAGE);
            return;
        }
        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid() || rootFS.getVersion() < RootFSInstaller.LATEST_VERSION) {
            busy(R.string.saipen_installing);
            RootFSInstaller.install(this, this::startup);
            return;
        }
        busy(R.string.saipen_preparing);
        ensureContainer();
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) return;
        if (hasStoragePermission()) startup();
        else idle(getString(R.string.saipen_need_storage));
    }

    /** One container for SAI: the one used before, else the first there is,
     *  else a new one. Its screen gets the display's shape at every start. */
    private void ensureContainer() {
        ContainerManager manager = new ContainerManager(this);
        Container chosen = manager.getContainerById(prefs.getInt(PREF_CONTAINER_ID, 0));
        if (chosen == null && !manager.getContainers().isEmpty()) chosen = manager.getContainers().get(0);
        if (chosen != null) {
            onContainer(chosen);
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("name", "SAI");
            data.put("screenSize", wineScreenSize());
            manager.createContainerAsync(data, created -> {
                if (created == null) idle(getString(R.string.saipen_container_failed));
                else {
                    // a new container: this device's own System ID (DeviceSerial)
                    DeviceSerial.writeIfMissing(this);
                    prefs.edit().putLong(PREF_OWN_ID_SINCE, System.currentTimeMillis()).apply();
                    onContainer(created);
                }
            });
        }
        catch (JSONException e) {
            idle(getString(R.string.saipen_container_failed));
        }
    }

    private void onContainer(Container c) {
        container = c;
        showVideoButton();
        autoMakeVideo();
        prefs.edit().putInt(PREF_CONTAINER_ID, c.id).apply();
        locate();
    }

    private String wineScreenSize() {
        DisplayMetrics dm = realMetrics();
        return SaiLocator.wineScreenSize(dm.widthPixels, dm.heightPixels, screenHeight());
    }

    private int shortSide() {
        DisplayMetrics dm = realMetrics();
        return Math.min(dm.widthPixels, dm.heightPixels);
    }

    /**
     * The whole display, system bars included: SAI's session is full screen.
     * THE TRAP: getResources().getDisplayMetrics() leaves the bars out, and
     * which ones depends on how the tablet is held (1930x1200 one time,
     * 2000x1128 another on the Tab S6 Lite), so Wine's screen came out
     * 1158x720 or 1596x900 instead of the display's 5:3, with black bars.
     */
    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    /** The chosen height, if the display still has it (else the default). */
    private int screenHeight() {
        int shortSide = shortSide();
        int chosen = prefs.getInt(PREF_SCREEN_HEIGHT, 0);
        for (int h : SaiLocator.screenHeights(shortSide)) if (h == chosen) return h;
        return SaiLocator.defaultScreenHeight(shortSide);
    }

    /** Language: SAI's menus (SaiLanguage) and the font for Japanese text
     *  (FontLink). Saved here, put into SAI before it starts (SaiPenSetup). */
    private void setUpLanguage() {
        TextView value = findViewById(R.id.SaiPenLanguageValue);
        Runnable show = () -> {
            String lang = SaiLanguage.target(prefs.getString(SaiLanguage.PREF_CHOICE, null), SaiPenSetup.deviceLanguage());
            value.setText(SaiLanguage.JA.equals(lang) ? "日本語" : "English");
        };
        String deviceLang = SaiLanguage.JA.equals(SaiLanguage.forDevice(SaiPenSetup.deviceLanguage())) ? "日本語" : "English";
        String stored = prefs.getString(SaiLanguage.PREF_CHOICE, SaiLanguage.DEVICE);
        RadioGroup langs = findViewById(R.id.SaiPenLanguage);
        String[][] options = {
            {SaiLanguage.DEVICE, getString(R.string.saipen_language_device, deviceLang)},
            {SaiLanguage.EN, "English"},
            {SaiLanguage.JA, "日本語"},
        };
        for (String[] o : options) {
            RadioButton b = new RadioButton(this);
            b.setText(o[1]);
            b.setId(View.generateViewId());
            langs.addView(b);
            if (o[0].equals(stored) || (!SaiLanguage.isLanguage(stored) && o[0].equals(SaiLanguage.DEVICE))) b.setChecked(true);
            b.setOnCheckedChangeListener((v, checked) -> {
                if (!checked) return;
                prefs.edit().putString(SaiLanguage.PREF_CHOICE, o[0]).apply();
                show.run();
            });
        }
        show.run();

        RadioGroup fonts = findViewById(R.id.SaiPenFont);
        String font = prefs.getString(FontLink.PREF_FONT, FontLink.DEFAULT_FONT);
        java.util.List<String[]> fontOptions = new ArrayList<>();
        for (FontLink.BuiltIn f : FontLink.BUILT_IN) {
            if (new File(FontLink.SYSTEM_FONTS, f.file).isFile()) fontOptions.add(new String[] {f.id, f.label});
        }
        fontOptions.add(new String[] {FontLink.OFF, getString(R.string.saipen_font_off)});
        for (String[] o : fontOptions) {
            RadioButton b = new RadioButton(this);
            b.setText(o[1]);
            b.setId(View.generateViewId());
            fonts.addView(b);
            if (o[0].equals(font)) b.setChecked(true);
            b.setOnCheckedChangeListener((v, checked) -> {
                if (checked) prefs.edit().putString(FontLink.PREF_FONT, o[0]).apply();
            });
        }
    }

    /** Sharpness: one choice per height the display allows. */
    /** "New frame: after every stroke / a short pause / a long pause",
     *  shown while the timelapse switch is on. */
    private void setUpTimelapsePause(android.widget.CompoundButton timelapse) {
        RadioGroup group = findViewById(R.id.SaiPenTimelapsePause);
        int current = prefs.getInt(PREF_TIMELAPSE_PAUSE, TIMELAPSE_PAUSES_MS[0]);
        for (int i = 0; i < TIMELAPSE_PAUSES_MS.length; i++) {
            final int ms = TIMELAPSE_PAUSES_MS[i];
            RadioButton b = new RadioButton(this);
            b.setText(TIMELAPSE_PAUSE_NAMES[i]);
            b.setId(View.generateViewId());
            group.addView(b);
            if (ms == current) b.setChecked(true);
            b.setOnCheckedChangeListener((v, checked) -> {
                if (checked) prefs.edit().putInt(PREF_TIMELAPSE_PAUSE, ms).apply();
            });
        }
        setUpTimelapseSpace();
        View box = findViewById(R.id.SaiPenTimelapsePauseBox);
        box.setVisibility(timelapse.isChecked() ? View.VISIBLE : View.GONE);
        timelapse.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(PREF_TIMELAPSE, on).apply();
            box.setVisibility(on ? View.VISIBLE : View.GONE);
        });
    }

    /** "Room for frames": a slider, 2 GB up in half-GB steps, Auto at the right end (TimelapseBudget),
     *  with what is free, and a yellow note when that is too little. */
    private void setUpTimelapseSpace() {
        android.widget.SeekBar bar = findViewById(R.id.SaiPenTlSpace);
        TextView label = findViewById(R.id.SaiPenTlSpaceLabel);
        TextView warn = findViewById(R.id.SaiPenTlSpaceWarn);
        /* 2 GB up in half-GB steps, the last step is Auto: all that safely
           fits, a quarter of the free space */
        long free0 = getFilesDir().getUsableSpace();
        findViewById(R.id.SaiPenTlSpaceInfo).setOnClickListener(v -> {
            long free = getFilesDir().getUsableSpace();
            String gb = "%.1f GB";
            new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_tl_space_info_title)
                .setMessage(getString(R.string.saipen_tl_space_info_text,
                    String.format(java.util.Locale.ROOT, gb, free / (1024.0 * 1024 * 1024)),
                    String.format(java.util.Locale.ROOT, gb, TimelapseBudget.megabytes(free) / 1024.0)))
                .setPositiveButton(android.R.string.ok, null).show();
        });
        bar.setMax(TimelapseBudget.lastStep(free0));
        bar.setProgress(TimelapseBudget.stepOf(timelapseRoomChoice(), free0));
        java.util.function.IntConsumer show = step -> {
            long free = getFilesDir().getUsableSpace();
            int gb = TimelapseBudget.mbAt(step, free);
            long mb = TimelapseBudget.megabytes(free, gb);
            String size = String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0);
            String freeText = String.format(java.util.Locale.ROOT, "%.1f GB", free / (1024.0 * 1024 * 1024));
            label.setText(gb == TimelapseBudget.AUTO
                ? getString(R.string.saipen_tl_space_auto, size, freeText)
                : getString(R.string.saipen_tl_space_fixed, size, freeText));
            boolean low = TimelapseBudget.tooLittleSpace(free);
            warn.setText(getString(R.string.saipen_tl_space_low, freeText));
            warn.setVisibility(low ? View.VISIBLE : View.GONE);
        };
        show.accept(bar.getProgress());
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) { show.accept(v); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {
                int v = s.getProgress();
                prefs.edit().putInt(TimelapseBudget.PREF_MB,
                    TimelapseBudget.mbAt(v, getFilesDir().getUsableSpace())).apply();
            }
        });
    }

    /** The saved room for frames, megabytes or AUTO; an old whole-GB choice carries over. */
    private int timelapseRoomChoice() {
        if (prefs.contains(TimelapseBudget.PREF_MB)) return prefs.getInt(TimelapseBudget.PREF_MB, TimelapseBudget.AUTO);
        return TimelapseBudget.fromGb(prefs.getInt(TimelapseBudget.PREF_GB, TimelapseBudget.AUTO));
    }

    /** "Fast cores: Drawing / All of SAI / Off" (SaiCores), one
     *  line like the sharpness, what the choice does under it, and a small
     *  table of each choice's pen-to-screen time so far (CoresCompare).
     *  The choice lives in SaiCores' file, so adb can still change it. */
    private void setUpFastCores() {
        RadioGroup group = findViewById(R.id.SaiPenFastCores);
        TextView what = findViewById(R.id.SaiPenFastCoresWhat);
        TextView coresValue = findViewById(R.id.SaiPenCoresValue);
        boolean any = FastCores.find(new File("/sys/devices/system/cpu")).hasFast();
        SaiCores.Mode current = SaiCores.readMode(getFilesDir());
        for (int i = 0; i < SaiCores.CHOICES.length; i++) {
            final SaiCores.Mode mode = SaiCores.CHOICES[i];
            final int whatText = FAST_CORES_WHAT[i];
            RadioButton b = new RadioButton(this);
            b.setText(FAST_CORES_NAMES[i]);
            b.setId(View.generateViewId());
            b.setEnabled(any);
            group.addView(b);
            final int name = FAST_CORES_NAMES[i];
            if (mode == current) {
                b.setChecked(true);
                what.setText(whatText);
                if (any) coresValue.setText(name);
            }
            b.setOnCheckedChangeListener((v, checked) -> {
                if (!checked) return;
                what.setText(whatText);
                coresValue.setText(name);
                if (!SaiCores.writeMode(getFilesDir(), mode)) Log.w(TAG, "fast cores choice not saved");
            });
        }
        if (!any) {
            what.setText(R.string.saipen_cores_none);
        }
        else {
            fastCoresTable();
        }

        android.widget.CompoundButton logs = findViewById(R.id.SaiPenSpeedLog);
        logs.setChecked(prefs.getBoolean(SpeedSession.PREF_SPEED_LOG, FirstRun.SPEED_LOG));
        logs.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(SpeedSession.PREF_SPEED_LOG, on).apply());

        setUpPenPressure();

        /* palm rejection: three checks, each on its own, all off by default */
        palmSwitch(R.id.SaiPenPalmAndroid, PalmFilter.PREF_PALM_ANDROID, FirstRun.PALM_ANDROID);
        palmSwitch(R.id.SaiPenPalmBig, PalmFilter.PREF_PALM_BIG, FirstRun.PALM_BIG);
        palmSwitch(R.id.SaiPenPalmPenNear, PalmFilter.PREF_PALM_PEN_NEAR, FirstRun.PALM_PEN_NEAR);
        /* the pen-near check can swallow two-finger gestures: a yellow note while it is on */
        View penNearWarn = findViewById(R.id.SaiPenPalmPenNearWarn);
        android.widget.CompoundButton penNear = findViewById(R.id.SaiPenPalmPenNear);
        penNearWarn.setVisibility(penNear.isChecked() ? View.VISIBLE : View.GONE);
        penNear.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(PalmFilter.PREF_PALM_PEN_NEAR, on).apply();
            showPalmCount();
            penNearWarn.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        showPalmCount();

        /* the Drawings card's rows: recent drawings on, references and timelapses off */
        homeSwitch(R.id.SaiPenHomeDrawings, PREF_HOME_DRAWINGS, true);
        homeSwitch(R.id.SaiPenHomeRefs, PREF_HOME_REFERENCES, false);
        homeSwitch(R.id.SaiPenHomeTimelapses, PREF_HOME_TIMELAPSES, false);
        /* a tap on a drawing: straight into SAI (default) or its page first */
        android.widget.CompoundButton look = findViewById(R.id.SaiPenHomeTapPage);
        look.setChecked(prefs.getBoolean(PREF_HOME_TAP_PAGE, false));
        look.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(PREF_HOME_TAP_PAGE, on).apply());
        showHomeCount();

        /* on-screen keys over SAI: which keys, shortcuts, colour (FloatingKeysSettings) */
        TextView keysValue = findViewById(R.id.SaiPenKeysValue);
        Runnable keysChanged = () -> keysValue.setText(FloatingKeysSettings.summary(this, prefs));
        FloatingKeysSettings.build(this, findViewById(R.id.SaiPenKeysBody), prefs, DIALOG_THEME, keysChanged);
        keysChanged.run();

        /* what each finger and pen gesture does (GesturesSettings, issue #10) */
        TextView gesturesValue = findViewById(R.id.SaiPenGesturesValue);
        Runnable gesturesChanged = () -> gesturesValue.setText(GesturesSettings.summary(this, prefs));
        GesturesSettings.build(this, findViewById(R.id.SaiPenGesturesBody), prefs, DIALOG_THEME, gesturesChanged);
        gesturesChanged.run();
    }

    /** "Pen pressure": a slider, Normal then softer up to 10 (PenPressure),
     *  with what a light and a medium press give, and the step next to the
     *  row's title so it shows without opening it. */
    private void setUpPenPressure() {
        android.widget.SeekBar bar = findViewById(R.id.SaiPenPressure);
        TextView value = findViewById(R.id.SaiPenPressureValue);
        TextView what = findViewById(R.id.SaiPenPressureWhat);
        bar.setMax(PenPressure.MAX_SOFTNESS);
        bar.setProgress(PenPressure.clamp(prefs.getInt(PenPressure.PREF_SOFTNESS, FirstRun.PEN_SOFTNESS)));
        java.util.function.IntConsumer show = step -> {
            value.setText(step == 0 ? getString(R.string.saipen_pressure_normal)
                : getString(R.string.saipen_pressure_soft, step));
            what.setText(getString(R.string.saipen_pressure_light,
                PenPressure.percentFor(0.25f, step), PenPressure.percentFor(0.5f, step)));
        };
        show.accept(bar.getProgress());
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) { show.accept(v); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {
                prefs.edit().putInt(PenPressure.PREF_SOFTNESS, s.getProgress()).apply();
            }
        });
    }

    /** "1 of 3 on" next to Palm rejection, so it shows without opening the row. */
    private void showPalmCount() {
        int on = 0;
        if (prefs.getBoolean(PalmFilter.PREF_PALM_ANDROID, FirstRun.PALM_ANDROID)) on++;
        if (prefs.getBoolean(PalmFilter.PREF_PALM_PEN_NEAR, FirstRun.PALM_PEN_NEAR)) on++;
        if (prefs.getBoolean(PalmFilter.PREF_PALM_BIG, FirstRun.PALM_BIG)) on++;
        ((TextView) findViewById(R.id.SaiPenPalmValue)).setText(getString(R.string.saipen_palm_on, on));
    }

    private void homeSwitch(int id, String pref, boolean byDefault) {
        android.widget.CompoundButton b = findViewById(id);
        b.setChecked(prefs.getBoolean(pref, byDefault));
        b.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(pref, on).apply();
            showHomeCount();
            showRecent();
        });
    }

    private void showHomeCount() {
        int on = 0;
        if (prefs.getBoolean(PREF_HOME_DRAWINGS, true)) on++;
        if (prefs.getBoolean(PREF_HOME_REFERENCES, false)) on++;
        if (prefs.getBoolean(PREF_HOME_TIMELAPSES, false)) on++;
        ((TextView) findViewById(R.id.SaiPenHomeValue)).setText(getString(R.string.saipen_home_on, on));
    }

    private void palmSwitch(int id, String pref, boolean byDefault) {
        android.widget.CompoundButton b = findViewById(id);
        b.setChecked(prefs.getBoolean(pref, byDefault));
        b.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(pref, on).apply();
            showPalmCount();
        });
    }

    /** The device's fast cores (FastCores) and every core, read once. */
    private FastCores cpuCores;

    private FastCores cpuCores() {
        if (cpuCores == null) cpuCores = FastCores.find(new File("/sys/devices/system/cpu"));
        return cpuCores;
    }

    private int totalCores() {
        int n = cpuCores().cores.length;
        return n > 0 ? n : Runtime.getRuntime().availableProcessors();
    }

    /** How many processors SAI sees at the next start (SaiProcessors):
     *  a number, or SaiProcessors.ALL. */
    private int saiProcessors() {
        return SaiProcessors.resolve(prefs.getInt(SaiProcessors.PREF, SaiProcessors.AUTO),
                                     cpuCores().fast.length, totalCores());
    }

    /** "Cores SAI sees": the device's fast cores (at least 2) by
     *  default, any number up to all of them, with (i) on SAI's own
     *  setting. Saved here, put into the container at Start SAI. */
    private void setUpProcessors() {
        TextView value = findViewById(R.id.SaiPenProcessorsValue);
        ((TextView) findViewById(R.id.SaiPenProcessorsWhat)).setText(R.string.saipen_processors_what);
        findViewById(R.id.SaiPenProcessorsInfo).setOnClickListener(v -> processorsInfo());
        View chart = coresChart();
        if (chart != null) ((LinearLayout) findViewById(R.id.SaiPenProcessorsBody)).addView(chart, 0);
        int total = totalCores();
        int suggested = SaiProcessors.suggested(cpuCores().fast.length, total);
        int current = saiProcessors();
        Runnable show = () -> {
            int n = saiProcessors();
            value.setText(n == SaiProcessors.ALL ? getString(R.string.saipen_processors_value_all) : String.valueOf(n));
        };
        RadioGroup group = findViewById(R.id.SaiPenProcessors);
        for (int n : SaiProcessors.choices(total)) {
            RadioButton b = new RadioButton(this);
            if (n == SaiProcessors.ALL) {
                b.setText(getString(suggested == SaiProcessors.ALL ? R.string.saipen_processors_all_recommended
                                                                    : R.string.saipen_processors_all, total));
            }
            else if (n == suggested) b.setText(getString(R.string.saipen_processors_fast, n));
            else b.setText(String.valueOf(n));
            b.setId(View.generateViewId());
            group.addView(b);
            if (n == current) b.setChecked(true);
            b.setOnCheckedChangeListener((v, checked) -> {
                if (!checked) return;
                /* the recommended one follows the device; any other is kept as picked */
                prefs.edit().putInt(SaiProcessors.PREF, n == suggested ? SaiProcessors.AUTO : n).apply();
                show.run();
            });
        }
        show.run();
    }

    /** The device's cores as bars, like Ready to draw's: one per core, as
     *  tall as it is strong (FastCores' speed: cpu_capacity, or the clock),
     *  green for the fast ones and yellow for the slower ones, its top clock
     *  under it and, with cpu_capacity, its strength as a share of the
     *  strongest core. Null when the speeds are unknown. */
    private View coresChart() {
        FastCores f = cpuCores();
        long top = 0;
        for (long s : f.speed) top = Math.max(top, s);
        if (f.cores.length == 0 || top <= 0) return null;
        java.util.Set<Integer> fast = new java.util.HashSet<>();
        for (int c : f.fast) fast.add(c);
        float dp = getResources().getDisplayMetrics().density;
        int textColor = ((TextView) findViewById(R.id.SaiPenProcessorsWhat)).getCurrentTextColor();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        TextView caption = new TextView(this);
        caption.setTextColor(textColor);
        caption.setTextSize(13);
        /* "the fastest is 100%" only where the percentages are shown */
        caption.setText(f.hasFast()
            ? getString(f.fromCapacity ? R.string.saipen_processors_cores_pct : R.string.saipen_processors_cores,
                        f.cores.length, f.fast.length, f.cores.length - f.fast.length)
            : getString(f.fromCapacity ? R.string.saipen_processors_cores_alike_pct : R.string.saipen_processors_cores_alike,
                        f.cores.length));
        box.addView(caption);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (8 * dp), 0, 0);
        int maxBar = (int) (48 * dp);
        for (int i = 0; i < f.cores.length; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) clp.setMarginStart((int) (4 * dp));
            col.setLayoutParams(clp);

            /* the bar sits on a floor of maxBar, so short ones line up at the bottom */
            FrameLayout well = new FrameLayout(this);
            well.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxBar));
            View bar = new View(this);
            int h = Math.max((int) (6 * dp), (int) (maxBar * f.speed[i] / (double) top));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h,
                                                                        android.view.Gravity.BOTTOM);
            bar.setLayoutParams(blp);
            boolean isFast = !f.hasFast() || fast.contains(f.cores[i]);
            bar.setBackground(rounded(isFast ? 0xFF4CAF50 : 0xFFFFB74D, 3 * dp));
            well.addView(bar);
            col.addView(well);

            TextView label = new TextView(this);
            label.setTextColor(textColor);
            label.setTextSize(11);
            label.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            long khz = maxKhz(f.cores[i]);
            label.setText(khz > 0 ? getString(R.string.saipen_processors_ghz, khz / 1e6) : "cpu" + f.cores[i]);
            col.addView(label);

            /* its strength under the clock: the bar's own number */
            int pct = f.strengthPercent(i);
            if (pct >= 0) {
                TextView strength = new TextView(this);
                strength.setTextColor(textColor);
                strength.setTextSize(11);
                strength.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                strength.setText(getString(R.string.saipen_processors_percent, pct));
                col.addView(strength);
            }
            row.addView(col);
        }
        box.addView(row);
        return box;
    }

    /** A core's top clock in kHz, 0 if the kernel does not say. */
    private static long maxKhz(int core) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(new File(
                "/sys/devices/system/cpu/cpu" + core + "/cpufreq/cpuinfo_max_freq").toPath())).trim();
            return Long.parseLong(s);
        }
        catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** (i) beside "Cores SAI sees": why, and that SAI has the same
     *  setting itself: one or the other, not both. */
    private void processorsInfo() {
        int fast = cpuCores().fast.length, total = totalCores();
        String device = SaiProcessors.suggested(fast, total) == SaiProcessors.ALL && fast == 0
            ? getString(R.string.saipen_processors_info_device_none)
            : getString(R.string.saipen_processors_info_device, fast, total - fast);
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_processors_info_title)
            .setMessage(getString(R.string.saipen_processors_info_text, device))
            .setPositiveButton(android.R.string.ok, null).show();
    }

    /** Pen to screen so far, one row per choice; hidden with no numbers at all. */
    /** "Screen drawing: Front buffer / Front buffer while drawing / Standard"
     *  (FrontBuffer). The choice lives in FrontBuffer's file, read when SAI starts. */
    private void setUpScreenBuffer() {
        RadioGroup group = findViewById(R.id.SaiPenBuffer);
        TextView what = findViewById(R.id.SaiPenBufferWhat);
        TextView value = findViewById(R.id.SaiPenBufferValue);
        View warn = findViewById(R.id.SaiPenBufferWarn);
        FrontBuffer.Mode current = FrontBuffer.readMode(getFilesDir());
        final FrontBuffer.Mode[] choices = {FrontBuffer.Mode.FRONT, FrontBuffer.Mode.WHILE_DRAWING, FrontBuffer.Mode.STANDARD};
        final int[] names = {R.string.saipen_buffer_front, R.string.saipen_buffer_drawing, R.string.saipen_buffer_standard};
        final int[] whats = {R.string.saipen_buffer_front_what, R.string.saipen_buffer_drawing_what, R.string.saipen_buffer_standard_what};
        for (int i = 0; i < choices.length; i++) {
            final FrontBuffer.Mode mode = choices[i];
            final int name = names[i], whatText = whats[i];
            RadioButton b = new RadioButton(this);
            b.setText(name);
            b.setId(View.generateViewId());
            group.addView(b);
            if (mode == current) {
                b.setChecked(true);
                what.setText(whatText);
                value.setText(name);
                warn.setVisibility(mode == FrontBuffer.Mode.STANDARD ? View.GONE : View.VISIBLE);
            }
            b.setOnCheckedChangeListener((v, checked) -> {
                if (!checked) return;
                what.setText(whatText);
                value.setText(name);
                warn.setVisibility(mode == FrontBuffer.Mode.STANDARD ? View.GONE : View.VISIBLE);
                if (!FrontBuffer.writeMode(getFilesDir(), mode)) Log.w(TAG, "screen drawing choice not saved");
            });
        }
    }

    private void fastCoresTable() {
        java.util.Map<SaiCores.Mode, CoresCompare.Result> past = CoresCompare.fromFolder(new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), SpeedSession.FOLDER));
        android.widget.TableLayout table = findViewById(R.id.SaiPenFastCoresTable);
        float dp = getResources().getDisplayMetrics().density;
        boolean anyNumber = false;
        for (int i = 0; i < SaiCores.CHOICES.length; i++) {
            CoresCompare.Result r = past.get(SaiCores.CHOICES[i]);
            int ms = r.median();
            if (ms >= 0) anyNumber = true;
            android.widget.TableRow row = new android.widget.TableRow(this);
            row.addView(cell(getString(FAST_CORES_NAMES[i]), 0));
            row.addView(cell(ms >= 0 ? getString(R.string.saipen_cores_ms, ms) : getString(R.string.saipen_cores_few),
                             (int) (16 * dp)));
            row.addView(cell(ms >= 0 ? getString(R.string.saipen_cores_sessions, r.sessions) : "", (int) (16 * dp)));
            table.addView(row);
        }
        findViewById(R.id.SaiPenFastCoresPast).setVisibility(anyNumber ? View.VISIBLE : View.GONE);
    }

    private TextView cell(String text, int left) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(left, 0, 0, 0);
        return t;
    }

    private void setUpSharpness() {
        RadioGroup group = findViewById(R.id.SaiPenSharpness);
        int shortSide = shortSide();
        int current = screenHeight();
        TextView value = findViewById(R.id.SaiPenSharpnessValue);
        value.setText(String.valueOf(current));
        for (int h : SaiLocator.screenHeights(shortSide)) {
            RadioButton b = new RadioButton(this);
            b.setText(h == shortSide && h > 720 ? getString(R.string.saipen_sharpness_native, h) : String.valueOf(h));
            b.setId(View.generateViewId());
            group.addView(b);
            if (h == current) b.setChecked(true);
            b.setOnCheckedChangeListener((v, checked) -> {
                if (!checked) return;
                prefs.edit().putInt(PREF_SCREEN_HEIGHT, h).apply();
                value.setText(String.valueOf(h));
            });
        }
    }

    /* ---- finding SAI ---- */

    private void locate() {
        /* No SAI yet: ask before every search, never search by itself. A
         * search nobody asked for felt creepy (the owner, 2026-11-19), and an
         * earlier "yes" is not a yes for the next time (2026-11-23). */
        if (rememberedSaiDir() == null) {
            askHowToFind();
            return;
        }
        scanDevice();
    }

    /** The remembered SAI, or with none every SAI on the device, four
     *  folders deep. Only after a yes (askHowToFind, the Find button). */
    private void scanDevice() {
        busy(R.string.saipen_searching);
        worker.execute(() -> {
            File remembered = rememberedSaiDir();
            List<SaiLocator.Found> found = new ArrayList<>();
            File storage = Environment.getExternalStorageDirectory();
            if (remembered == null) {
                found.addAll(SaiLocator.scan(storage, 4));
                found.addAll(SaiLocator.scan(new File(storage, "Android/media"), 4));
            }
            SaiLocator.Found best = SaiLocator.best(found);
            List<File> zips = remembered == null && (best == null || !best.complete)
                ? SaiLocator.zips(storage, 4) : new ArrayList<>();
            ui.post(() -> show(remembered, best, zips));
        });
    }

    /** Use this SAI from now on, and show it. */
    private void useSai(File dir) {
        prefs.edit().putString(PREF_SAI_DIR, dir.getPath()).apply();
        show(dir, null, new ArrayList<>());
    }

    /**
     * "Change SAI…": look for every SAI on the device and let the person pick
     * one, newest first, or choose a folder themselves. Once two buttons
     * ("Look for other SAIs", "Choose the SAI folder"); nobody could tell
     * them apart (the owner, 2026-11-23), so one, and the folder picker is
     * the list's last line.
     *
     * THE TRAP: a remembered folder is never searched past on its own, so a
     * newer SAI downloaded later is never even looked at (a user on Reddit,
     * 2026-11-23, kept getting the 2026.07.02b next to the newer Alpha).
     * Only on this button, never by itself: the owner wants no search
     * nobody asked for.
     */
    private void changeSai() {
        /* ask first: a search nobody expected looked scary to the owner
         * (2026-11-23), and it only goes four folders deep, so a folder of
         * one's own must always be on offer */
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_change_title)
            .setMessage(R.string.saipen_change_text)
            .setPositiveButton(R.string.saipen_change_search, (d, w) -> searchAllSais())
            .setNeutralButton(R.string.saipen_change_folder, (d, w) -> chooseFolder())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Every SAI on the device, four folders deep, then the list. */
    private void searchAllSais() {
        busy(R.string.saipen_searching);
        worker.execute(() -> {
            File storage = Environment.getExternalStorageDirectory();
            List<SaiLocator.Found> found = new ArrayList<>(SaiLocator.scan(storage, 4));
            found.addAll(SaiLocator.scan(new File(storage, "Android/media"), 4));
            List<SaiLocator.Found> complete = SaiLocator.completeNewestFirst(found);
            ui.post(() -> {
                show(rememberedSaiDir(), null, new ArrayList<>());
                pickOne(complete);
            });
        });
    }

    /**
     * Which SAI to use: the ones found, newest first, the one in use marked,
     * and last "choose the folder myself". With nothing but the one in use,
     * says so and why a newer one may be missing (still zipped).
     */
    private void pickOne(List<SaiLocator.Found> list) {
        boolean onlyCurrent = list.isEmpty() || (list.size() == 1 && saiDir != null
            && list.get(0).dir.getAbsolutePath().equals(saiDir.getAbsolutePath()));
        AlertDialog.Builder dialog = new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_pick_title)
            .setNegativeButton(android.R.string.cancel, null);
        if (onlyCurrent) {
            dialog.setMessage(R.string.saipen_only_one)
                .setPositiveButton(R.string.saipen_pick_folder, (d, w) -> chooseFolder())
                .show();
            return;
        }
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String[] items = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            SaiLocator.Found f = list.get(i);
            /* the folder it is in too: two copies of one build (Telegram's
             * and Download's) look the same by name alone */
            String parent = f.dir.getAbsoluteFile().getParent();
            if (parent != null && parent.startsWith(root)) parent = parent.substring(root.length());
            if (parent == null || parent.isEmpty()) parent = "/";
            items[i] = f.dir.getName() + "  (" + df.format(new java.util.Date(f.modified)) + ")"
                + (saiDir != null && f.dir.getAbsolutePath().equals(saiDir.getAbsolutePath())
                   ? "  " + getString(R.string.saipen_in_use) : "")
                + (f.is32Bit() ? "  " + getString(R.string.saipen_32bit_short) : "")
                + "\n" + getString(R.string.saipen_in_folder, parent);
        }
        items[list.size()] = getString(R.string.saipen_pick_folder);
        dialog.setItems(items, (d, which) -> {
            if (which == list.size()) chooseFolder();
            else useSai(list.get(which).dir);
        }).show();
    }

    private File rememberedSaiDir() {
        String path = prefs.getString(PREF_SAI_DIR, null);
        if (path == null) return null;
        File dir = new File(path);
        return SaiLocator.isComplete(dir) ? dir : null;
    }

    private void show(File remembered, SaiLocator.Found best, List<File> zips) {
        saiDir = remembered != null ? remembered : (best != null && best.complete ? best.dir : null);
        zip = saiDir == null && !zips.isEmpty() ? zips.get(0) : null;
        if (saiDir != null) prefs.edit().putString(PREF_SAI_DIR, saiDir.getPath()).apply();

        /* the 32-bit SAI doesn't start properly here: say so instead of Start */
        boolean is32 = saiDir != null && SaiLocator.exeBits(new File(saiDir, "sai2.exe")) == 32;

        progress.setVisibility(View.GONE);
        /* a 32-bit SAI: the big button changes SAI instead of starting it */
        btnStart.setVisibility(saiDir != null ? View.VISIBLE : View.GONE);
        btnStart.setText(is32 ? R.string.saipen_change_sai : R.string.saipen_start);
        btnUnzip.setVisibility(zip != null ? View.VISIBLE : View.GONE);
        btnSite.setVisibility(saiDir == null && zip == null ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(saiDir == null && zip == null ? View.VISIBLE : View.GONE);
        btnSearch.setEnabled(true);
        btnChoose.setText(saiDir != null ? R.string.saipen_change_sai : R.string.saipen_choose);
        btnChoose.setVisibility(is32 ? View.GONE : View.VISIBLE);
        btnChoose.setEnabled(true);

        if (is32) {
            tvStatus.setText(R.string.saipen_32bit_title);
            tvSaiPath.setText(getString(R.string.saipen_32bit) + "\n" + saiDir.getPath());
            return;
        }
        if (saiDir != null) {
            tvStatus.setText(R.string.saipen_ready);
            tvSaiPath.setText(saiDir.getPath());
        }
        else if (zip != null) {
            tvStatus.setText(R.string.saipen_zip_found);
            tvSaiPath.setText(zip.getPath());
        }
        else if (best != null) {
            tvStatus.setText(R.string.saipen_incomplete);
            tvSaiPath.setText(best.dir.getPath());
        }
        else {
            tvStatus.setText(R.string.saipen_not_found);
            tvSaiPath.setText("");
        }

        installLicence();
        refreshLicence();
        showReadiness();

        /* SAI found the first time: the screen says so and waits for "Start
         * SAI". A licence file found next to it or in Downloads is used
         * right away and named; only without one is there a question.
         * A new install has its own System ID: no certificate on the device
         * can be for it yet, so the screen says how to get one instead. */
        if (saiDir != null && shouldAskAboutLicence()) {
            if (ownIdSince() > 0) explainOwnSystemId();
            else searchLicence();
        }
        else if (autoLaunch && saiDir != null) launch();
        autoLaunch = false;
    }

    /** First start: search the device for SAI, or pick its folder? */
    private void askHowToFind() {
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_find_title)
            .setMessage(R.string.saipen_find_text)
            .setPositiveButton(R.string.saipen_find_auto, (d, w) -> scanDevice())
            .setNegativeButton(R.string.saipen_find_manual, (d, w) -> {
                prefs.edit().putString(PREF_SEARCH, "manual").apply();
                show(null, null, new ArrayList<>());
                chooseFolder();
            })
            .setCancelable(false)
            .show();
    }

    /* ---- first run without a licence ---- */

    private boolean shouldAskAboutLicence() {
        return container != null && !prefs.getBoolean(PREF_LICENCE_ASKED, false)
            && SaiLocator.installedLicences(saiV2Dir()).isEmpty();
    }

    /** "Add a SAI licence", or "Change or remove the licence" once there is
     *  one. */
    private void askAboutLicence() {
        if (licenceDialog != null && licenceDialog.isShowing()) return;
        if (container != null && !SaiLocator.installedLicences(saiV2Dir()).isEmpty()) {
            licenceDialog = new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(R.string.saipen_licence_q_title)
                .setMessage(getString(R.string.saipen_licence_change_text,
                                      SaiLocator.maskedLicenceName(SaiLocator.installedLicences(saiV2Dir()).get(0).getName())))
                .setPositiveButton(R.string.saipen_licence_q_pick, (d, w) -> pickLicence())
                .setNeutralButton(R.string.saipen_licence_remove, (d, w) -> removeLicence())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        licenceDialog = new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_licence_q_title)
            .setMessage(R.string.saipen_licence_q_text)
            .setPositiveButton(R.string.saipen_licence_q_search, (d, w) -> searchLicence())
            .setNeutralButton(R.string.saipen_licence_q_pick, (d, w) -> pickLicence())
            .setNegativeButton(R.string.saipen_licence_q_without, (d, w) ->
                prefs.edit().putBoolean(PREF_LICENCE_ASKED, true).apply())
            .setCancelable(false)
            .show();
    }

    /** 0 for a container made before the per-device System ID. */
    private long ownIdSince() {
        return prefs.getLong(PREF_OWN_ID_SINCE, 0);
    }

    /** A new install: first the System ID, then the certificate for it. A
     *  certificate downloaded after this install is picked up by itself
     *  (installLicence), one from before is not: it is for another computer. */
    private void explainOwnSystemId() {
        if (licenceDialog != null && licenceDialog.isShowing()) return;
        licenceDialog = new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_own_id_title)
            .setMessage(R.string.saipen_own_id_text)
            .setPositiveButton(R.string.saipen_own_id_start, (d, w) -> {
                prefs.edit().putBoolean(PREF_LICENCE_ASKED, true).putBoolean(PREF_LICENCE_SEARCH, true).apply();
                launch();
            })
            .setNeutralButton(R.string.saipen_licence_q_site, (d, w) -> {
                prefs.edit().putBoolean(PREF_LICENCE_ASKED, true).putBoolean(PREF_LICENCE_SEARCH, true).apply();
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LICENCE_SITE)));
            })
            .setNegativeButton(R.string.saipen_licence_q_pick, (d, w) -> pickLicence())
            .setCancelable(false)
            .show();
    }

    /** Looks in Downloads and SAI's folder: a licence found is used and
     *  named; none found, and the screen says how to get one. */
    private void searchLicence() {
        if (container == null) return;
        List<File> roots = licenceRoots();
        worker.execute(() -> {
            File found = SaiLocator.newestLicence(roots, 2, ownIdSince());
            ui.post(() -> {
                prefs.edit().putBoolean(PREF_LICENCE_ASKED, true).apply();
                if (found == null) {
                    new AlertDialog.Builder(this, DIALOG_THEME)
                        .setTitle(R.string.saipen_licence_q_title)
                        .setMessage(R.string.saipen_licence_none_found)
                        .setPositiveButton(R.string.saipen_licence_q_pick, (d, w) -> pickLicence())
                        .setNeutralButton(R.string.saipen_licence_q_site, (d, w) ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LICENCE_SITE))))
                        .setNegativeButton(R.string.saipen_licence_q_without, null)
                        .show();
                    return;
                }
                useFoundLicence(found);
            });
        });
    }

    /** Out of SAI's folder in this app; the file it came from stays, and it
     *  is no longer picked up by itself (until "Look for it" again). */
    private void removeLicence() {
        if (container == null) return;
        File saiV2 = saiV2Dir();
        prefs.edit().putBoolean(PREF_LICENCE_SEARCH, false).putBoolean(PREF_LICENCE_ASKED, true).apply();
        worker.execute(() -> {
            SaiLocator.removeLicences(saiV2);
            ui.post(() -> {
                refreshLicence();
                Toast.makeText(this, R.string.saipen_licence_removed, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void useFoundLicence(File found) {
        File saiV2 = saiV2Dir();
        worker.execute(() -> {
            boolean ok;
            try {
                SaiLocator.installLicence(found, saiV2);
                ok = true;
            }
            catch (IOException e) {
                ok = false;
            }
            boolean added = ok;
            ui.post(() -> {
                /* yes to this one is a yes to a newer one later: a new
                 * certificate for a new System ID lands in Downloads */
                if (added) prefs.edit().putBoolean(PREF_LICENCE_SEARCH, true).apply();
                refreshLicence();
                if (added) Toast.makeText(this, getString(R.string.saipen_licence_found,
                                                          SaiLocator.maskedLicenceName(found.getName()), found.getParent()), Toast.LENGTH_LONG).show();
                else Toast.makeText(this, R.string.saipen_licence_bad, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LICENCE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) addLicence(data.getData());
            return;
        }
        if (requestCode == REQUEST_BRUSHES) {
            if (resultCode != RESULT_OK || data == null) return;
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            }
            else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) readBrushes(pickedFiles(uris), false);
            return;
        }
        if (requestCode == REQUEST_PC_SETTINGS) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            readBrushes(pickedFiles(java.util.Collections.singletonList(data.getData())), true);
            return;
        }
        if (requestCode == REQUEST_STATS_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && statsCsv != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new IOException("could not open file");
                    out.write(statsCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    Toast.makeText(this, R.string.saipen_stats_exported, Toast.LENGTH_SHORT).show();
                }
                catch (IOException e) {
                    Toast.makeText(this, R.string.saipen_stats_export_failed, Toast.LENGTH_LONG).show();
                }
            }
            statsCsv = null;
            return;
        }
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String path = SaiLocator.treeDocIdToPath(DocumentsContract.getTreeDocumentId(data.getData()),
                                                 Environment.getExternalStorageDirectory().getPath());
        if (path == null) {
            tvStatus.setText(R.string.saipen_no_path);
            return;
        }
        File picked = new File(path);
        busy(R.string.saipen_searching);
        worker.execute(() -> {
            /* the folder itself, or SAI somewhere inside it */
            List<SaiLocator.Found> all = SaiLocator.scan(picked, 3);
            SaiLocator.Found best = SaiLocator.best(all);
            List<SaiLocator.Found> complete = SaiLocator.completeNewestFirst(all);
            List<File> zips = best == null ? SaiLocator.zips(picked, 3) : new ArrayList<>();
            ui.post(() -> {
                if (complete.size() > 1) {
                    show(rememberedSaiDir(), null, new ArrayList<>());
                    pickOne(complete);
                    return;
                }
                if (best != null && best.complete) prefs.edit().putString(PREF_SAI_DIR, best.dir.getPath()).apply();
                show(best != null && best.complete ? best.dir : null, best, zips);
                if (best == null && zips.isEmpty()) tvStatus.setText(R.string.saipen_not_here);
            });
        });
    }

    private void unzip() {
        File z = zip;
        if (z == null) return;
        busy(R.string.saipen_unzipping);
        worker.execute(() -> {
            try {
                File dir = SaiLocator.unzipNextToIt(z);
                ui.post(() -> {
                    if (SaiLocator.isComplete(dir)) prefs.edit().putString(PREF_SAI_DIR, dir.getPath()).apply();
                    locate();
                });
            }
            catch (IOException e) {
                ui.post(() -> idle(getString(R.string.saipen_unzip_failed) + "\n" + e.getMessage()));
            }
        });
    }

    /* ---- starting SAI ---- */

    private boolean packingBeforeStart;

    /* ---- the newest drawings (RecentDrawings, Sai2Thumb) ---- */

    private static final int RECENT_W_DP = 92, RECENT_H_DP = 120;

    /* Which rows the Drawings card shows (Settings, Start screen). */
    static final String PREF_HOME_DRAWINGS = "home_show_drawings";
    static final String PREF_HOME_REFERENCES = "home_show_references";
    static final String PREF_HOME_TIMELAPSES = "home_show_timelapses";
    /** A tap on a start-screen drawing shows its page (as in All drawings) instead of opening SAI. */
    static final String PREF_HOME_TAP_PAGE = "home_tap_shows_page";

    /** Fills the Drawings card's rows of thumbnails: the newest drawings (a
     *  tap opens one in SAI), and, when turned on, the reference pictures
     *  (open in SAI too) and the timelapses (a tap plays one). */
    private void showRecent() {
        File folder = SaiPenSetup.drawingsFolder();
        int round = ++recentRound;
        int px = (int) (RECENT_H_DP * getResources().getDisplayMetrics().density);
        boolean wantDrawings = prefs.getBoolean(PREF_HOME_DRAWINGS, true);
        boolean wantRefs = prefs.getBoolean(PREF_HOME_REFERENCES, false);
        boolean wantTimelapses = prefs.getBoolean(PREF_HOME_TIMELAPSES, false);
        /* the format chips are All drawings' own filter: the start screen
           shows every drawing (a hidden .sai2 there emptied this row,
           2026-11-27) */
        thumbs.execute(() -> {
            List<File> listed;
            try {
                listed = RecentDrawings.newest(folder, Integer.MAX_VALUE);
            }
            catch (Throwable t) {
                Log.e(TAG, "start screen: listing the drawings", t);
                listed = new ArrayList<>();
            }
            List<File> every = listed;
            /* references are the artist's, not drawings: their own row */
            List<File> own = new ArrayList<>(), refs = new ArrayList<>();
            for (File f : every) (RecentDrawings.isReference(f, folder) ? refs : own).add(f);
            List<File> files = wantDrawings ? first(own) : new ArrayList<>();
            List<File> refFiles = wantRefs ? first(refs) : new ArrayList<>();
            List<android.graphics.Bitmap> pictures = new ArrayList<>(), refPictures = new ArrayList<>();
            /* one picture that can't be read must not keep the whole row away */
            for (File f : files) pictures.add(safeThumbnail(f, px));
            for (File f : refFiles) refPictures.add(safeThumbnail(f, px));
            /* the time on each, for the line under its picture (DrawingDays) */
            List<DrawingDays.Summary> spent = new ArrayList<>();
            try {
                java.util.Map<String, DrawingDays.Summary> sums = DrawingDays.summaries(
                    CanvasTime.load(new File(getFilesDir(), CanvasTime.FILE)).all(), java.time.ZoneId.systemDefault());
                for (File f : files) spent.add(sums.get(drawingKey(folder, f)));
            }
            catch (RuntimeException e) {
                Log.w(TAG, "start screen: no time per drawing", e);
                spent.clear();
            }
            List<DrawingLibrary.Drawing> videos = new ArrayList<>();
            List<android.graphics.Bitmap> stills = new ArrayList<>();
            if (wantTimelapses) {
                try {
                    for (DrawingLibrary.Drawing d : TimelapseShelf.load().newestFirst()) {
                        if (videos.size() >= RecentDrawings.MAX) break;
                        if (d.pieces.isEmpty() || !TimelapseShelf.joined(d, 0).isFile()) continue;
                        videos.add(d);
                        File still = TimelapseShelf.still(d);
                        stills.add(still.isFile() ? pictureFile(still, px) : null);
                    }
                }
                catch (Throwable e) {
                    Log.w(TAG, "start screen: no timelapse list", e);
                    while (stills.size() < videos.size()) stills.add(null);
                }
            }
            ui.post(() -> {
                if (round != recentRound || isDestroyed()) return;
                List<String> titles = new ArrayList<>(), refTitles = new ArrayList<>(), videoTitles = new ArrayList<>();
                List<Runnable> taps = new ArrayList<>(), refTaps = new ArrayList<>(), videoTaps = new ArrayList<>();
                for (File f : files) { titles.add(RecentDrawings.title(f)); taps.add(() -> tapDrawing(f)); }
                for (File f : refFiles) { refTitles.add(RecentDrawings.title(f)); refTaps.add(() -> tapDrawing(f)); }
                for (DrawingLibrary.Drawing d : videos) {
                    videoTitles.add(d.title.isEmpty() ? d.folder : d.title);
                    videoTaps.add(() -> TimelapsesDialog.play(this, d));
                }
                List<File> videoFiles = new ArrayList<>();
                for (DrawingLibrary.Drawing d : videos) videoFiles.add(TimelapseShelf.joined(d, 0));
                List<String> details = new ArrayList<>();
                for (DrawingDays.Summary d : spent) details.add(d == null ? "" : recentLine(d));
                fillRow(R.id.SaiPenRecentScroll, R.id.SaiPenRecent, 0, titles, pictures, taps, R.string.saipen_recent_open, null,
                        details.size() == titles.size() ? details : null);
                fillRow(R.id.SaiPenRefsScroll, R.id.SaiPenRefs, R.id.SaiPenRefsLabel, refTitles, refPictures, refTaps,
                        R.string.saipen_recent_open, null, null);
                fillRow(R.id.SaiPenTlScroll, R.id.SaiPenTl, R.id.SaiPenTlLabel, videoTitles, stills, videoTaps, 0, videoFiles, null);
                Log.i(TAG, "start screen: " + files.size() + " drawing(s)" + (wantDrawings ? "" : " (row off)") + " of "
                      + own.size() + ", " + refFiles.size() + " reference(s)" + (wantRefs ? "" : " (row off)") + ", "
                      + videos.size() + " timelapse(s)" + (wantTimelapses ? "" : " (row off)"));
                ((TextView) findViewById(R.id.SaiPenAllDrawingsValue))
                    .setText(own.isEmpty() ? "" : String.valueOf(own.size()));
            });
        });
    }

    /** The newest RecentDrawings.MAX of `files`. */
    private static List<File> first(List<File> files) {
        return new ArrayList<>(files.subList(0, Math.min(files.size(), RecentDrawings.MAX)));
    }

    /** The SD cards and USB drives mounted now, as folders SAI can reach. */
    private List<String> removableVolumes() {
        List<String> out = new ArrayList<>();
        try {
            android.os.storage.StorageManager sm = getSystemService(android.os.storage.StorageManager.class);
            for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable() || !android.os.Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                File dir = null;
                if (android.os.Build.VERSION.SDK_INT >= 30) dir = v.getDirectory();
                if (dir == null && v.getUuid() != null) dir = new File("/storage/" + v.getUuid());
                if (dir != null && dir.isDirectory()) out.add(dir.getPath());
            }
        }
        catch (RuntimeException e) {
            Log.w(TAG, "drives: SD cards and USB drives not listed", e);
        }
        return out;
    }

    /** A start-screen drawing tapped: into SAI, or its page when the artist chose so. */
    private void tapDrawing(File f) {
        if (prefs.getBoolean(PREF_HOME_TAP_PAGE, false)) DrawingsDialog.showDrawing(this, thumbs, f);
        else openDrawing(f, null);
    }

    /** A start-screen tile's line: the time only, "7 h 20 min", as the
     *  tile is too narrow for the days too (All drawings shows them). */
    private String recentLine(DrawingDays.Summary d) {
        return d.seconds < 60 ? getString(R.string.saipen_under_minute_short) : duration(d.seconds);
    }

    /** A drawing's key in CanvasTime: its path under SAI Drawings; null outside it. */
    static String drawingKey(File drawingsFolder, File drawing) {
        try {
            java.nio.file.Path rel = drawingsFolder.toPath().relativize(drawing.toPath());
            String key = rel.toString().replace(File.separatorChar, '/');
            return key.isEmpty() || key.startsWith("..") ? null : key;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** "All drawings": every drawing, searched and sorted (DrawingsDialog). */
    private void openAllDrawings() {
        DrawingsDialog.show(this, thumbs);
    }

    /** DrawingsDialog deleted some: the row of recent ones and the count follow. */
    void drawingsChanged() {
        if (!isDestroyed()) showRecent();
    }

    /** Where SAI's C: drive is, for the copies inside the app; null before the container is ready. */
    File containerRootDir() {
        return container == null ? null : container.getRootDir();
    }

    /** The drawing's picture, no bigger than about twice `maxPx` across: the
     *  small one SAI or Photoshop keeps in a .sai2 or .psd/.psb, the picture
     *  itself for .png, .jpg and .bmp. Null if it cannot be read, and for
     *  .sai and .tga, which Android cannot show. */
    static android.graphics.Bitmap thumbnail(File drawing, int maxPx) {
        try {
            String kind = DrawingFormats.of(drawing);
            byte[] jpeg;
            if (kind.equals("sai2")) jpeg = Sai2Thumb.jpeg(drawing);
            else if (kind.equals("psd") || kind.equals("psb")) jpeg = PsdThumb.jpeg(drawing);
            else if (kind.equals("png") || kind.equals("jpg") || kind.equals("bmp")) return pictureFile(drawing, maxPx);
            else return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        }
        catch (IOException | RuntimeException e) {
            Log.w(TAG, "no thumbnail for " + drawing.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /** A picture file decoded no bigger than about twice `maxPx` across. */
    static android.graphics.Bitmap pictureFile(File f, int maxPx) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
        android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
        d.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeFile(f.getPath(), d);
    }

    /** One row of thumbnails with names under them; empty hides the row
     *  and its label (`labelId`, 0 for none). `describe` reads "Open %s"
     *  for a picture, 0 to use the name alone. */
    /** A thumbnail, or null if anything at all goes wrong reading it. */
    private static android.graphics.Bitmap safeThumbnail(File f, int px) {
        try {
            return thumbnail(f, px);
        }
        catch (Throwable t) {
            Log.w(TAG, "start screen: no picture for " + f.getName() + ": " + t);
            return null;
        }
    }

    /** `videos` (null for none): a timelapse per item, played in the
     *  picture's place while the pen hovers over it (HoverVideo). */
    private void fillRow(int scrollId, int rowId, int labelId, List<String> titles,
                         List<android.graphics.Bitmap> pictures, List<Runnable> taps, int describe,
                         List<File> videos, List<String> details) {
        View scroll = findViewById(scrollId);
        android.widget.LinearLayout row = findViewById(rowId);
        row.removeAllViews();
        scroll.setVisibility(titles.isEmpty() ? View.GONE : View.VISIBLE);
        if (labelId != 0) findViewById(labelId).setVisibility(titles.isEmpty() ? View.GONE : View.VISIBLE);
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < titles.size(); i++) {
            String title = titles.get(i);
            Runnable tap = taps.get(i);

            android.widget.LinearLayout item = new android.widget.LinearLayout(this);
            item.setOrientation(android.widget.LinearLayout.VERTICAL);
            item.setBackgroundResource(R.drawable.saipen_row);
            item.setPadding((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));
            item.setContentDescription(describe != 0 ? getString(describe, title) : title);
            item.setOnClickListener(v -> tap.run());

            int pad = (int) (3 * dp);
            View picture;
            if (videos != null && i < videos.size()) {
                HoverVideo hv = new HoverVideo(this);
                hv.setBackgroundResource(R.drawable.saipen_tile);
                hv.setPadding(pad, pad, pad, pad);
                hv.setStill(pictures.get(i));
                hv.setVideo(videos.get(i));
                picture = hv;
            }
            else {
                android.widget.ImageView img = new android.widget.ImageView(this);
                img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                img.setBackgroundResource(R.drawable.saipen_tile);
                img.setPadding(pad, pad, pad, pad);
                if (pictures.get(i) != null) img.setImageBitmap(pictures.get(i));
                picture = img;
            }
            item.addView(picture, new android.widget.LinearLayout.LayoutParams(
                (int) (RECENT_W_DP * dp), (int) (RECENT_H_DP * dp)));

            TextView name = new TextView(this);
            name.setText(title);
            name.setTextSize(12);
            name.setTextColor(StatisticsDialog.TEXT2);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, (int) (4 * dp), 0, 0);
            item.addView(name, new android.widget.LinearLayout.LayoutParams(
                (int) (RECENT_W_DP * dp), android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            /* days and time on it; an empty line keeps the row's tiles even */
            if (details != null) {
                String detail = details.get(i);
                TextView spent = new TextView(this);
                spent.setText(detail.isEmpty() ? " " : detail);
                spent.setTextSize(11);
                spent.setTextColor(StatisticsDialog.TEXT3);
                spent.setSingleLine(true);
                spent.setEllipsize(android.text.TextUtils.TruncateAt.END);
                item.addView(spent, new android.widget.LinearLayout.LayoutParams(
                    (int) (RECENT_W_DP * dp), android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                if (!detail.isEmpty()) item.setContentDescription(item.getContentDescription() + ", " + detail);
            }

            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (6 * dp));
            row.addView(item, lp);
        }
    }

    /** False (with a toast) while SAI cannot start yet. `onStarting`, if
     *  any, runs when SAI is about to start, after the leftover timelapse
     *  frames are packed (that can take seconds). */
    boolean openDrawing(File drawing, Runnable onStarting) {
        if (container == null || saiDir == null) {
            Toast.makeText(this, R.string.saipen_recent_not_ready, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (makingVideo) {
            Toast.makeText(this, R.string.saipen_wait_for_video, Toast.LENGTH_LONG).show();
            return false;
        }
        drawingToOpen = drawing;
        onSaiStarting = onStarting;
        launch();
        return true;
    }

    /** Frames left from an earlier SAI start are packed into their own
     *  folder first: the DLL numbers frames from 1 again and would write
     *  over them (2026-11-23). */
    private void launch() {
        if (container == null || saiDir == null || packingBeforeStart) return;
        if (makingVideo) {
            /* a tap that did nothing looked broken */
            Toast.makeText(this, R.string.saipen_wait_for_video, Toast.LENGTH_LONG).show();
            return;
        }
        File root = container.getRootDir();
        if (TimelapseVideo.countFrames(TimelapseVideo.framesDir(root)) == 0) {
            launchNow();
            return;
        }
        packingBeforeStart = true;
        btnStart.setEnabled(false);
        /* packing can take seconds: say that SAI is on its way */
        CharSequence startLabel = btnStart.getText();
        btnStart.setText(R.string.saipen_starting);
        worker.execute(() -> {
            try {
                TimelapseVideo.packLeftovers(root);
            }
            catch (Exception e) {
                Log.e(TAG, "timelapse: packing the frames left from before", e);
            }
            runOnUiThread(() -> {
                packingBeforeStart = false;
                btnStart.setEnabled(true);
                btnStart.setText(startLabel);
                launchNow();
            });
        });
    }

    private void launchNow() {
        Runnable starting = onSaiStarting;
        onSaiStarting = null;
        if (starting != null) starting.run();
        if (container == null || saiDir == null) return;
        SaiLocator.Found f = SaiLocator.best(SaiLocator.scan(saiDir, 0));
        if (f == null) {
            prefs.edit().remove(PREF_SAI_DIR).apply();
            locate();
            return;
        }
        if (f.is32Bit()) {
            changeSai();
            return;
        }
        installLicence();
        /* E: is the device's own storage, as the README says. Winlator's
         * INTERNAL_STORAGE is a folder inside the app, empty: E: showed
         * nothing (the owner, 2026-11-23). D: (Downloads) proves the app
         * reads the device's storage by path. */
        File storage = android.os.Environment.getExternalStorageDirectory();
        File gallery = new File(new File(AppUtils.INTERNAL_STORAGE).getParentFile(), "gallery");
        try {
            SaiLocator.galleryLinks(gallery, storage);
        }
        catch (IOException | RuntimeException e) {
            Log.w(TAG, "G: (the gallery) not set up", e);
        }
        List<String> removable = removableVolumes();
        Log.i(TAG, "drives: " + removable.size() + " SD card(s) or USB drive(s) as H: on " + removable);
        container.setDrives(SaiLocator.drives(saiDir.getPath(), AppUtils.DIRECTORY_DOWNLOADS, storage.getPath(),
            SaiPenSetup.drawingsFolder().getPath(), gallery.getPath(), removable));
        container.setScreenSize(wineScreenSize());
        /* No Microsoft components: SAI draws with GDI and needs none of the
         * DirectX parts, Visual C++ runtimes or Windows Media decoder, and
         * the APK does not carry them (build.sh). Turning them off also puts
         * back Wine's own DLLs in a prefix that had them on. */
        container.setWinComponents(com.winlator.container.Container.FALLBACK_WINCOMPONENTS);
        boolean record = prefs.getBoolean(PREF_TIMELAPSE, FirstRun.TIMELAPSE);
        String env = EnvVarsEdit.set(container.getEnvVars(), "WT_TIMELAPSE", record ? "1" : null);
        /* room for frames before the DLL thins them: a quarter of the free space (TimelapseBudget) */
        long budgetMb = TimelapseBudget.megabytes(getFilesDir().getUsableSpace(),
                                                  timelapseRoomChoice());
        env = EnvVarsEdit.set(env, "WT_TIMELAPSE_BUDGET_MB", record ? String.valueOf(budgetMb) : null);
        /* the same room for the app's packed frames (TimelapseRecorder) */
        TimelapseRecorder.budgetBytes = budgetMb << 20;
        if (record) Log.i(TAG, "timelapse budget: " + budgetMb + " MB");
        env = EnvVarsEdit.set(env, "WT_TIMELAPSE_QUIET_MS",
                              record ? String.valueOf(prefs.getInt(PREF_TIMELAPSE_PAUSE, TIMELAPSE_PAUSES_MS[0])) : null);
        env = EnvVarsEdit.set(env, "WT_TIMELAPSE_MAX_WAIT_MS", record ? TIMELAPSE_MAX_WAIT_MS : null);
        /* how many processors SAI sees (SaiProcessors): ALL leaves it out */
        int processors = saiProcessors();
        env = EnvVarsEdit.set(env, SaiProcessors.ENV, SaiProcessors.envValue(processors));
        Log.i(TAG, "processors SAI sees: " + (processors == SaiProcessors.ALL ? "all" : processors));
        /* speed tests only: `adb shell setprop debug.saipen.env "NAME=value ..."` (DebugEnv) */
        String[] debug = DebugEnv.apply(env, PenInput.systemProperty(DebugEnv.PROP),
                                        prefs.getString(DebugEnv.PREF_KEYS, ""));
        env = debug[0];
        prefs.edit().putString(DebugEnv.PREF_KEYS, debug[1]).apply();
        if (!debug[1].isEmpty()) Log.i(TAG, "speed test env: " + PenInput.systemProperty(DebugEnv.PROP).trim());
        container.setEnvVars(env);
        container.saveData();

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", f.exe().getPath());
        /* "The first start takes longer" only for a SAI not started here yet:
           a new install, another SAI, or its file changed (StartupCover) */
        StartupCover.saiIdentity = StartupCover.identity(f.exe());
        String windowsPath = drawingToOpen != null
            ? RecentDrawings.windowsPath(drawingToOpen, SaiPenSetup.drawingsFolder()) : null;
        /* XServerDisplayActivity puts it after SAI's name on Wine's command line (winlator.patch) */
        if (windowsPath != null) intent.putExtra("exec_args", RecentDrawings.commandLineArg(windowsPath));
        startActivity(intent);
        finish();
    }

    /* ---- SAI's licence certificate ---- */

    /** Where SAI Ver.2 reads its certificate, inside this app's Wine. */
    private File saiV2Dir() {
        return new File(container.getRootDir(),
            ".wine/drive_c/users/" + RootFS.USER + "/Documents/SYSTEMAX Software Development/SAIv2");
    }

    private List<File> licenceRoots() {
        List<File> roots = new ArrayList<>();
        if (saiDir != null) roots.add(saiDir);
        roots.add(new File(AppUtils.DIRECTORY_DOWNLOADS));
        return roots;
    }

    /** A newer sai*.slc in Downloads or SAI's folder goes where SAI reads it,
     *  but only once the person said yes to the licence search. */
    private void installLicence() {
        if (container == null || !prefs.getBoolean(PREF_LICENCE_SEARCH, false)) return;
        try {
            SaiLocator.autoInstallLicence(SaiLocator.newestLicence(licenceRoots(), 2, ownIdSince()), saiV2Dir());
        }
        catch (IOException ignored) {
            /* SAI then runs as before, without the licence */
        }
    }

    private void refreshLicence() {
        if (container == null) return;
        List<File> installed = SaiLocator.installedLicences(saiV2Dir());
        if (installed.isEmpty()) {
            tvLicence.setText(R.string.saipen_licence_none);
        }
        else {
            /* dots, as everywhere else: the number is never on the screen,
             * so it cannot end up in a screenshot (the owner, 2026-11-23;
             * the tap-to-show spoiler before it was not needed) */
            tvLicence.setText(getString(R.string.saipen_licence_ok,
                                        SaiLocator.maskedLicenceName(installed.get(0).getName())));
        }
        findViewById(R.id.SaiPenLicenceCard).setVisibility(View.VISIBLE);
        tvLicence.setVisibility(View.VISIBLE);
        tvLicenceHint.setVisibility(installed.isEmpty() ? View.VISIBLE : View.GONE);
        btnLicenceTitle.setText(installed.isEmpty() ? R.string.saipen_add_licence : R.string.saipen_change_licence);
        btnLicence.setVisibility(View.VISIBLE);
        refreshBrushes();
    }

    /* ---- brushes and textures: SAI's settings folder on the device ---- */

    /** Counts what SAI has, after making sure its settings folder is the
     *  one in Documents (SaiPenSetup.linkSettings says why first). */
    private void refreshBrushes() {
        if (container == null) return;
        File root = container.getRootDir();
        worker.execute(() -> {
            File settings = SaiPenSetup.settingsFolder();
            boolean ready = SaiPenSetup.linkSettings(root) && BrushImport.hasSaiSettings(settings);
            int[] n = ready ? BrushImport.count(settings) : null;
            ui.post(() -> {
                /* the tiles once SAI has its folder; before, a line why there are none */
                tvBrushes.setText(R.string.saipen_brushes_unknown);
                tvBrushes.setVisibility(n == null ? View.VISIBLE : View.GONE);
                findViewById(R.id.SaiPenKinds).setVisibility(n == null ? View.GONE : View.VISIBLE);
                findViewById(R.id.SaiPenBrowseAll).setVisibility(n == null ? View.GONE : View.VISIBLE);
                if (n != null && isResumedForPreviews()) BrowseThumbs.get(this).start(settings, false);
                if (n != null) {
                    int[] perKind = {n[BrushImport.BRUSH_TEXTURES], n[BrushImport.PAPER_TEXTURES],
                                     n[3] + n[4] + n[5], n[BrushImport.SCATTER], n[BrushImport.FOLDERS.length]};
                    for (int k = 0; k < perKind.length; k++) {
                        ((TextView) findViewById(KIND_COUNTS[k])).setText(String.valueOf(perKind[k]));
                    }
                }
                findViewById(R.id.SaiPenBrushesCard).setVisibility(View.VISIBLE);
                brushesActions.setVisibility(View.VISIBLE);
                btnOpenBrushes.setVisibility(
                    folderIntent().resolveActivity(getPackageManager()) != null ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** "342 brush textures, 474 paper textures, ...": the kinds there are
     *  some of, or all of them when `all`. */
    private String kinds(int brushTextures, int paperTextures, int brushShapes, int scatter, int tools, boolean all) {
        StringBuilder b = new StringBuilder();
        int[] counts = {brushTextures, paperTextures, brushShapes, scatter, tools};
        int[] names = {R.plurals.saipen_n_brush_textures, R.plurals.saipen_n_paper_textures,
                       R.plurals.saipen_n_brush_shapes, R.plurals.saipen_n_scatter, R.plurals.saipen_n_tools};
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] < 0 || (!all && counts[i] == 0)) continue;
            if (b.length() > 0) b.append(", ");
            b.append(getResources().getQuantityString(names[i], counts[i], counts[i]));
        }
        return b.toString();
    }

    private boolean isResumedForPreviews() {
        return !isFinishing() && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
    }

    /** The brushes and textures browser, on one kind's page. */
    private void openInSai(int kind) {
        openBrowser(BrowseDialog.pageFor(kind));
    }

    private void openBrowser(int page) {
        BrowseDialog.show(this, SaiPenSetup.settingsFolder(), page, this::brushesInfo, this::pickBrushes,
            folderIntent().resolveActivity(getPackageManager()) != null ? this::openBrushesFolder : null,
            this::refreshBrushes);
    }

    /** SAI's settings folder in the device's file manager. */
    private Intent folderIntent() {
        return folderIntent(SaiPenSetup.SETTINGS_PATH);
    }

    /** Documents/`path` in the device's file manager, where one can open it. */
    private Intent folderIntent(String path) {
        Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
            "primary:" + Environment.DIRECTORY_DOCUMENTS + "/" + path);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private void openBrushesFolder() {
        try {
            startActivity(folderIntent());
        }
        catch (RuntimeException e) {
            Toast.makeText(this, R.string.saipen_brushes_where_toast, Toast.LENGTH_LONG).show();
        }
    }

    /** Documents/SAI Drawings in the device's file manager: share, copy or
     *  open the drawings from there. */
    private void openDrawingsFolder() {
        SaiPenSetup.drawingsFolder().mkdirs();
        SaiPenSetup.scanDrawings(this);
        try {
            startActivity(folderIntent(SaiPenSetup.DRAWINGS));
        }
        catch (RuntimeException e) {
            Toast.makeText(this, R.string.saipen_drawings_where_toast, Toast.LENGTH_LONG).show();
        }
    }

    /** Documents/SAI Drawings/Timelapses in the device's file manager. */
    void openTimelapseFolder() {
        SaiPenSetup.timelapseFolder().mkdirs();
        try {
            startActivity(folderIntent(SaiPenSetup.DRAWINGS + "/" + SaiPenSetup.TIMELAPSES));
        }
        catch (RuntimeException e) {
            Toast.makeText(this, R.string.saipen_drawings_where_toast, Toast.LENGTH_LONG).show();
        }
    }

    /** (i) beside "SAI licence": SAI Pen is free, SAI is SYSTEMAX's. */
    private void licenceInfo() {
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_licence_info_title)
            .setMessage(R.string.saipen_licence_info_text)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    /** (i): what SAI's settings folder is and what the two actions do. */
    private void rowInfo(int title, int text) {
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(title).setMessage(text)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private void brushesInfo() {
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_brushes_info_title)
            .setMessage(R.string.saipen_brushes_info_text)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    /** "Bring my settings from a PC": how to make the zip, then look for it
     *  (only now, on the person's tap) or let them choose it. */
    private void askForPcSettings() {
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_pc_settings)
            .setMessage(R.string.saipen_pc_settings_steps)
            .setPositiveButton(R.string.saipen_pc_look, (d, w) -> lookForPcSettings())
            .setNeutralButton(R.string.saipen_pc_choose, (d, w) -> pickPcSettings())
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickPcSettings() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PC_SETTINGS);
    }

    /**
     * Zips and folders with settings2.ssd in the device storage, as deep as
     * the search for SAI goes. The device's own settings (SAIv2, with its
     * backups) are left out: SAI already has those.
     */
    private void lookForPcSettings() {
        AlertDialog wait = waitDialog(getString(R.string.saipen_pc_searching));
        worker.execute(() -> {
            File storage = Environment.getExternalStorageDirectory();
            File ownSaiV2 = SaiPenSetup.settingsFolder().getParentFile();
            List<PcSettings.Found> found = PcSettings.find(storage, 4, ownSaiV2);
            ui.post(() -> {
                wait.dismiss();
                if (found.isEmpty()) {
                    new AlertDialog.Builder(this, DIALOG_THEME).setMessage(R.string.saipen_pc_none)
                        .setPositiveButton(R.string.saipen_pc_choose, (d, w) -> pickPcSettings())
                        .setNegativeButton(android.R.string.cancel, null).show();
                }
                else if (found.size() == 1) readPcSettings(found.get(0));
                else pickPcSettingsFrom(found);
            });
        });
    }

    /** More than one: newest first, each with where it is and its date. */
    private void pickPcSettingsFrom(List<PcSettings.Found> found) {
        String storage = Environment.getExternalStorageDirectory().getPath() + "/";
        java.text.DateFormat date = android.text.format.DateFormat.getMediumDateFormat(this);
        String[] lines = new String[found.size()];
        for (int i = 0; i < lines.length; i++) {
            File f = found.get(i).file;
            String where = f.getPath().startsWith(storage) ? f.getPath().substring(storage.length()) : f.getPath();
            lines[i] = where + "\n" + date.format(new java.util.Date(found.get(i).modified));
        }
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_pc_which)
            .setItems(lines, (d, which) -> readPcSettings(found.get(which)))
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void readPcSettings(PcSettings.Found f) {
        readBrushes(f.zip ? BrushImport.zip(() -> new java.io.FileInputStream(f.file))
                          : BrushImport.folder(f.file), true);
    }

    /** Pictures, or a zip of a pack or of a PC's settings folder. */
    private void pickBrushes() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_BRUSHES);
    }

    /** What was picked, read file by file: zips opened, a picture as it is. */
    private BrushImport.Source pickedFiles(List<Uri> uris) {
        List<String> names = new ArrayList<>();
        for (Uri u : uris) names.add(displayName(u));
        return visitor -> {
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                if (names.get(i).toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    BrushImport.zip(() -> open(uri)).visit(visitor);
                }
                else {
                    try (InputStream in = open(uri)) {
                        visitor.file(names.get(i), in);
                    }
                }
            }
        };
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + uri);
        return in;
    }

    /** pcSettings: from "Bring my settings from a PC", where only a whole
     *  settings folder will do; otherwise a pack or a settings folder. */
    private void readBrushes(BrushImport.Source picked, boolean pcSettings) {
        if (container == null) return;
        File root = container.getRootDir();
        AlertDialog wait = waitDialog(getString(R.string.saipen_brushes_reading));
        worker.execute(() -> {
            File settings = SaiPenSetup.settingsFolder();
            boolean linked = SaiPenSetup.linkSettings(root);
            BrushImport.Source source = null;
            BrushImport.Plan plan = null;
            String error = null;
            try {
                source = picked;
                plan = BrushImport.scan(source);
            }
            catch (IOException | RuntimeException e) {
                Log.e(TAG, "reading brushes", e);
                error = String.valueOf(e.getMessage());
            }
            final BrushImport.Source src = source;
            final BrushImport.Plan p = plan;
            final String err = error;
            ui.post(() -> {
                wait.dismiss();
                if (err != null) message(getString(R.string.saipen_brushes_failed, err));
                else if (!linked) message(getString(R.string.saipen_brushes_no_folder));
                else if (pcSettings && !p.isWholeSettings()) message(getString(R.string.saipen_pc_not_settings));
                else if (p.isEmpty()) message(getString(R.string.saipen_brushes_nothing));
                else if (p.isWholeSettings()) askToReplace(src, p, settings);
                else if (!BrushImport.hasSaiSettings(settings)) message(getString(R.string.saipen_brushes_start_first));
                else if (p.sorted() > 0) askToAdd(src, p, settings);
                else askWhatTheyAre(src, p, settings);
            });
        });
    }

    private void askToAdd(BrushImport.Source src, BrushImport.Plan p, File settings) {
        String text = getString(R.string.saipen_brushes_add_q, kinds(p.pictures[BrushImport.BRUSH_TEXTURES],
            p.pictures[BrushImport.PAPER_TEXTURES], p.brushShapes(), p.pictures[BrushImport.SCATTER], -1, false));
        if (p.unsorted > 0) text += "\n\n" + getString(R.string.saipen_brushes_left_out, p.unsorted);
        new AlertDialog.Builder(this, DIALOG_THEME).setMessage(text)
            .setPositiveButton(R.string.saipen_brushes_add, (d, w) -> addBrushes(src, settings, -1, p.files))
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Pictures with no folder that says what they are: the person says. */
    private void askWhatTheyAre(BrushImport.Source src, BrushImport.Plan p, File settings) {
        final int[] folders = {BrushImport.BRUSH_TEXTURES, BrushImport.PAPER_TEXTURES,
                               BrushImport.BLOTMAP, BrushImport.SCATTER};
        String[] names = {getString(R.string.saipen_kind_brush_textures), getString(R.string.saipen_kind_paper_textures),
                          getString(R.string.saipen_kind_brush_shapes), getString(R.string.saipen_kind_scatter)};
        String title = p.unsorted == 1 ? getString(R.string.saipen_brushes_what_one)
                                       : getString(R.string.saipen_brushes_what, p.unsorted);
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(title)
            .setItems(names, (d, which) -> addBrushes(src, settings, folders[which], p.unsortedFiles))
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askToReplace(BrushImport.Source src, BrushImport.Plan p, File settings) {
        String backup = "settings-before-"
            + new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.ROOT).format(new java.util.Date());
        new AlertDialog.Builder(this, DIALOG_THEME).setMessage(getString(R.string.saipen_brushes_replace_q, backup))
            .setPositiveButton(R.string.saipen_brushes_replace, (d, w) -> {
                AlertDialog wait = waitDialog(getString(R.string.saipen_brushes_adding, 0, p.files));
                worker.execute(() -> {
                    String text;
                    try {
                        BrushImport.Result r = BrushImport.replace(src, p.settingsRoot, settings, backup, p.files,
                                                                   progressTo(wait));
                        text = getString(R.string.saipen_brushes_replaced, r.files,
                                         r.backup != null ? r.backup.getName() : "-");
                    }
                    catch (IOException | RuntimeException e) {
                        Log.e(TAG, "replacing SAI's settings", e);
                        text = getString(R.string.saipen_brushes_failed, String.valueOf(e.getMessage()));
                    }
                    finished(wait, text);
                });
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addBrushes(BrushImport.Source src, File settings, int forced, int total) {
        AlertDialog wait = waitDialog(getString(R.string.saipen_brushes_adding, 0, total));
        worker.execute(() -> {
            String text;
            try {
                BrushImport.Result r = BrushImport.add(src, settings, forced, total, progressTo(wait));
                text = r.addedPictures() == 0 ? getString(R.string.saipen_brushes_added_none)
                    : getString(R.string.saipen_brushes_added, kinds(r.added[BrushImport.BRUSH_TEXTURES],
                        r.added[BrushImport.PAPER_TEXTURES], r.addedBrushShapes(), r.added[BrushImport.SCATTER], -1, false));
                if (r.kept > 0) text += "\n\n" + getString(R.string.saipen_brushes_kept, r.kept);
            }
            catch (IOException | RuntimeException e) {
                Log.e(TAG, "adding brushes", e);
                text = getString(R.string.saipen_brushes_failed, String.valueOf(e.getMessage()));
            }
            finished(wait, text);
        });
    }

    /** "Adding to SAI: 512 of 2195", a few times a second at most. */
    private BrushImport.Progress progressTo(AlertDialog wait) {
        final long[] last = {0};
        return (done, total) -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - last[0] < 200 && done < total) return;
            last[0] = now;
            String text = getString(R.string.saipen_brushes_adding, done, Math.max(done, total));
            ui.post(() -> wait.setMessage(text));
        };
    }

    private void finished(AlertDialog wait, String text) {
        ui.post(() -> {
            wait.dismiss();
            message(text);
            refreshBrushes();
        });
    }

    private AlertDialog waitDialog(String text) {
        AlertDialog d = new AlertDialog.Builder(this, DIALOG_THEME).setMessage(text).setCancelable(false).create();
        d.show();
        return d;
    }

    private void message(String text) {
        new AlertDialog.Builder(this, DIALOG_THEME).setMessage(text)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    /** For a certificate kept anywhere else: the system's file picker. */
    private void pickLicence() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_LICENCE);
    }

    private void addLicence(Uri uri) {
        if (container == null) return;
        String name = displayName(uri);
        File saiV2 = saiV2Dir();
        worker.execute(() -> {
            boolean ok;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open " + uri);
                SaiLocator.installLicence(readAtMost(in, SaiLocator.MAX_LICENCE_BYTES + 1), name, saiV2);
                ok = true;
            }
            catch (IOException e) {
                ok = false;
            }
            boolean added = ok;
            ui.post(() -> {
                refreshLicence();
                Toast.makeText(this, added ? R.string.saipen_licence_added : R.string.saipen_licence_bad,
                               Toast.LENGTH_LONG).show();
                if (added) prefs.edit().putBoolean(PREF_LICENCE_ASKED, true).apply();
            });
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        }
        catch (RuntimeException ignored) {
            /* some providers refuse the query: fall back to the uri */
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }

    /** Reads up to `limit` bytes: a bigger file then fails the size check. */
    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (out.size() < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - out.size()))) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /* ---- screen states ---- */

    private void busy(int textId) {
        tvStatus.setText(textId);
        progress.setVisibility(View.VISIBLE);
        btnStart.setVisibility(View.GONE);
        btnUnzip.setVisibility(View.GONE);
        btnSite.setVisibility(View.GONE);
        btnSearch.setEnabled(false);
        btnChoose.setEnabled(false);
    }

    private void idle(String text) {
        tvStatus.setText(text);
        progress.setVisibility(View.GONE);
        btnSearch.setEnabled(true);
        btnChoose.setEnabled(true);
    }

    /** NOTICE from the APK: GPL 3, the attribution terms and what the app
     *  is built on (section 7(b) asks for it wherever credits are shown). */
    private void showNotice() {
        String text = FileUtils.readString(this, "saipen/NOTICE");
        if (text == null || text.isEmpty()) text = getString(R.string.saipen_credits_plain);
        /* and every part inside the APK, with its licence and source */
        String parts = FileUtils.readString(this, "saipen/THIRD_PARTY.md");
        if (parts != null && !parts.isEmpty()) text += "\n\n" + parts;
        TextView tv = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        /* the dialog is light whatever the app's theme: without this the
         * text took the dark screen's white and could not be seen */
        tv.setTextColor(0xFF1D1F23);
        tv.setText(text);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_about).setView(scroll)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    /** LICENSES.txt from the APK: the licence text and copyright notice of
     *  every part inside it (tools/collect-licenses.py makes it). A WebView,
     *  as it is half a megabyte: too much for a TextView to lay out at once. */
    private void showLicences() {
        android.webkit.WebView web = new android.webkit.WebView(this);
        web.getSettings().setJavaScriptEnabled(false);
        web.getSettings().setAllowFileAccess(true);
        web.loadUrl("file:///android_asset/saipen/LICENSES.txt");
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(R.string.saipen_licences).setView(web)
            .setPositiveButton(android.R.string.ok, null).show();
    }
}
