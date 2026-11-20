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
    /** Start SAI as soon as SAI Pen opens. Off unless the person turns it on:
     *  the screen with the settings must be reachable (the owner, 2026-11-19). */
    private static final String PREF_AUTOSTART = "saipen_autostart";
    /** A light dialog with dark text, set outright: under Winlator's dark
     *  app theme the dialogs came out grey on white, the title unreadable
     *  (seen on the Galaxy S24 Ultra, 2026-11-18). */
    private static final String TAG = "SaiPen";
    private static final String COFFEE_SITE = "https://buymeacoffee.com/ametrien";
    private static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    private static final String SAI_SITE = "https://www.systemax.jp/en/sai/devdept.html";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView tvStatus, tvSaiPath, tvLicenceHint;
    /** The licence line: its number behind a spoiler, so a screenshot of
     *  the screen does not give it away (the owner, 2026-11-20). */
    private SpoilerTextView tvLicence;
    private Button btnLicence;
    private ProgressBar progress;
    private Button btnVideo;
    private boolean makingVideo;
    private Button btnStart, btnUnzip, btnChoose, btnSite, btnSearch;
    /** How SAI is found: "auto" (search the device) or "manual" (the person
     *  picks the folder). Unset until the first start asks. */
    private static final String PREF_SEARCH = "saipen_search";
    /** Record a timelapse of the canvas (our DLL inside SAI does it). */
    private static final String PREF_TIMELAPSE = "saipen_timelapse";
    /** Frames on the device before the DLL thins out old ones. */
    private static final String TIMELAPSE_BUDGET_MB = "512";
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
    private TextView tvBrushes, tvBrushesWhere;
    private Button btnAddBrushes, btnOpenBrushes;

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
        autoLaunch = savedInstanceState == null && prefs.getBoolean(PREF_AUTOSTART, false)
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
        btnSearch.setOnClickListener(v -> {
            prefs.edit().putString(PREF_SEARCH, "auto").apply();
            locate();
        });
        tvLicence = findViewById(R.id.SaiPenLicenceStatus);
        tvLicenceHint = findViewById(R.id.SaiPenLicenceHint);
        btnLicence = findViewById(R.id.SaiPenAddLicence);
        btnLicence.setOnClickListener(v -> askAboutLicence());
        tvBrushes = findViewById(R.id.SaiPenBrushes);
        tvBrushesWhere = findViewById(R.id.SaiPenBrushesWhere);
        btnAddBrushes = findViewById(R.id.SaiPenAddBrushes);
        btnAddBrushes.setOnClickListener(v -> pickBrushes());
        btnOpenBrushes = findViewById(R.id.SaiPenOpenBrushes);
        btnOpenBrushes.setOnClickListener(v -> openBrushesFolder());
        android.widget.CompoundButton autostart = findViewById(R.id.SaiPenAutostart);
        autostart.setChecked(prefs.getBoolean(PREF_AUTOSTART, false));
        autostart.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(PREF_AUTOSTART, on).apply());
        setUpSharpness();
        setUpFastCores();
        android.widget.CompoundButton timelapse = findViewById(R.id.SaiPenTimelapse);
        timelapse.setChecked(prefs.getBoolean(PREF_TIMELAPSE, false));
        setUpTimelapsePause(timelapse);
        btnVideo = findViewById(R.id.SaiPenMakeVideo);
        btnVideo.setOnClickListener(v -> makeVideo());

        btnStart.setOnClickListener(v -> launch());
        btnUnzip.setOnClickListener(v -> unzip());
        btnChoose.setOnClickListener(v -> chooseFolder());
        btnSite.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SAI_SITE))));

        TextView credits = findViewById(R.id.SaiPenCredits);
        credits.setText(Html.fromHtml(getString(R.string.saipen_credits), Html.FROM_HTML_MODE_LEGACY));
        credits.setMovementMethod(LinkMovementMethod.getInstance());
        TextView coffee = findViewById(R.id.SaiPenCoffee);
        coffee.setText(Html.fromHtml(getString(R.string.saipen_coffee), Html.FROM_HTML_MODE_LEGACY));
        coffee.setMovementMethod(LinkMovementMethod.getInstance());
        findViewById(R.id.SaiPenAbout).setOnClickListener(v -> showNotice());
        findViewById(R.id.SaiPenLicences).setOnClickListener(v -> showLicences());
        findViewById(R.id.SaiPenGestures).setOnClickListener(v -> new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_gestures).setMessage(R.string.saipen_gestures_text)
            .setPositiveButton(android.R.string.ok, null).show());
        findViewById(R.id.SaiPenSettings).setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));

        startup();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showVideoButton();
    }

    /* ---- timelapse: frames from SAI into a video ---- */

    /** The button shows when SAI has recorded frames. */
    private void showVideoButton() {
        if (btnVideo == null || container == null || makingVideo) return;
        int n = TimelapseVideo.countFrames(TimelapseVideo.framesDir(container.getRootDir()));
        btnVideo.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        btnVideo.setEnabled(true);
        btnVideo.setText(getString(R.string.saipen_make_video, n));
    }

    private void makeVideo() {
        if (container == null || makingVideo) return;
        makingVideo = true;
        btnVideo.setEnabled(false);
        File frames = TimelapseVideo.framesDir(container.getRootDir());
        File out = SaiPenSetup.drawingsFolder();
        worker.execute(() -> {
            String message;
            try {
                List<File> made = TimelapseVideo.makeAll(frames, out, (done, total) -> runOnUiThread(
                    () -> btnVideo.setText(getString(R.string.saipen_making_video, done, total))));
                String[] paths = new String[made.size()];
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < paths.length; i++) {
                    paths[i] = made.get(i).getAbsolutePath();
                    names.append(i > 0 ? "\n" : "").append(made.get(i).getName());
                }
                /* so the gallery and the Files app see them at once */
                android.media.MediaScannerConnection.scanFile(this, paths, null, null);
                message = getString(R.string.saipen_video_done, names);
            }
            catch (Exception e) {
                Log.e(TAG, "timelapse video", e);
                message = getString(R.string.saipen_video_failed, String.valueOf(e.getMessage()));
            }
            final String text = message;
            runOnUiThread(() -> {
                makingVideo = false;
                showVideoButton();
                new AlertDialog.Builder(this, DIALOG_THEME).setMessage(text)
                    .setPositiveButton(android.R.string.ok, null).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
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
                else onContainer(created);
            });
        }
        catch (JSONException e) {
            idle(getString(R.string.saipen_container_failed));
        }
    }

    private void onContainer(Container c) {
        container = c;
        showVideoButton();
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
        View box = findViewById(R.id.SaiPenTimelapsePauseBox);
        box.setVisibility(timelapse.isChecked() ? View.VISIBLE : View.GONE);
        timelapse.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(PREF_TIMELAPSE, on).apply();
            box.setVisibility(on ? View.VISIBLE : View.GONE);
        });
    }

    /** "Fast processor cores: Drawing / All of SAI / Off" (SaiCores), one
     *  line like the sharpness, what the choice does under it, and a small
     *  table of each choice's pen-to-screen time so far (CoresCompare).
     *  The choice lives in SaiCores' file, so adb can still change it. */
    private void setUpFastCores() {
        RadioGroup group = findViewById(R.id.SaiPenFastCores);
        TextView what = findViewById(R.id.SaiPenFastCoresWhat);
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
            if (mode == current) {
                b.setChecked(true);
                what.setText(whatText);
            }
            b.setOnCheckedChangeListener((v, checked) -> {
                if (!checked) return;
                what.setText(whatText);
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
        logs.setChecked(prefs.getBoolean(SpeedSession.PREF_SPEED_LOG, true));
        logs.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(SpeedSession.PREF_SPEED_LOG, on).apply());

        /* palm rejection: three checks, each on its own, all off by default */
        palmSwitch(R.id.SaiPenPalmAndroid, PalmFilter.PREF_PALM_ANDROID);
        palmSwitch(R.id.SaiPenPalmPenNear, PalmFilter.PREF_PALM_PEN_NEAR);
        palmSwitch(R.id.SaiPenPalmBig, PalmFilter.PREF_PALM_BIG);
    }

    private void palmSwitch(int id, String pref) {
        android.widget.CompoundButton b = findViewById(id);
        b.setChecked(prefs.getBoolean(pref, false));
        b.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(pref, on).apply());
    }

    /** Pen to screen so far, one row per choice; hidden with no numbers at all. */
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
        for (int h : SaiLocator.screenHeights(shortSide)) {
            RadioButton b = new RadioButton(this);
            b.setText(h == shortSide && h > 720 ? getString(R.string.saipen_sharpness_native, h) : String.valueOf(h));
            b.setId(View.generateViewId());
            group.addView(b);
            if (h == current) b.setChecked(true);
            b.setOnCheckedChangeListener((v, checked) -> {
                if (checked) prefs.edit().putInt(PREF_SCREEN_HEIGHT, h).apply();
            });
        }
    }

    /* ---- finding SAI ---- */

    private void locate() {
        /* The very first time, ask before looking through the device: a
         * search nobody asked for felt creepy (the owner, 2026-11-19). */
        String mode = prefs.getString(PREF_SEARCH, null);
        if (rememberedSaiDir() == null && mode == null) {
            askHowToFind();
            return;
        }
        if (rememberedSaiDir() == null && "manual".equals(mode)) {
            show(null, null, new ArrayList<>());
            return;
        }
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

        progress.setVisibility(View.GONE);
        btnStart.setVisibility(saiDir != null ? View.VISIBLE : View.GONE);
        btnUnzip.setVisibility(zip != null ? View.VISIBLE : View.GONE);
        btnSite.setVisibility(saiDir == null && zip == null ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(saiDir == null && zip == null ? View.VISIBLE : View.GONE);
        btnChoose.setEnabled(true);

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

        /* SAI found the first time: the screen says so and waits for "Start
         * SAI". A licence file found next to it or in Downloads is used
         * right away and named; only without one is there a question. */
        if (saiDir != null && shouldAskAboutLicence()) searchLicence();
        else if (autoLaunch && saiDir != null) launch();
        autoLaunch = false;
    }

    /** First start: search the device for SAI, or pick its folder? */
    private void askHowToFind() {
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.saipen_find_title)
            .setMessage(R.string.saipen_find_text)
            .setPositiveButton(R.string.saipen_find_auto, (d, w) -> {
                prefs.edit().putString(PREF_SEARCH, "auto").apply();
                locate();
            })
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
                                      SaiLocator.installedLicences(saiV2Dir()).get(0).getName()))
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

    /** Looks in Downloads and SAI's folder: a licence found is used and
     *  named; none found, and the screen says how to get one. */
    private void searchLicence() {
        if (container == null) return;
        List<File> roots = licenceRoots();
        worker.execute(() -> {
            File found = SaiLocator.newestLicence(roots, 2);
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
                                                          found.getName(), found.getParent()), Toast.LENGTH_LONG).show();
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
            if (!uris.isEmpty()) readBrushes(uris);
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
            SaiLocator.Found best = SaiLocator.best(SaiLocator.scan(picked, 3));
            List<File> zips = best == null ? SaiLocator.zips(picked, 3) : new ArrayList<>();
            ui.post(() -> {
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

    private void launch() {
        if (container == null || saiDir == null) return;
        SaiLocator.Found f = SaiLocator.best(SaiLocator.scan(saiDir, 0));
        if (f == null) {
            prefs.edit().remove(PREF_SAI_DIR).apply();
            locate();
            return;
        }
        installLicence();
        container.setDrives(SaiLocator.drives(saiDir.getPath(), AppUtils.DIRECTORY_DOWNLOADS, AppUtils.INTERNAL_STORAGE));
        container.setScreenSize(wineScreenSize());
        /* No Microsoft components: SAI draws with GDI and needs none of the
         * DirectX parts, Visual C++ runtimes or Windows Media decoder, and
         * the APK does not carry them (build.sh). Turning them off also puts
         * back Wine's own DLLs in a prefix that had them on. */
        container.setWinComponents(com.winlator.container.Container.FALLBACK_WINCOMPONENTS);
        boolean record = prefs.getBoolean(PREF_TIMELAPSE, false);
        String env = EnvVarsEdit.set(container.getEnvVars(), "WT_TIMELAPSE", record ? "1" : null);
        env = EnvVarsEdit.set(env, "WT_TIMELAPSE_BUDGET_MB", record ? TIMELAPSE_BUDGET_MB : null);
        env = EnvVarsEdit.set(env, "WT_TIMELAPSE_QUIET_MS",
                              record ? String.valueOf(prefs.getInt(PREF_TIMELAPSE_PAUSE, TIMELAPSE_PAUSES_MS[0])) : null);
        env = EnvVarsEdit.set(env, "WT_TIMELAPSE_MAX_WAIT_MS", record ? TIMELAPSE_MAX_WAIT_MS : null);
        container.setEnvVars(env);
        container.saveData();

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", f.exe().getPath());
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
            SaiLocator.autoInstallLicence(SaiLocator.newestLicence(licenceRoots(), 2), saiV2Dir());
        }
        catch (IOException ignored) {
            /* SAI then runs as before, without the licence */
        }
    }

    private void refreshLicence() {
        if (container == null) return;
        List<File> installed = SaiLocator.installedLicences(saiV2Dir());
        if (installed.isEmpty()) {
            tvLicence.setSpoiler(getString(R.string.saipen_licence_none), -1, -1);
        }
        else {
            String name = installed.get(0).getName();
            String line = getString(R.string.saipen_licence_ok, name);
            int[] hidden = SaiLocator.licenceNumberRange(line, name);
            tvLicence.setSpoiler(line, hidden[0], hidden[1]);
        }
        tvLicence.setVisibility(View.VISIBLE);
        tvLicenceHint.setVisibility(installed.isEmpty() ? View.VISIBLE : View.GONE);
        btnLicence.setText(installed.isEmpty() ? R.string.saipen_add_licence : R.string.saipen_change_licence);
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
                tvBrushes.setText(n == null ? getString(R.string.saipen_brushes_unknown)
                    : getString(R.string.saipen_brushes_count, kinds(n[BrushImport.BRUSH_TEXTURES],
                        n[BrushImport.PAPER_TEXTURES], n[3] + n[4] + n[5], n[BrushImport.SCATTER],
                        n[BrushImport.FOLDERS.length], true)));
                tvBrushes.setVisibility(View.VISIBLE);
                tvBrushesWhere.setVisibility(View.VISIBLE);
                btnAddBrushes.setVisibility(View.VISIBLE);
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

    /** The folder in the device's file manager, where one can open it. */
    private Intent folderIntent() {
        Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
            "primary:" + Environment.DIRECTORY_DOCUMENTS + "/" + SaiPenSetup.SETTINGS_PATH);
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

    private void readBrushes(List<Uri> uris) {
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
                source = pickedFiles(uris);
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
        btnChoose.setEnabled(false);
    }

    private void idle(String text) {
        tvStatus.setText(text);
        progress.setVisibility(View.GONE);
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
            .setNeutralButton(R.string.saipen_coffee_button,
                (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(COFFEE_SITE))))
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
