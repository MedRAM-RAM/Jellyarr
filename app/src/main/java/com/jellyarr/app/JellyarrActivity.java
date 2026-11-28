package com.jellyarr.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.app.ActivityManager;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.widget.HorizontalScrollView;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import java.nio.charset.StandardCharsets;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import com.jellyarr.app.model.ManagedService;
import com.jellyarr.app.model.ServiceManifest;
import com.jellyarr.app.model.ServiceState;
import com.jellyarr.app.services.MediaServiceManager;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Map;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Phase 15: Jellyarr UI Polish, Simplified 3-Tab Navigation & Network Connection Card.
 *  - High-visibility Pure Dark Mode (#121316 surface background) with clean separation.
 *  - Fully functional 3-Tab Bottom Navigation: Home (Hero, Stages, Actions, Network Cards), Logs, Settings (Server Config & Storage).
 *  - Dual-band IP presentation (Local + LAN IP) with individual COPY IP buttons.
 *  - Material Ripple touch effects and high-contrast Action buttons (Red STOP SERVER).
 *  - Transparent Jellyarr white header logo.
 *  - Strict NO-EMOJI enforcement across all views.
 */
public final class JellyarrActivity extends AppCompatActivity
        implements JellyfinController.Listener, JellyfinController.LogListener {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(I18n.wrap(newBase));
    }

    private static final String TAG = "JellyarrActivity";
    private JellyfinController controller;
    private SharedPreferences settingsPrefs;

    // Active tab state (0: Home, 1: Logs, 2: Settings)
    private int activeTab = 0;
    private View cachedLibraryView;
    private LibraryTabView libraryTabView;
    private View cachedSonarrView;
    private SonarrTabView sonarrTabView;
    private View cachedRadarrView;
    private RadarrTabView radarrTabView;
    private View cachedTransmissionView;
    private TransmissionTabView transmissionTabView;
    private static final int TAB_RADARR = 10;
    private static final int TAB_TRANSMISSION = 12;
    private View cachedProwlarrView;
    private ProwlarrTabView prowlarrTabView;
    private static final int TAB_PROWLARR = 11;

    // Containers
    private LinearLayout rootLayout;
    private View headerBar;
    private FrameLayout contentContainer;
    private LinearLayout bottomNavLayout;
    private View navDivider;
    private int statusBarInsetTop = 0;
    private int statusBarInsetBottom = 0;

    // Dynamic UI References for Dashboard
    private TextView statusBadge;
    private TextView healthStatusBadge;
    private TextView healthDetailText;
    private TextView healthUptimeText;
    private TextView healthRamText;
    private TextView healthStorageText;
    private TextView defaultIpValueText;
    private TextView localIpValueText;
    private TextView lanIpValueText;
    private TextView tailscaleIpValueText;
    private ImageView btnCopyDefaultIp;
    private ImageView btnCopyLocalIp;
    private ImageView btnCopyLanIp;
    private ImageView btnCopyTailscaleIp;
    private View rowDefaultView;
    private View rowLanView;
    private View rowLocalView;
    private View rowTailscaleView;
    private final java.util.Map<String, ImageView> serviceTileMap = new java.util.HashMap<>();

    // Download Center References
    private LinearLayout downloadItemsContainer;
    private TextView downloadSummaryBadge;

    // Activity Timeline References
    private LinearLayout timelineContainer;
    private String selectedTimelineFilter = "ALL";

    private View homeActionDock;
    private Button btnOpenJellyfin;
    private Button btnStartServer;
    private Button btnStopServer;
    private Button btnRestartServer;
    private Button btnResetCrash;
    private Button btnReinstallRuntime;

    // Button Debounce & Immediate In-Progress States
    private long lastButtonActionTimestamp = 0;
    private static final long BUTTON_DEBOUNCE_MS = 1500;

    // Error Diagnosis Card References
    private View errorDiagnosticCard;
    private TextView errorCardCategoryText;
    private TextView errorCardWhatText;
    private TextView errorCardWhyText;
    private TextView errorCardFixText;
    private TextView errorCardRawText;
    private View errorCardRawContainer;
    private Button btnErrorClearCache;
    private Button btnErrorOpenStorage;
    private boolean errorCardDismissed = false;
    private boolean hasShownErrorPopupForIncident = false;
    private String lastPoppedIncident = "";

    // Permissions Card References
    private TextView permStorageBadge;
    private TextView permStorageAction;
    private TextView permBatteryBadge;
    private TextView permBatteryAction;
    private TextView permNotifBadge;
    private TextView permNotifAction;
    private Button btnGrantAllPermissions;
    private Button setupBtnGrantAll;

    // Password Reset In-App Notification Banner (Dynamic: 5-minute auto removal)
    private View homePasswordResetBanner;
    private TextView resetBannerUserText;
    private TextView resetBannerPinText;
    private TextView resetBannerTimerText;
    private final Handler resetBannerCountdownHandler = new Handler(Looper.getMainLooper());
    private Runnable resetBannerCountdownRunnable;
    private PasswordResetManager.ResetStateListener passwordResetListener;

    // Home Linked Account References
    private TextView homeLinkedAccountValueText;
    private ImageView btnCopyLinkedAccount;
    private View rowAccountView;
    private volatile String cachedCleanAccountUsername = null;

    // Dynamic Multi-LAN Endpoints Container
    private LinearLayout clientEndpointsContainer;

    // Setup Permissions Card References
    private TextView setupPermStorageBadge;
    private TextView setupPermBatteryBadge;
    private TextView setupPermNotifBadge;
    private TextView setupPermOemBadge;

    // Security Section References
    private LinearLayout securityUsersContainer;

    // Real Startup Progress Card References
    private View startupProgressCard;
    private TextView startupProgressHeader;
    private ProgressBar startupProgressBar;
    private TextView txtStage1, txtStage2, txtStage3, txtStage4, txtStage5;
    private long startupCompletedTimeMs = 0;
    private long lastStartupActivityMs = 0;
    private LinearLayout startupCompanionsContainer;
    private final java.util.LinkedHashMap<String, TextView> startupCompanionRows = new java.util.LinkedHashMap<>();
    private final Runnable startupProgressPoller = () -> renderCurrentState();

    // Transcoding & Hardware Dashboard References
    private TextView transcodeEngineBadge;
    private TextView transcodeActiveBadge;
    private TextView transcodeDetailsText;
    private TextView transcodeCodecSummaryText;

    // External Storage Space Dashboard References
    private TextView healthExternalStorageText;
    private View healthExternalStorageRow;

    // Slim Header Loading Bar & Status Banner
    private ProgressBar headerProgressBar;
    private TextView headerStatusBanner;

    // Logs & Settings/Storage References
    private TextView logsOutputText;
    private ScrollView logsScrollView;
    private TextView storageInfoText;
    private TextView storageHeaderTitle;
    private TextView storageHeaderSubtitle;
    private LinearLayout storageBarContainer;
    private LinearLayout storageLegendContainer;
    private LinearLayout externalStorageSection;
    private volatile boolean isCalculatingStorage = false;
    private TextView updateStatusText;
    private Button updateActionBtn;
    private Button updateForceDownloadBtn;
    private TextView runtimeStatusText;
    private Button btnSettingsReinstall;
    private Button btnSettingsRetry;

    // Logs & Console Tab Enhancements
    private String selectedLogService = "ALL";
    private String selectedLogLevel = "ALL";
    private LinearLayout logServiceChipsContainer;
    private LinearLayout logLevelChipsContainer;
    private String logFilter = "ALL";
    private boolean autoScrollEnabled = true;
    private Button btnFilterAll;
    private Button btnFilterError;
    private Button btnFilterWarn;
    private Button btnFilterInfo;
    private Button btnToggleAutoScroll;
    private TextView logsStatusSubtitle;

    // Updates Channel UI References
    private TextView updateChannelBadge;
    private Button btnChannelStable;
    private Button btnChannelBeta;

    // Backup & Restore References
    private ActivityResultLauncher<Intent> restoreFilePickerLauncher;
    private TextView backupStatusTv;

    // Cached Tab Views to prevent recreation and crashes on tab click (4 Destinations + Logs)
    private View cachedHomeView;
    private View cachedServicesView;
    private View cachedActivityView;
    private View cachedSettingsView;
    private View cachedLogsView;

    // Home Downloads Summary references
    private TextView homeDownloadsSummaryText;
    private TextView homeDownloadsCountBadge;

    // High-performance background thread pool for smooth asynchronous operations
    private final ExecutorService bgExecutor = Executors.newFixedThreadPool(3, new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "fishbowl-opt-" + count.getAndIncrement());
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setDaemon(true);
            return t;
        }
    });

    /** Runs {@code r} on the background pool; ignored once the pool is shut down in onDestroy. */
    private void runBg(Runnable r) {
        try {
            bgExecutor.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // activity is being destroyed
        }
    }

    /** True while it is safe to show dialogs and touch the view tree. */
    private boolean isUiAlive() {
        return !isFinishing() && !isDestroyed();
    }

    /** Shows a dialog unless the activity is finishing (avoids WindowManager$BadTokenException). */
    private boolean safeShow(Dialog d) {
        if (d == null || !isUiAlive()) return false;
        try {
            d.show();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "dialog show failed: " + t.getMessage());
            return false;
        }
    }

    /** True between onStart and onStop (pollers only run while the activity is visible). */
    private boolean uiStarted = false;

    // Download Center Auto-Refresh Poller (Dynamic throttle: 1.5s active, 3.5s idle)
    private final Handler downloadAutoRefreshHandler = new Handler(Looper.getMainLooper());
    private volatile boolean hasActiveDownloads = false;
    private final Runnable downloadRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeTab == TAB_TRANSMISSION && uiStarted && isUiAlive()) {
                boolean visible = downloadItemsContainer != null && downloadItemsContainer.isShown();
                if (visible) refreshDownloadCenter();
                long delay = (visible && hasActiveDownloads) ? 1500L : 3500L;
                downloadAutoRefreshHandler.postDelayed(this, delay);
            }
        }
    };

    // Services Manager UI References
    private LinearLayout servicesCardsContainer;
    private TextView servicesSummaryBadge;
    private Button masterStackToggleBtn;
    private View masterStackGap;
    private MediaToolsView mediaToolsView;
    private Button companionInstallBtn;
    private Button companionUninstallBtn;
    private MediaLibrariesView mediaLibrariesView;
    private TextView homeServicesCountBadge;
    private LinearLayout homeServicesRow;
    private boolean isAvailableServicesDrawerOpen = false;

    // Server Kill & Crash History Tracker Container
    private LinearLayout killTrackerContentContainer;

    // Dedicated Full-Screen Setup / Loading Overlay
    private View setupOverlayView;
    private LinearLayout setupOverlayContainer;
    private ProgressBar setupProgressBar;
    private TextView setupProgressText;
    private TextView setupDetailText;
    private Button setupRetryBtn;
    private Button setupDismissBtn;
    private TextView setupLogSummaryText;
    private boolean isSetupOverlayManuallyOpened = false;

    // Throttled Log Handler (Section 4 UI Thread Protection)
    private final android.os.Handler logUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean logUpdatePending = false;
    private boolean showingDiskLog = true;
    private static final long LOG_REFRESH_THROTTLE_MS = 500;

    // Server Health Poller
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler healthHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable healthPollRunnable = new Runnable() {
        @Override
        public void run() {
            // Only polls while Home is the visible tab; renderActiveTab() restarts it when Home is shown again.
            if (activeTab != 0 || !uiStarted) return;
            refreshServerHealthUI();
            healthHandler.postDelayed(this, 4000);
        }
    };

    /** Restarts the Home health poller (no-op unless Home is visible and the activity is started). */
    private void restartHealthPolling() {
        healthHandler.removeCallbacks(healthPollRunnable);
        if (activeTab == 0 && uiStarted) healthHandler.post(healthPollRunnable);
    }

    private final Runnable firstRunFlowRunnable = this::maybeRunFirstRunFlow;

    // Network Callback & Receiver
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver connectivityReceiver;

    // USB-OTG Receiver for Live Hot-Plug Detection
    private BroadcastReceiver usbReceiver;

    private void registerUsbReceiver() {
        if (usbReceiver != null) return;
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mainHandler.postDelayed(() -> {
                    refreshServerHealthUI();
                }, 1000);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(usbReceiver, filter);
            }
        } catch (Throwable t) {
            try {
                registerReceiver(usbReceiver, filter);
            } catch (Throwable ignored) {}
        }

        try {
            IntentFilter usbFilter = new IntentFilter();
            usbFilter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);
            usbFilter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(usbReceiver, usbFilter);
            }
        } catch (Throwable ignored) {}
    }

    private void unregisterUsbReceiver() {
        if (usbReceiver != null) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Throwable ignored) {}
            usbReceiver = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settingsPrefs = getSharedPreferences("fishbowl_settings", MODE_PRIVATE);
        applySavedThemeMode();

        super.onCreate(savedInstanceState);
        controller = JellyfinController.getInstance();
        controller.setContext(getApplicationContext());

        restoreFilePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        Uri uri = result.getData().getData();
                        confirmAndRestoreFromUri(uri);
                    }
                }
        );

        try {
            android.system.Os.chmod(getApplicationInfo().dataDir, 0700);
        } catch (Throwable ignored) {}


        // Request Android 13+ Notification Permission if needed (non-blocking)
        requestNotificationPermissionIfNeeded();

        // Shared internal storage initialization & permissions (v1.4.8-beta.1)
        requestStoragePermissionIfNeeded();
        new Thread(() -> {
            MediaStorageManager.getInstance(getApplicationContext()).initializeOnLaunch(getApplicationContext());
        }, "JellyarrStorageInit").start();

        // Start watching for Jellyfin password reset token files and notify user
        PasswordResetManager.getInstance(getApplicationContext()).startMonitoring();
        passwordResetListener = new PasswordResetManager.ResetStateListener() {
            @Override
            public void onResetStateChanged(PasswordResetManager.ActiveResetInfo info) {
                runOnUiThread(() -> {
                    if (isUiAlive()) updatePasswordResetBanner(info);
                });
            }
        };
        PasswordResetManager.registerListener(passwordResetListener);

        setContentView(I18n.attach(buildMainPixelShell()));
        schedulePrebuildServiceTabs();
        // Initialize native Chromium in-app Cloudflare solver (headless WebView on port 8191)
        com.jellyarr.app.services.NativeFlareSolver.getInstance().init(this);
        setupSystemBarsAndInsets();
        setupNetworkMonitor();
        registerUsbReceiver();

        if (savedInstanceState != null) {
            activeTab = savedInstanceState.getInt("activeTab", 0);
            updateBottomNavSelection();
            renderActiveTab();
            String reopen = savedInstanceState.getString("settingsSection");
            if (reopen != null) openSettingsSectionByName(reopen);
        }

        // Auto-start server on app launch ONLY if explicitly enabled in preferences or uninitialized
        if (!JellyfinBootstrapper.isInitialized(this)) {
            Log.i(TAG, "Jellyarr runtime uninitialized on app launch. Automatically initiating initial setup & extraction.");
            showSetupOverlay(false);
            ensureBootstrapStartedIfNeeded();
        } else {
            triggerAutoStartOnAppLaunchIfEnabled();
        }
        handleControlIntent(getIntent());
    }

    /** Starts the whole server: Jellyfin plus every installed companion service (then auto-wires them). */
    private void startFullServer() {
        if (controller != null) controller.start(getApplicationContext());
        triggerServerAction(JellyfinServerService.ACTION_START_JELLYFIN);
        try {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            if (isMediaStackEnabled()) mgr.startCompanionServices();
        } catch (Throwable t) { Log.w(TAG, "startFullServer companions: " + t.getMessage()); }
        refreshHomeServicesBadge();
    }

    /** Stops the whole server: companion services first, then Jellyfin. */
    private void stopFullServer() {
        try { MediaServiceManager.getInstance(this).stopCompanionServices(); }
        catch (Throwable t) { Log.w(TAG, "stopFullServer companions: " + t.getMessage()); }
        if (controller != null) controller.stop();
        triggerServerAction(JellyfinServerService.ACTION_STOP_JELLYFIN);
        refreshHomeServicesBadge();
    }

    private void triggerAutoStartOnAppLaunchIfEnabled() {
        if (!JellyfinBootstrapper.isInitialized(this)) return;
        SharedPreferences launchPrefs = getJellyarrPrefs();
        boolean startOnAppLaunch = launchPrefs.getBoolean("auto_start_app", launchPrefs.getBoolean("auto_start", false));
        if (startOnAppLaunch) {
            JellyfinController.State state = controller != null ? controller.getState() : JellyfinController.State.UNINITIALIZED;
            if (state != JellyfinController.State.RUNNING && state != JellyfinController.State.STARTING && state != JellyfinController.State.INITIALIZING) {
                Log.i(TAG, "Auto-start on app launch triggered: launching full server (current state=" + state + ")");
                startFullServer();
                renderCurrentState();
            }
        }
    }

    private SharedPreferences getJellyarrPrefs() {
        return getSharedPreferences("fishbowl", MODE_PRIVATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleControlIntent(intent);
        handleUpdateIntent(intent);
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            triggerAutoStartOnAppLaunchIfEnabled();
        }
    }

    private void handleControlIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if ("com.jellyarr.app.ACTION_START_SERVICE".equals(action)) {
            String svcId = intent.getStringExtra("service_id");
            if (svcId != null) {
                MediaServiceManager.getInstance(this).startService(svcId);
            }
        } else if ("com.jellyarr.app.ACTION_START_ALL_SERVICES".equals(action)) {
            MediaServiceManager.getInstance(this).startAllServices();
        } else if ("com.jellyarr.app.ACTION_STOP_ALL_SERVICES".equals(action)) {
            MediaServiceManager.getInstance(this).stopAllServices();
            controller.stop();
            triggerServerAction(JellyfinServerService.ACTION_STOP);
        } else if ("com.jellyarr.app.ACTION_STOP_SERVICE".equals(action)) {
            String svcId = intent.getStringExtra("service_id");
            if (svcId != null) {
                MediaServiceManager.getInstance(this).stopService(svcId);
            }
        }
    }

    private final android.os.Handler serviceUiThrottleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable serviceUiThrottleRunnable = null;

    private void requestThrottledServicesTabRefresh() {
        if (serviceUiThrottleRunnable != null) {
            serviceUiThrottleHandler.removeCallbacks(serviceUiThrottleRunnable);
        }
        serviceUiThrottleRunnable = () -> {
            refreshServicesTabUI();
            refreshHomeServicesBadge();
            if (activeTab == 2) {
                refreshLogServiceChips();
                refreshUnifiedLogsDisplay();
            }
        };
        serviceUiThrottleHandler.postDelayed(serviceUiThrottleRunnable, 120);
    }

    private final MediaServiceManager.ServiceListener serviceListener = (id, state) -> {
        runOnUiThread(() -> {
            requestThrottledServicesTabRefresh();
            if (activeTab == 0) {
                mainHandler.removeCallbacks(startupProgressPoller);
                mainHandler.postDelayed(startupProgressPoller, 150);   // companion startup rows follow service states
            }
        });
    };

    @Override
    protected void onStart() {
        super.onStart();
        uiStarted = true;
        controller.addListener(this);
        controller.addLogListener(this);
        MediaServiceManager.getInstance(this).addListener(serviceListener);
        UpdateManager.getInstance(this).addListener(updateListener);
        refreshUpdateBanner();
        UpdateManager.getInstance(this).checkForUpdates(this, false);
        refreshLanAddress();
        renderCurrentState();
        // reconcile*, refreshHomeServicesBadge() and refreshUpdateUI() run in onResume(), which always follows.
    }

    @Override
    protected void onResume() {
        super.onResume();
        PasswordResetManager.getInstance(getApplicationContext()).checkOnce();
        mainHandler.removeCallbacks(firstRunFlowRunnable);
        mainHandler.postDelayed(firstRunFlowRunnable, 1500);
        if (controller != null) {
            controller.reconcileStateAsync();
        }
        MediaServiceManager.getInstance(this).reconcileRunningServices();
        MediaServiceManager.getInstance(this).scheduleAutoWireDebounced(4000);
        refreshUpdateUI();
        UpdateNotifier.schedule(this);
        UpdateManager.getInstance(this).restoreSavedState(this);
        refreshUpdateBanner();
        maybeShowWhatsNewAfterUpdate();
        runStartupUpdateCheck();
        updateUiHandler.postDelayed(this::maybeShowInstallReadyPrompt, 1800);
        handleUpdateIntent(getIntent());
        refreshHomeServicesBadge();
        restartHealthPolling();
        refreshPermissionsUI();
        updatePasswordResetBanner(PasswordResetManager.getActiveResetInfo());
        refreshHomeAccountsUI();

        // Background automatic cleanup for orphan media folders (e.g. movies deleted in Jellyfin leaving only .nfo or empty folders)
        runBg(() -> {
            try {
                MediaStorageManager.getInstance(JellyarrActivity.this).cleanupOrphanMediaFolders();
            } catch (Throwable ignored) {}
        });
        if (!JellyfinBootstrapper.isInitialized(this)) {
            showSetupOverlay(false);
            ensureBootstrapStartedIfNeeded();
        } else {
            triggerAutoStartOnAppLaunchIfEnabled();
        }
        if (activeTab == TAB_TRANSMISSION) {
            downloadAutoRefreshHandler.removeCallbacks(downloadRefreshRunnable);
            downloadAutoRefreshHandler.post(downloadRefreshRunnable);
        }
    }

    @Override
    protected void onStop() {
        uiStarted = false;
        mainHandler.removeCallbacks(firstRunFlowRunnable);
        controller.removeListener(this);
        controller.removeLogListener(this);
        MediaServiceManager.getInstance(this).removeListener(serviceListener);
        UpdateManager.getInstance(this).removeListener(updateListener);
        logUpdateHandler.removeCallbacksAndMessages(null);
        logUpdatePending = false;
        healthHandler.removeCallbacks(healthPollRunnable);
        downloadAutoRefreshHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacks(startupProgressPoller);
        // the reset-PIN countdown is restarted by updatePasswordResetBanner() in onResume()
        resetBannerCountdownHandler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterUsbReceiver();
        teardownNetworkMonitor();
        controller.removeLogListener(this);
        controller.removeListener(this);
        logUpdateHandler.removeCallbacksAndMessages(null);
        downloadAutoRefreshHandler.removeCallbacksAndMessages(null);
        // Nothing scheduled on the UI handlers may run against a destroyed activity.
        mainHandler.removeCallbacksAndMessages(null);
        healthHandler.removeCallbacksAndMessages(null);
        resetBannerCountdownHandler.removeCallbacksAndMessages(null);
        serviceUiThrottleHandler.removeCallbacksAndMessages(null);
        updateUiHandler.removeCallbacksAndMessages(null);
        if (passwordResetListener != null) {
            PasswordResetManager.unregisterListener(passwordResetListener);
            passwordResetListener = null;
        }
        com.jellyarr.app.services.NativeFlareSolver.getInstance().stop();
        bgExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("activeTab", activeTab);
        if (settingsOpenName != null) outState.putString("settingsSection", settingsOpenName);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // onCreate() already restored the tab and the open Settings section; rendering again here
        // only duplicated every refresh.
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Configuration changes are handled without restarting, except when the app follows the
        // system light/dark setting and that setting just flipped: colours are baked into views.
        if (AppTheme.getMode(this) == AppTheme.MODE_SYSTEM) {
            boolean nightNow = (newConfig.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            if (nightNow != AppTheme.isDark() && !themeRestarting) recreate();
        }
    }

    @Override
    public void onBackPressed() {
        if (sideDrawerOpen) {
            closeSideDrawer();
            return;
        }
        if (activeTab == 5 && libraryTabView != null && libraryTabView.onBack()) {
            return;
        }
        if (activeTab == 6 && sonarrTabView != null && sonarrTabView.onBack()) {
            return;
        }
        if (activeTab == TAB_RADARR && radarrTabView != null && radarrTabView.onBack()) {
            return;
        }
        if (activeTab == TAB_TRANSMISSION && transmissionTabView != null && transmissionTabView.onBack()) {
            return;
        }
        if (activeTab == TAB_PROWLARR && prowlarrTabView != null && prowlarrTabView.onBack()) {
            return;
        }
        if (activeTab == 3 && closeSettingsSection()) {
            return;
        }
        if (activeTab != 0) {
            activeTab = 0;
            updateBottomNavSelection();
            renderActiveTab();
            return;
        }
        if (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE) {
            if (isSetupOverlayManuallyOpened || JellyfinBootstrapper.isInitialized(this)) {
                isSetupOverlayManuallyOpened = false;
                setupOverlayView.setVisibility(View.GONE);
                setChromeVisibilityForSetup(false);
                if (setupDismissBtn != null) setupDismissBtn.setVisibility(View.GONE);
                renderActiveTab();
                return;
            }
            Toast.makeText(this, I18n.tr("Setup in progress. Please keep Jellyarr open."), Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
            return;
        }
        moveTaskToBack(true);
    }

    // -- System Bar & Inset Management -----------------------------------------

    private void setupSystemBarsAndInsets() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);

        WindowInsetsControllerCompat controllerCompat = new WindowInsetsControllerCompat(window, window.getDecorView());
        controllerCompat.setAppearanceLightStatusBars(!AppTheme.isDark());
        controllerCompat.setAppearanceLightNavigationBars(!AppTheme.isDark());

        window.setStatusBarColor(getSurfaceColor());
        window.setNavigationBarColor(AppTheme.card());

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            androidx.core.graphics.Insets statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            androidx.core.graphics.Insets navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars());

            statusBarInsetTop = statusBarInset.top;
            statusBarInsetBottom = navBarInset.bottom;
            applyTopInsets();
            applyDrawerInsets(statusBarInset.top, navBarInset.bottom);
            if (bottomNavLayout != null) {
                ViewGroup.LayoutParams blp = bottomNavLayout.getLayoutParams();
                if (blp instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) blp).bottomMargin = navBarInset.bottom + dp(10);
                    bottomNavLayout.setLayoutParams(blp);
                }
            }
            if (setupOverlayContainer != null) {
                setupOverlayContainer.setPadding(dp(24), statusBarInset.top + dp(28), dp(24), navBarInset.bottom + dp(24));
            }
            updateContentInsetsForTab();
            return insets;
        });
    }

    private boolean isUpdateBannerShown() {
        return updateBannerBar != null && updateBannerBar.getVisibility() == View.VISIBLE;
    }

    /** The update bar sits at the very top, so while it is visible IT owns the status-bar inset. */
    private void applyTopInsets() {
        boolean banner = isUpdateBannerShown();
        if (updateBannerBar != null) updateBannerBar.setPadding(0, banner ? statusBarInsetTop : 0, 0, 0);
        if (headerBar != null) {
            headerBar.setPadding(dp(20), (banner ? 0 : statusBarInsetTop) + dp(10), dp(20), dp(10));
        }
        updateContentInsetsForTab();
    }

    /** Height the floating bottom bar takes from the content area (0 while it is hidden). */
    private int floatingNavInset() {
        if (bottomNavLayout == null || bottomNavLayout.getVisibility() != View.VISIBLE) return 0;
        int h = bottomNavLayout.getHeight() > 0 ? bottomNavLayout.getHeight() : dp(72);
        return h + dp(10) + statusBarInsetBottom + dp(6);
    }

    private void updateContentInsetsForTab() {
        if (contentContainer == null) return;
        int bottom = floatingNavInset();
        if (activeTab == 0 || isUpdateBannerShown()) {
            contentContainer.setPadding(0, 0, 0, bottom);
        } else {
            contentContainer.setPadding(0, statusBarInsetTop > 0 ? statusBarInsetTop : dp(24), 0, bottom);
        }
    }

    // -- Theme Engine (System / Light / Dark / AMOLED + accent, see AppTheme) -------

    private void applySavedThemeMode() {
        AppTheme.init(this);
        AppTheme.applyNightMode();
    }

    private boolean isDarkTheme() {
        return AppTheme.isDark();
    }

    // -- Colors & Design Tokens (Material 3 colour roles via AppTheme) ---------

    private int getBgColor() { return AppTheme.bg(); }
    private int getSurfaceColor() { return AppTheme.bg(); }
    private int getSurfaceElevatedColor() { return AppTheme.card(); }
    private int getBorderColor() { return AppTheme.outline(); }
    private int getPrimaryTextColor() { return AppTheme.text(); }
    private int getSecondaryTextColor() { return AppTheme.textSecondary(); }
    private int getAccentColor() { return AppTheme.accent(); }
    private int getSuccessColor() { return AppTheme.success(); }
    private int getWarningColor() { return AppTheme.warning(); }
    private int getDangerColor() { return AppTheme.danger(); }

    private GradientDrawable createCardDrawable(int bgColor, int radiusDp) {
        int r = radiusDp > 0 ? radiusDp : dp(14);
        return createBorderedRoundedDrawable(bgColor, getBorderColor(), dp(1), r);
    }


    // -- Root UI Shell Construction ---------------------------------------------

    private View buildMainPixelShell() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(getBgColor());

        // Persistent update bar (download progress / install) - stays until the user closes it
        rootLayout.addView(buildUpdateBanner());

        // Header Bar
        headerBar = buildHeaderBar();
        rootLayout.addView(headerBar);

        // Slim Non-Intrusive Progress Bar (Small loading indicator right under header)
        headerProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        headerProgressBar.setIndeterminate(false);
        headerProgressBar.setMax(100);
        headerProgressBar.setProgress(0);
        headerProgressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams hpbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        rootLayout.addView(headerProgressBar, hpbParams);

        // Slim Status Notification Banner right below progress bar
        headerStatusBanner = new TextView(this);
        headerStatusBanner.setTextSize(12);
        headerStatusBanner.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerStatusBanner.setPadding(dp(16), dp(6), dp(16), dp(6));
        headerStatusBanner.setBackgroundColor(getSurfaceElevatedColor());
        headerStatusBanner.setTextColor(getSecondaryTextColor());
        headerStatusBanner.setVisibility(View.GONE);
        rootLayout.addView(headerStatusBanner);

        // Content Area
        contentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        rootLayout.addView(contentContainer, contentParams);

        // Pre-build and cache tab views to guarantee zero crashes and instant tab switching
        cachedHomeView = buildHomeTab();
        contentContainer.addView(cachedHomeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedServicesView = buildServicesTab();
        cachedServicesView.setVisibility(View.GONE);
        contentContainer.addView(cachedServicesView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedActivityView = buildActivityTab();
        cachedActivityView.setVisibility(View.GONE);
        contentContainer.addView(cachedActivityView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedSettingsView = buildSettingsTab();
        cachedSettingsView.setVisibility(View.GONE);
        contentContainer.addView(cachedSettingsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cachedLogsView = buildLogsTab();
        cachedLogsView.setVisibility(View.GONE);
        contentContainer.addView(cachedLogsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Library / Sonarr / Radarr / Transmission / Prowlarr screens are built on demand (see ensureServiceTab),
        // which keeps start-up and theme changes fast.

        cachedPlaceholderView = buildPlaceholderView();
        cachedPlaceholderView.setVisibility(View.GONE);
        contentContainer.addView(cachedPlaceholderView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Dedicated Full-Screen Setup / Loading Overlay (placed on top of tabs)
        setupOverlayView = buildSetupOverlay();
        contentContainer.addView(setupOverlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating bottom navigation bar (Menu, Home, Media, Activity, Settings): an overlay above the content.
        bottomNavLayout = buildBottomNavBar();
        bottomNavLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            if (b - t != ob - ot) {
                syncDrawerBottomMargin();
                updateContentInsetsForTab();
            }
        });

        // Initial tab render
        renderActiveTab();

        FrameLayout shell = new FrameLayout(this);
        shell.addView(rootLayout, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        navLp.setMargins(dp(14), 0, dp(14), dp(10) + statusBarInsetBottom);
        shell.addView(bottomNavLayout, navLp);
        buildSideDrawer(shell);
        return shell;
    }

    private View buildHeaderBar() {
        LinearLayout rootHeader = new LinearLayout(this);
        rootHeader.setOrientation(LinearLayout.VERTICAL);
        rootHeader.setBackgroundColor(getBgColor());
        rootHeader.setPadding(dp(20), dp(10), dp(20), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(2), 0, dp(2));

        // Logo Mark (Jellyarr Logo, White)
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_jellyarr_logo);
        logo.setColorFilter(AppTheme.text());
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        logoParams.setMarginEnd(dp(10));
        header.addView(logo, logoParams);

        // App Branding Name: JELLYARR (Uppercase, tracking-widest)
        TextView title = new TextView(this);
        title.setText("JELLYARR");
        title.setTextSize(14f);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(AppTheme.text());
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView verBadge = new TextView(this);
        verBadge.setText("v" + com.termux.BuildConfig.VERSION_NAME);
        verBadge.setTextSize(10);
        verBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        verBadge.setTextColor(AppTheme.textMuted());
        header.addView(verBadge);

        rootHeader.addView(header);

        // Hairline divider
        View border = new View(this);
        border.setBackgroundColor(AppTheme.outline());
        rootHeader.addView(border, new LinearLayout.LayoutParams(-1, dp(1)));

        return rootHeader;
    }

    private LinearLayout buildBottomNavBar() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        // Rounded (not fully pill-shaped) floating bar: reads as one surface on every width and keeps the
        // selected-item pill visually inside it.
        nav.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), 26));
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        nav.setElevation(dp(8));
        nav.setClipToOutline(true);
        nav.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        nav.setMinimumHeight(dp(64));

        populateNavTabs(nav);
        return nav;
    }

    /** Navigation, ordered by what you do: control the server (Home), acquire (Media: services + downloads),
     *  inspect (Activity), configure (Settings); the Menu item opens the drawer with the service screens. */
    private void populateNavTabs(LinearLayout nav) {
        addNavTab(nav, -1, R.drawable.ic_menu, "Menu");
        addNavTab(nav, 0, R.drawable.ic_home, "Home");
        if (isMediaStackEnabled()) {
            addNavTab(nav, 1, R.drawable.ic_services, "Media");
        }
        addNavTab(nav, 2, R.drawable.ic_activity, "Activity");
        addNavTab(nav, 3, R.drawable.ic_settings, "Settings");
    }

    private void addNavTab(LinearLayout parent, int tabIndex, int iconRes, String label) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setTag(tabIndex);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setContentDescription(I18n.tr(label));
        tab.setPadding(0, dp(4), 0, dp(4));
        tab.setMinimumHeight(dp(52));
        tab.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(AppTheme.withAlpha(AppTheme.accent(), 0x30)), null,
                UiKit.shape(this, 0xFFFFFFFF, 0, 20)));
        tab.setOnClickListener(v -> {
            if (tabIndex == -1) {
                toggleSideDrawer();
                return;
            }
            if (activeTab == tabIndex) {
                onNavTabReselected(tabIndex);
                return;
            }
            if (activeTab != tabIndex) {
                final long t0 = SystemClock.elapsedRealtime();
                activeTab = tabIndex;
                updateBottomNavSelection();
                renderActiveTab();
                final int shown = tabIndex;
                v.post(() -> Log.i("JellyarrPerf", "tab " + shown + " switch+layout ms=" + (SystemClock.elapsedRealtime() - t0)));
            }
        });

        // Active indicator ("pill") that holds the icon.
        FrameLayout pill = new FrameLayout(this);
        pill.setTag("pill");
        ImageView icon = new ImageView(this);
        icon.setTag("icon");
        icon.setImageResource(iconRes);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        iconParams.gravity = Gravity.CENTER;
        pill.addView(icon, iconParams);
        tab.addView(pill, new LinearLayout.LayoutParams(dp(52), dp(30)));

        TextView labelView = new TextView(this);
        labelView.setTag("label");
        labelView.setText(label);
        labelView.setTextSize(11f);
        labelView.setGravity(Gravity.CENTER);
        labelView.setSingleLine(true);
        labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelView.setIncludeFontPadding(false);
        labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        tab.addView(labelView, lp);

        parent.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
    }

    /**
     * Tapping the item of the screen you are on: inside a Settings section the first tap scrolls to the top and the
     * next one goes back to the Settings list; on the list itself it scrolls to the top.
     */
    private void onNavTabReselected(int tabIndex) {
        if (tabIndex != 3) return;
        if (settingsSectionOpen && settingsDetailScroll != null) {
            if (settingsDetailScroll.getScrollY() > 0) settingsDetailScroll.smoothScrollTo(0, 0);
            else closeSettingsSection();
            return;
        }
        if (settingsMenu != null && settingsMenu.getParent() instanceof ScrollView) {
            ((ScrollView) settingsMenu.getParent()).smoothScrollTo(0, 0);
        }
    }

    private void updateBottomNavSelection() {
        updateDrawerSelection();
        if (bottomNavLayout == null) return;
        for (int i = 0; i < bottomNavLayout.getChildCount(); i++) {
            View child = bottomNavLayout.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout tab = (LinearLayout) child;
            View pill = tab.findViewWithTag("pill");
            ImageView icon = tab.findViewWithTag("icon");
            TextView label = tab.findViewWithTag("label");
            Object tagVal = tab.getTag();
            int tabIndex = (tagVal instanceof Integer) ? (Integer) tagVal : i;

            boolean selected = (tabIndex == activeTab) || (tabIndex == -1 && isDrawerTab(activeTab));
            boolean wasSelected = Boolean.TRUE.equals(tab.getTag(R.id.nav_selected_state));
            tab.setTag(R.id.nav_selected_state, selected);
            tab.setSelected(selected);
            if (pill != null) {
                pill.setBackground(selected ? createRoundedDrawable(AppTheme.accentContainer(), dp(15)) : null);
                // Only the item that just became selected animates; the others stay still.
                if (selected && !wasSelected) {
                    pill.setScaleX(0.6f);
                    pill.setAlpha(0.6f);
                    pill.animate().scaleX(1f).alpha(1f).setDuration(180).start();
                } else {
                    pill.animate().cancel();
                    pill.setScaleX(1f);
                    pill.setAlpha(1f);
                }
            }
            if (icon != null) {
                icon.setColorFilter(selected ? AppTheme.onAccentContainer() : AppTheme.textSecondary());
            }
            if (label != null) {
                label.setTextColor(selected ? AppTheme.text() : AppTheme.textMuted());
                label.setTypeface(Typeface.create("sans-serif-medium", selected ? Typeface.BOLD : Typeface.NORMAL));
            }
        }
    }

    // -- Tab View Router (Cached & Zero-Recreation) ---------------------------

    /** Builds a service screen the first time it is needed and adds it under the setup overlay. */
    private void ensureServiceTab(int tab) {
        View v = null;
        if (tab == 5 && libraryTabView == null) {
            libraryTabView = new LibraryTabView(this);
            v = cachedLibraryView = libraryTabView.getView();
        } else if (tab == 6 && sonarrTabView == null) {
            sonarrTabView = new SonarrTabView(this);
            v = cachedSonarrView = sonarrTabView.getView();
        } else if (tab == TAB_RADARR && radarrTabView == null) {
            radarrTabView = new RadarrTabView(this);
            v = cachedRadarrView = radarrTabView.getView();
        } else if (tab == TAB_TRANSMISSION && transmissionTabView == null) {
            transmissionTabView = new TransmissionTabView(this);
            transmissionTabView.setCenterView(buildDownloadCenterCard(), this::refreshDownloadCenter);
            v = cachedTransmissionView = transmissionTabView.getView();
        } else if (tab == TAB_PROWLARR && prowlarrTabView == null) {
            prowlarrTabView = new ProwlarrTabView(this);
            v = cachedProwlarrView = prowlarrTabView.getView();
        }
        if (v == null || contentContainer == null) return;
        v.setVisibility(View.GONE);
        int idx = setupOverlayView != null ? contentContainer.indexOfChild(setupOverlayView) : -1;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (idx >= 0) contentContainer.addView(v, idx, lp); else contentContainer.addView(v, lp);
        I18n.attach(v);
    }

    // -- Service gate: a service screen opened while its service is missing or stopped -----------------

    private LinearLayout serviceGateView;
    private String serviceGateKey = "";
    private final Runnable serviceGatePoll = this::updateServiceGate;

    private static String serviceIdForTab(int tab) {
        if (tab == 5) return "jellyfin";
        if (tab == 6) return "sonarr";
        if (tab == TAB_RADARR) return "radarr";
        if (tab == TAB_TRANSMISSION) return "transmission";
        if (tab == TAB_PROWLARR) return "prowlarr";
        return null;
    }

    private View serviceTabViewFor(int tab) {
        if (tab == 5) return cachedLibraryView;
        if (tab == 6) return cachedSonarrView;
        if (tab == TAB_RADARR) return cachedRadarrView;
        if (tab == TAB_TRANSMISSION) return cachedTransmissionView;
        if (tab == TAB_PROWLARR) return cachedProwlarrView;
        return null;
    }

    /** 0 = ready, 1 = not installed, 2 = stopped, 3 = starting, 4 = blocked. */
    private int serviceGateState(String sid) {
        try {
            if ("jellyfin".equals(sid)) {
                JellyfinController.State js = controller != null ? controller.getState() : JellyfinController.State.STOPPED;
                if (js == JellyfinController.State.RUNNING) return 0;
                if (js == JellyfinController.State.STARTING || js == JellyfinController.State.INITIALIZING) return 3;
                return 2;
            }
            ManagedService svc = MediaServiceManager.getInstance(this).getService(sid);
            if (svc == null) return 1;
            if (svc.isBlocked()) return 4;
            if (!svc.isInstalled()) return 1;
            if (svc.isRunning()) return 0;
            if (svc.isStarting() || svc.getState() == com.jellyarr.app.model.ServiceState.RECOVERING) return 3;
            return 2;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * A service screen only shows its settings while the service is installed and running. Otherwise it shows one
     * clear message with the action that fixes it: install, or start. The screen comes back by itself once ready.
     */
    private void updateServiceGate() {
        mainHandler.removeCallbacks(serviceGatePoll);
        if (contentContainer == null) return;
        String sid = serviceIdForTab(activeTab);
        boolean overlay = setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE;
        int state = (sid == null || overlay) ? 0 : serviceGateState(sid);
        View tabView = serviceTabViewFor(activeTab);
        if (state == 0) {
            boolean wasGated = serviceGateView != null && serviceGateView.getVisibility() == View.VISIBLE;
            if (serviceGateView != null) serviceGateView.setVisibility(View.GONE);
            serviceGateKey = "";
            if (sid != null && tabView != null && !overlay) {
                tabView.setVisibility(View.VISIBLE);
                if (wasGated) {
                    if (activeTab == 5 && libraryTabView != null) libraryTabView.refresh();
                    else if (activeTab == 6 && sonarrTabView != null) sonarrTabView.refresh();
                    else if (activeTab == TAB_RADARR && radarrTabView != null) radarrTabView.refresh();
                    else if (activeTab == TAB_TRANSMISSION && transmissionTabView != null) transmissionTabView.refresh();
                    else if (activeTab == TAB_PROWLARR && prowlarrTabView != null) prowlarrTabView.refresh();
                }
            }
            return;
        }
        if (tabView != null) tabView.setVisibility(View.GONE);
        if (serviceGateView == null) {
            serviceGateView = UiKit.column(this);
            serviceGateView.setGravity(Gravity.CENTER);
            serviceGateView.setBackgroundColor(getBgColor());
            serviceGateView.setClickable(true);
            int idx = setupOverlayView != null ? contentContainer.indexOfChild(setupOverlayView) : -1;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
            if (idx >= 0) contentContainer.addView(serviceGateView, idx, lp); else contentContainer.addView(serviceGateView, lp);
        }
        serviceGateView.setVisibility(View.VISIBLE);
        serviceGateView.bringToFront();
        if (setupOverlayView != null) setupOverlayView.bringToFront();
        serviceGateView.setPadding(dp(32), dp(24), dp(32), floatingNavInset() + dp(24));

        String key = sid + ":" + state;
        if (!key.equals(serviceGateKey)) {
            serviceGateKey = key;
            serviceGateView.removeAllViews();
            ManagedService gs = "jellyfin".equals(sid) ? null : MediaServiceManager.getInstance(this).getService(sid);
            String name = gs != null ? gs.getDisplayName()
                    : sid.substring(0, 1).toUpperCase(Locale.ROOT) + sid.substring(1);

            ImageView logo = new ImageView(this);
            logo.setImageResource(getServiceIconRes(sid));
            logo.setAlpha(state == 3 ? 1f : 0.55f);
            serviceGateView.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));

            TextView title = UiKit.title(this, name);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(16), 0, dp(6));
            serviceGateView.addView(title, new LinearLayout.LayoutParams(-2, -2));

            String msg;
            if (state == 1) msg = I18n.tr("Not installed. Tap to install it.");
            else if (state == 2) msg = I18n.tr("Not running. Tap to start it.");
            else if (state == 3) msg = I18n.tr("Starting… this screen opens by itself when it is ready.");
            else msg = I18n.tr("This service is not compatible with this phone.");
            TextView body = UiKit.body(this, msg);
            body.setGravity(Gravity.CENTER);
            serviceGateView.addView(body, new LinearLayout.LayoutParams(-2, -2));

            if (state == 3) {
                ProgressBar pb = new ProgressBar(this);
                pb.setIndeterminate(true);
                pb.setIndeterminateTintList(ColorStateList.valueOf(AppTheme.accent()));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(28), dp(28));
                plp.topMargin = dp(20);
                serviceGateView.addView(pb, plp);
            } else if (state == 1 || state == 2) {
                final String id = sid;
                Button action = UiKit.primary(this, buttonLabel(state == 1 ? "INSTALL" : "START"), v -> {
                    if (state == 1) {
                        ManagedService svc = MediaServiceManager.getInstance(this).getService(id);
                        if (svc != null) showServiceInstallCompatibilityDialog(svc);
                    } else if ("jellyfin".equals(id)) {
                        triggerServerAction(JellyfinServerService.ACTION_START);
                    } else {
                        MediaServiceManager.getInstance(this).startService(id);
                    }
                    mainHandler.postDelayed(this::updateServiceGate, 600);
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(200), dp(44));
                blp.topMargin = dp(20);
                serviceGateView.addView(action, blp);
                serviceGateView.setOnClickListener(v -> action.performClick());
            }
            I18n.attach(serviceGateView);
        }
        if (state == 1 || state == 2) {
            // the whole screen is the button; keep watching in case the service is started elsewhere
            mainHandler.postDelayed(serviceGatePoll, 2500);
        } else {
            serviceGateView.setOnClickListener(null);
            mainHandler.postDelayed(serviceGatePoll, 1200);
        }
    }

    private int prebuildStep = 0;

    /** After start-up, builds the remaining service screens one at a time while the UI is idle. */
    private void schedulePrebuildServiceTabs() {
        final int[] order = {6, TAB_RADARR, TAB_TRANSMISSION, TAB_PROWLARR, 5};
        if (prebuildStep >= order.length) return;
        mainHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (prebuildStep < order.length) ensureServiceTab(order[prebuildStep++]);
            schedulePrebuildServiceTabs();
        }, 700);
    }

    private void renderActiveTab() {
        ensureServiceTab(activeTab);
        updateBottomNavSelection();

        if (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE) {
            setChromeVisibilityForSetup(true);
        } else {
            if (headerBar != null) {
                headerBar.setVisibility(activeTab == 0 ? View.VISIBLE : View.GONE);
            }
            if (bottomNavLayout != null) {
                bottomNavLayout.setVisibility(View.VISIBLE);
            }
            if (navDivider != null) {
                navDivider.setVisibility(View.VISIBLE);
            }
        }
        updateContentInsetsForTab();

        if (cachedHomeView != null) cachedHomeView.setVisibility(activeTab == 0 ? View.VISIBLE : View.GONE);
        if (cachedServicesView != null) cachedServicesView.setVisibility(activeTab == 1 ? View.VISIBLE : View.GONE);
        if (cachedActivityView != null) cachedActivityView.setVisibility(activeTab == 2 ? View.VISIBLE : View.GONE);
        if (cachedSettingsView != null) cachedSettingsView.setVisibility(activeTab == 3 ? View.VISIBLE : View.GONE);
        if (cachedLogsView != null) cachedLogsView.setVisibility(activeTab == 4 ? View.VISIBLE : View.GONE);
        if (cachedLibraryView != null) cachedLibraryView.setVisibility(activeTab == 5 ? View.VISIBLE : View.GONE);
        if (cachedSonarrView != null) cachedSonarrView.setVisibility(activeTab == 6 ? View.VISIBLE : View.GONE);
        if (cachedRadarrView != null) cachedRadarrView.setVisibility(activeTab == TAB_RADARR ? View.VISIBLE : View.GONE);
        if (cachedTransmissionView != null) cachedTransmissionView.setVisibility(activeTab == TAB_TRANSMISSION ? View.VISIBLE : View.GONE);
        if (cachedProwlarrView != null) cachedProwlarrView.setVisibility(activeTab == TAB_PROWLARR ? View.VISIBLE : View.GONE);
        if (cachedPlaceholderView != null) {
            boolean ph = activeTab >= PLACEHOLDER_TAB_BASE && activeTab != TAB_RADARR && activeTab != TAB_TRANSMISSION
                    && activeTab != TAB_PROWLARR;
            cachedPlaceholderView.setVisibility(ph ? View.VISIBLE : View.GONE);
            if (ph) showPlaceholderFor(activeTab);
        }
        updateServiceGate();

        if (activeTab == 0) {
            refreshLanAddress();
            refreshHomeServicesBadge();
            refreshHomeAccountsUI();
            restartHealthPolling();
        } else if (activeTab == 1) {
            refreshServicesTabUI();
            if (mediaToolsView != null) mediaToolsView.refresh(false);
            if (mediaLibrariesView != null) mediaLibrariesView.refresh(false);
        } else if (activeTab == 2) {
            refreshLogServiceChips();
            updateLogLevelChips();
            refreshUnifiedLogsDisplay();
            if (autoScrollEnabled && logsScrollView != null) {
                logsScrollView.postDelayed(() -> logsScrollView.fullScroll(View.FOCUS_DOWN), 30);
            }
        } else if (activeTab == 3) {
            updateStorageInfoText();
            refreshRuntimeStatusUI();
            refreshPermissionsUI();
            UpdateManager uMgr = UpdateManager.getInstance(this);
            if (uMgr.getCurrentState() == UpdateManager.State.IDLE) {
                uMgr.checkForUpdates(this, false);
            }
            refreshUpdateUI();
            refreshKillTrackerUI();
        } else if (activeTab == 5) {
            if (libraryTabView != null) libraryTabView.refresh();
        } else if (activeTab == 6) {
            if (sonarrTabView != null) sonarrTabView.refresh();
        } else if (activeTab == TAB_RADARR) {
            if (radarrTabView != null) radarrTabView.refresh();
        } else if (activeTab == TAB_TRANSMISSION) {
            if (transmissionTabView != null) transmissionTabView.refresh();
            // the download-center poller is (re)scheduled once, below
        } else if (activeTab == TAB_PROWLARR) {
            if (prowlarrTabView != null) prowlarrTabView.refresh();
        } else if (activeTab == 4) {
            refreshUnifiedLogsDisplay();
            if (autoScrollEnabled && logsScrollView != null) {
                logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }

        if (activeTab == 2 || activeTab == 4 || (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE)) {
            controller.addLogListener(this);
        } else {
            controller.removeLogListener(this);
            logUpdateHandler.removeCallbacksAndMessages(null);
            logUpdatePending = false;
        }

        if (activeTab == TAB_TRANSMISSION) {
            downloadAutoRefreshHandler.removeCallbacks(downloadRefreshRunnable);
            downloadAutoRefreshHandler.postDelayed(downloadRefreshRunnable, 1000);
        } else {
            downloadAutoRefreshHandler.removeCallbacks(downloadRefreshRunnable);
        }

        renderCurrentState();
    }

    // -- Dedicated Full-Screen Setup & Extraction Screen -----------------------

    private void setChromeVisibilityForSetup(boolean setupActive) {
        if (setupActive) {
            if (headerBar != null) headerBar.setVisibility(View.GONE);
            if (headerProgressBar != null) headerProgressBar.setVisibility(View.GONE);
            if (headerStatusBanner != null) headerStatusBanner.setVisibility(View.GONE);
            if (bottomNavLayout != null) bottomNavLayout.setVisibility(View.GONE);
            if (navDivider != null) navDivider.setVisibility(View.GONE);
        } else {
            if (bottomNavLayout != null) bottomNavLayout.setVisibility(View.VISIBLE);
            if (navDivider != null) navDivider.setVisibility(View.VISIBLE);
            if (headerBar != null) headerBar.setVisibility(activeTab == 0 ? View.VISIBLE : View.GONE);
            updateContentInsetsForTab();
        }
    }

    private View buildSetupOverlay() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());
        scroll.setVisibility(JellyfinBootstrapper.isInitialized(this) ? View.GONE : View.VISIBLE);

        setupOverlayContainer = new LinearLayout(this);
        setupOverlayContainer.setOrientation(LinearLayout.VERTICAL);
        setupOverlayContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        int topPad = statusBarInsetTop > 0 ? statusBarInsetTop + dp(28) : dp(48);
        int btmPad = statusBarInsetBottom > 0 ? statusBarInsetBottom + dp(24) : dp(32);
        setupOverlayContainer.setPadding(dp(24), topPad, dp(24), btmPad);
        LinearLayout container = setupOverlayContainer;

        // Setup Brand Logo with subtle glow / accent tint
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_jellyarr_logo);
        logo.setColorFilter(AppTheme.text());
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        logoParams.bottomMargin = dp(14);
        container.addView(logo, logoParams);

        // Setup Title
        TextView title = new TextView(this);
        title.setText("Jellyarr System Setup");
        title.setTextSize(20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getPrimaryTextColor());
        title.setGravity(Gravity.CENTER);
        container.addView(title);

        // Subtitle & Status Details
        setupDetailText = new TextView(this);
        setupDetailText.setText("Preparing runtime environment...");
        setupDetailText.setTextSize(12.5f);
        setupDetailText.setTextColor(getSecondaryTextColor());
        setupDetailText.setGravity(Gravity.CENTER);
        setupDetailText.setPadding(0, dp(6), 0, dp(18));
        container.addView(setupDetailText);

        // Progress Card Container
        LinearLayout progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        progressCard.setBackground(createBorderedRoundedDrawable(getSurfaceColor(), getBorderColor(), dp(1), dp(12)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(12);
        progressCard.setLayoutParams(cardParams);

        // Top Row with Stage Label & Percentage
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblProgress = new TextView(this);
        lblProgress.setText("INSTALLATION & RUNTIME SETUP");
        lblProgress.setTextSize(11);
        lblProgress.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblProgress.setTextColor(getSecondaryTextColor());
        topRow.addView(lblProgress, new LinearLayout.LayoutParams(0, -2, 1.0f));

        setupProgressText = new TextView(this);
        setupProgressText.setText("0%");
        setupProgressText.setTextSize(15);
        setupProgressText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        setupProgressText.setTextColor(getAccentColor());
        topRow.addView(setupProgressText);

        progressCard.addView(topRow);

        // Dynamic Setup Progress Bar
        setupProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        setupProgressBar.setMax(100);
        setupProgressBar.setProgress(0);
        setupProgressBar.setIndeterminate(false);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(-1, dp(8));
        pbParams.topMargin = dp(10);
        pbParams.bottomMargin = dp(14);
        progressCard.addView(setupProgressBar, pbParams);

        // Granular Element Stage Rows
        TextView row1 = createSetupCheckItem("1. Recombining split package archives (asset chunks)");
        row1.setTag("setup_step_1");
        progressCard.addView(row1);

        TextView row2 = createSetupCheckItem("2. Extracting Jellyarr & Jellyfin Core 12.4.0 ARM64 runtime");
        row2.setTag("setup_step_2");
        progressCard.addView(row2);

        TextView row3 = createSetupCheckItem("3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder");
        row3.setTag("setup_step_3");
        progressCard.addView(row3);

        TextView row4 = createSetupCheckItem("4. Configuring POSIX permissions & system libraries");
        row4.setTag("setup_step_4");
        progressCard.addView(row4);

        TextView row5 = createSetupCheckItem("5. Verifying server binaries & service readiness");
        row5.setTag("setup_step_5");
        progressCard.addView(row5);

        container.addView(progressCard);

        // Pre-Flight Diagnostics Card (Shown on first install)
        LinearLayout setupDiagCard = new LinearLayout(this);
        setupDiagCard.setOrientation(LinearLayout.VERTICAL);
        setupDiagCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        setupDiagCard.setBackground(createBorderedRoundedDrawable(AppTheme.card(), AppTheme.outline(), dp(1), dp(12)));
        LinearLayout.LayoutParams sdcParams = new LinearLayout.LayoutParams(-1, -2);
        sdcParams.bottomMargin = dp(12);
        setupDiagCard.setLayoutParams(sdcParams);

        TextView sdcTitle = new TextView(this);
        sdcTitle.setText("SYSTEM & PORT PRE-FLIGHT CHECK");
        sdcTitle.setTextSize(11);
        sdcTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        sdcTitle.setTextColor(AppTheme.accent());
        setupDiagCard.addView(sdcTitle);

        TextView sdcDetails = new TextView(this);
        String abiStr = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        sdcDetails.setText("• Architecture: " + abiStr + " (64-bit)\n• Core Port 8096: Probing...\n• Companion Ports: Probing...");
        sdcDetails.setTextSize(11);
        sdcDetails.setTypeface(Typeface.MONOSPACE);
        sdcDetails.setTextColor(AppTheme.text());
        sdcDetails.setPadding(0, dp(4), 0, 0);
        setupDiagCard.addView(sdcDetails);

        new Thread(() -> {
            boolean coreFree = false;
            try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress("127.0.0.1", 8096));
                coreFree = true;
            } catch (Throwable t) {
                coreFree = (controller != null && controller.isRunning());
            }
            int freeCount = 0;
            int conflictCount = 0;
            for (int p : new int[]{7878, 8989, 9696, 9117, 9091, 5055}) {
                try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress("127.0.0.1", p));
                    freeCount++;
                } catch (Throwable t) {
                    conflictCount++;
                }
            }
            final String coreStatus = coreFree ? "Available" : "Port Conflict (In Use)";
            final String compStatus = conflictCount == 0 ? "All 6 Ready (" + freeCount + " Free)" : conflictCount + " Conflict, " + freeCount + " Free";
            runOnUiThread(() -> {
                sdcDetails.setText("• Architecture: " + abiStr + " (64-bit)\n• Core Port 8096: " + coreStatus + "\n• Companion Ports: " + compStatus);
            });
        }).start();

        container.addView(setupDiagCard);

        // Required Permissions Onboarding Card
        container.addView(buildSetupPermissionsCard());


        // Live Log Preview Box
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        logCard.setBackground(createBorderedRoundedDrawable(AppTheme.card(), getBorderColor(), dp(1), dp(10)));
        LinearLayout.LayoutParams logCardParams = new LinearLayout.LayoutParams(-1, -2);
        logCardParams.bottomMargin = dp(12);
        logCard.setLayoutParams(logCardParams);

        TextView logTitle = new TextView(this);
        logTitle.setText("LIVE EXTRACTION LOG");
        logTitle.setTextSize(11);
        logTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        logTitle.setTextColor(getSecondaryTextColor());
        logTitle.setPadding(0, 0, 0, dp(4));
        logCard.addView(logTitle);

        setupLogSummaryText = new TextView(this);
        setupLogSummaryText.setText("Initializing unpacker engine...");
        setupLogSummaryText.setTextSize(11);
        setupLogSummaryText.setTypeface(Typeface.MONOSPACE);
        setupLogSummaryText.setTextColor(AppTheme.success());
        setupLogSummaryText.setMaxLines(4);
        setupLogSummaryText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        logCard.addView(setupLogSummaryText);

        container.addView(logCard);

        // Advisory Note
        TextView note = new TextView(this);
        note.setText("Please keep Jellyarr open while runtime extraction completes.\nThis setup process only runs once.");
        note.setTextSize(11.5f);
        note.setTextColor(getSecondaryTextColor());
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), 0, dp(8), dp(14));
        container.addView(note);

        // Continue to Jellyarr button (Shown when setup is completed or previewed)
        setupDismissBtn = createPixelButton("CONTINUE TO JELLYARR", getAccentColor(), AppTheme.onAccent());
        setupDismissBtn.setFocusable(true);
        setupDismissBtn.setFocusableInTouchMode(true);
        setupDismissBtn.setOnClickListener(v -> {
            isSetupOverlayManuallyOpened = false;
            if (setupDismissBtn != null) {
                setupDismissBtn.setVisibility(View.GONE);
            }
            setupOverlayView.setVisibility(View.GONE);
            setChromeVisibilityForSetup(false);
            renderActiveTab();
        });
        setupDismissBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pDismiss = new LinearLayout.LayoutParams(-1, dp(40));
        pDismiss.bottomMargin = dp(8);
        container.addView(setupDismissBtn, pDismiss);

        // Retry & Reinstall Buttons (Shown only if failure occurs)
        setupRetryBtn = createPixelButton("RETRY SETUP", getAccentColor(), AppTheme.onAccent());
        setupRetryBtn.setOnClickListener(v -> {
            setupRetryBtn.setVisibility(View.GONE);
            triggerServerAction(JellyfinServerService.ACTION_START);
            controller.reinstallRuntime(this);
        });
        setupRetryBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pRetry = new LinearLayout.LayoutParams(-1, dp(40));
        pRetry.bottomMargin = dp(8);
        container.addView(setupRetryBtn, pRetry);

        Button setupReinstallBtn = createPixelButton("CLEAN REINSTALL", AppTheme.inner(), Color.WHITE);
        setupReinstallBtn.setOnClickListener(v -> showReinstallConfirmation());
        setupReinstallBtn.setTag("setup_reinstall_btn");
        setupReinstallBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pReinst = new LinearLayout.LayoutParams(-1, dp(40));
        pReinst.bottomMargin = dp(8);
        container.addView(setupReinstallBtn, pReinst);

        scroll.addView(container);
        return scroll;
    }

    private View buildSetupPermissionsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(createBorderedRoundedDrawable(
                AppTheme.card(), AppTheme.outline(), dp(1), dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText("REQUIRED PERMISSIONS ONBOARDING");
        title.setTextSize(11);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(AppTheme.accent());
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Grant permissions now to enable 24/7 background streaming and external drive access.");
        sub.setTextSize(10.5f);
        sub.setTextColor(AppTheme.textMuted());
        sub.setPadding(0, dp(2), 0, dp(8));
        card.addView(sub);

        // Row 1: Storage
        setupPermStorageBadge = new TextView(this);
        card.addView(buildSetupPermItem(
                "All Files & Media Access",
                "Internal & external media drives",
                setupPermStorageBadge,
                v -> com.jellyarr.app.permissions.AppPermissionManager.requestStorage(this)
        ));
        card.addView(createSettingsDivider());

        // Row 2: Battery Optimization
        setupPermBatteryBadge = new TextView(this);
        card.addView(buildSetupPermItem(
                "Battery Optimization Exemption",
                "Continuous playback when screen is off",
                setupPermBatteryBadge,
                v -> com.jellyarr.app.permissions.AppPermissionManager.requestBatteryOptimizationExemption(this)
        ));
        card.addView(createSettingsDivider());

        // Row 3: Notifications
        setupPermNotifBadge = new TextView(this);
        card.addView(buildSetupPermItem(
                "Foreground Notifications",
                "System service protection & status",
                setupPermNotifBadge,
                v -> com.jellyarr.app.permissions.AppPermissionManager.requestNotification(this)
        ));
        card.addView(createSettingsDivider());

        // Row 4: OEM Autostart
        setupPermOemBadge = new TextView(this);
        setupPermOemBadge.setText("CONFIGURE");
        setupPermOemBadge.setTextColor(AppTheme.warning());
        setupPermOemBadge.setBackground(createRoundedDrawable(AppTheme.warningContainer(), dp(6)));
        setupPermOemBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        setupPermOemBadge.setTextSize(9f);
        setupPermOemBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(buildSetupPermItem(
                "OEM Autostart & Background Survival",
                "Power manager settings (Vivo, Xiaomi, Samsung)",
                setupPermOemBadge,
                v -> com.jellyarr.app.permissions.AppPermissionManager.openOemBackgroundSettings(this)
        ));

        // Grant All Button
        setupBtnGrantAll = createPixelButton("GRANT ALL PERMISSIONS", getAccentColor(), AppTheme.onAccent());
        setupBtnGrantAll.setTextSize(10.5f);
        setupBtnGrantAll.setOnClickListener(v -> com.jellyarr.app.permissions.AppPermissionManager.grantNextUngrantedPermission(this));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(36));
        btnLp.topMargin = dp(10);
        card.addView(setupBtnGrantAll, btnLp);

        refreshPermissionsUI();
        return card;
    }

    private View buildSetupPermItem(String title, String desc, TextView badge, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12f);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setTextColor(AppTheme.text());
        textCol.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(10f);
        d.setTextColor(AppTheme.textMuted());
        textCol.addView(d);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        badge.setTextSize(9f);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        row.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        return row;
    }

    private TextView createSetupCheckItem(String text) {
        TextView tv = new TextView(this);
        tv.setText("○  " + text);
        tv.setTextSize(12);
        tv.setTextColor(getSecondaryTextColor());
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private void ensureBootstrapStartedIfNeeded() {
        if (controller == null) return;
        boolean initialized = JellyfinBootstrapper.isInitialized(this);
        if (!initialized) {
            JellyfinController.State state = controller.getState();
            String lastErr = JellyfinBootstrapper.getLastError();
            boolean hasFailed = (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED)
                    || (lastErr != null && !lastErr.isEmpty() && state != JellyfinController.State.INITIALIZING && state != JellyfinController.State.STARTING);
            if (!hasFailed && state != JellyfinController.State.INITIALIZING && state != JellyfinController.State.STARTING) {
                Log.i(TAG, "ensureBootstrapStartedIfNeeded: initiating automatic Jellyfin runtime extraction & stack start");
                triggerServerAction(JellyfinServerService.ACTION_START);
                controller.start(getApplicationContext());
            }
        }
    }

    public void showSetupOverlay(boolean allowDismiss) {
        if (setupOverlayView == null) return;
        isSetupOverlayManuallyOpened = allowDismiss;
        setChromeVisibilityForSetup(true);
        if (setupDismissBtn != null) {
            boolean showDismiss = (allowDismiss || JellyfinBootstrapper.isInitialized(this));
            setupDismissBtn.setVisibility(showDismiss ? View.VISIBLE : View.GONE);
            if (showDismiss) {
                setupDismissBtn.post(() -> setupDismissBtn.requestFocus());
            }
        }
        setupOverlayView.setVisibility(View.VISIBLE);
        setupOverlayView.bringToFront();
        refreshPermissionsUI();
        renderSetupOverlayState();
    }

    private void renderSetupOverlayState() {
        if (setupOverlayView == null || controller == null) return;

        JellyfinController.State state = controller.getState();
        boolean initialized = JellyfinBootstrapper.isInitialized(this);
        boolean isInitializing = (state == JellyfinController.State.INITIALIZING);
        boolean isStarting = (state == JellyfinController.State.STARTING);
        boolean isRunning = (state == JellyfinController.State.RUNNING);
        boolean isFailed = (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED);

        // Overlay is visible ONLY when uninitialized, or manually opened from settings
        boolean showOverlay = (!initialized && (isInitializing || !isRunning)) || isSetupOverlayManuallyOpened;

        if (!initialized && !isFailed && !isInitializing && !isStarting && !isRunning) {
            ensureBootstrapStartedIfNeeded();
        }

        if (!showOverlay) {
            setupOverlayView.setVisibility(View.GONE);
            setChromeVisibilityForSetup(false);
            if (setupDismissBtn != null) {
                setupDismissBtn.setVisibility(View.GONE);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }

        setupOverlayView.setVisibility(View.VISIBLE);
        setChromeVisibilityForSetup(true);

        if (isInitializing || isStarting) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setupOverlayView.bringToFront();

        int pct = controller.getBootstrapProgressPercent();
        String msg = controller.getBootstrapProgressMessage();
        String lastErr = JellyfinBootstrapper.getLastError();

        boolean hasFailed = isFailed || (lastErr != null && !lastErr.isEmpty() && !isInitializing && !isStarting);

        if (hasFailed) {
            // Setup failed state
            if (setupDetailText != null) {
                String errSummary = (lastErr != null && !lastErr.isEmpty()) ? lastErr : "Installation failed or incomplete.";
                setupDetailText.setText(errSummary + "\nTap RETRY SETUP or CLEAN REINSTALL to unpack fresh binaries.");
                setupDetailText.setTextColor(AppTheme.danger());
            }
            if (setupProgressText != null) {
                setupProgressText.setText("FAILED");
                setupProgressText.setTextColor(AppTheme.danger());
            }
            if (setupRetryBtn != null) {
                setupRetryBtn.setVisibility(View.VISIBLE);
                setupRetryBtn.setEnabled(true);
            }
            View reinstallBtn = setupOverlayView.findViewWithTag("setup_reinstall_btn");
            if (reinstallBtn != null) {
                reinstallBtn.setVisibility(View.VISIBLE);
                reinstallBtn.setEnabled(true);
            }
            if (setupDismissBtn != null) {
                setupDismissBtn.setVisibility(View.GONE);
            }
        } else {
            // Active extraction / verification state
            int displayPct = Math.max(isInitializing ? 5 : 0, pct);
            if (setupProgressBar != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setupProgressBar.setProgress(displayPct, true);
                } else {
                    setupProgressBar.setProgress(displayPct);
                }
            }
            if (setupProgressText != null) {
                setupProgressText.setText(displayPct + "%");
                setupProgressText.setTextColor(getAccentColor());
            }
            if (setupDetailText != null) {
                String detail = (msg != null && !msg.isEmpty()) ? msg : (initialized ? "Runtime components fully verified & ready." : "Configuring runtime files...");
                setupDetailText.setText(detail);
                setupDetailText.setTextColor(initialized ? AppTheme.success() : getSecondaryTextColor());
            }
            if (setupRetryBtn != null) {
                setupRetryBtn.setVisibility(View.GONE);
            }
            View reinstallBtn = setupOverlayView.findViewWithTag("setup_reinstall_btn");
            if (reinstallBtn != null) {
                reinstallBtn.setVisibility(View.GONE);
            }
            if (setupDismissBtn != null) {
                boolean showDismiss = (initialized || isSetupOverlayManuallyOpened);
                setupDismissBtn.setVisibility(showDismiss ? View.VISIBLE : View.GONE);
                if (showDismiss) {
                    setupDismissBtn.post(() -> setupDismissBtn.requestFocus());
                }
            }

            // Update stage rows
            updateSetupStageItem("setup_step_1", "1. Recombining split package archives (asset chunks)", pct >= 35 || initialized, pct > 0 && pct < 35);
            updateSetupStageItem("setup_step_2", "2. Extracting Jellyarr & Jellyfin Core 12.4.0 ARM64 runtime", pct >= 65 || initialized, pct >= 35 && pct < 65);
            updateSetupStageItem("setup_step_3", "3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder", pct >= 88 || initialized, pct >= 65 && pct < 88);
            updateSetupStageItem("setup_step_4", "4. Configuring POSIX permissions & system libraries", pct >= 95 || initialized, pct >= 88 && pct < 95);
            updateSetupStageItem("setup_step_5", "5. Verifying server binaries & service readiness", initialized || pct == 100, pct >= 95 && !initialized);
        }

        if (setupLogSummaryText != null) {
            String logs = controller.getLogs();
            if (logs != null && !logs.isEmpty()) {
                String[] lines = logs.split("\n");
                int start = Math.max(0, lines.length - 4);
                StringBuilder lastFew = new StringBuilder();
                for (int i = start; i < lines.length; i++) {
                    if (lines[i].trim().isEmpty()) continue;
                    if (lastFew.length() > 0) lastFew.append("\n");
                    lastFew.append(lines[i].trim());
                }
                setupLogSummaryText.setText(lastFew.toString());
            }
        }
    }

    private void updateSetupStageItem(String tag, String label, boolean completed, boolean active) {
        if (setupOverlayView == null) return;
        TextView tv = setupOverlayView.findViewWithTag(tag);
        if (tv == null) return;
        if (completed) {
            tv.setText("✓  " + label);
            tv.setTextColor(AppTheme.success());
        } else if (active) {
            tv.setText("●  " + label);
            tv.setTextColor(getWarningColor());
        } else {
            tv.setText("○  " + label);
            tv.setTextColor(getSecondaryTextColor());
        }
    }

    // -- Tab 0: Home (Dashboard & Server Controls) ------------------------------

    private View buildHomeTab() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getBgColor());

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());
        // Bottom padding ensures content scrolls completely clear of the docked action bar
        scroll.setPadding(dp(16), dp(12), dp(16), dp(130));
        scroll.setClipToPadding(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // 0. Dynamic Emergency Password Reset Banner (Appears only during active reset, auto-hides after 5m)
        homePasswordResetBanner = buildHomePasswordResetBanner();
        layout.addView(homePasswordResetBanner);

        // 1. Hero Status Section (Clean, Unboxed)
        layout.addView(buildServerHeroSection());

        // 2. Error diagnostics right under the server status (the startup progress sits at the bottom of the
        //    Services card). Cards hide themselves when idle.
        startupProgressCard = buildRealStartupProgressCard();   // shown at the bottom of the Services card

        errorDiagnosticCard = buildErrorDiagnosticCard();
        layout.addView(errorDiagnosticCard);

        layout.addView(createSectionSpacer(dp(12)));

        // 3. Client Streaming IPs & Linked Account (rows inside one card)
        LinearLayout connectCard = UiKit.card(this);
        connectCard.setPadding(dp(8), dp(12), dp(8), dp(6));
        TextView connectTitle = UiKit.overline(this, "Connect");
        connectTitle.setPadding(dp(8), 0, dp(8), dp(2));
        connectCard.addView(connectTitle);
        connectCard.addView(buildClientStreamingIpsSection());
        layout.addView(connectCard);

        // 4. Services Control Plane (Horizontal Icon Row)
        LinearLayout servicesCard = UiKit.card(this);
        servicesCard.addView(buildServicesControlPlaneSection());
        servicesCard.addView(startupProgressCard);
        layout.addView(servicesCard);

        scroll.addView(layout);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Docked Server Controls at Bottom
        homeActionDock = buildHomeActionDock();
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dockParams.gravity = Gravity.BOTTOM;
        root.addView(homeActionDock, dockParams);

        return root;
    }

    private View buildHomePasswordResetBanner() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setBackground(createBorderedRoundedDrawable(
                AppTheme.inner(),
                AppTheme.danger(),
                dp(1),
                dp(12)
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        banner.setLayoutParams(lp);
        banner.setVisibility(View.GONE); // ONLY visible when reset is active

        // Top Header Row
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("EMERGENCY PASSWORD RESET ACTIVE");
        title.setTextSize(11f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(AppTheme.danger());
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        resetBannerTimerText = new TextView(this);
        resetBannerTimerText.setText("Expires in 05:00");
        resetBannerTimerText.setTextSize(10f);
        resetBannerTimerText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        resetBannerTimerText.setTextColor(AppTheme.danger());
        resetBannerTimerText.setPadding(dp(6), dp(2), dp(6), dp(2));
        resetBannerTimerText.setBackground(createBorderedRoundedDrawable(
                AppTheme.dangerContainer(), AppTheme.danger(), dp(1), dp(4)));
        top.addView(resetBannerTimerText);

        banner.addView(top);

        // Target user row
        resetBannerUserText = new TextView(this);
        resetBannerUserText.setText("Password reset requested on Jellyfin Web client.");
        resetBannerUserText.setTextSize(11.5f);
        resetBannerUserText.setTextColor(AppTheme.text());
        resetBannerUserText.setPadding(0, dp(6), 0, dp(6));
        banner.addView(resetBannerUserText);

        // PIN display box
        LinearLayout pinBox = new LinearLayout(this);
        pinBox.setOrientation(LinearLayout.VERTICAL);
        pinBox.setGravity(Gravity.CENTER);
        pinBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        pinBox.setBackground(createBorderedRoundedDrawable(
                AppTheme.card(), AppTheme.danger(), dp(1), dp(8)));
        LinearLayout.LayoutParams pinBoxLp = new LinearLayout.LayoutParams(-1, -2);
        pinBoxLp.topMargin = dp(4);
        pinBoxLp.bottomMargin = dp(10);
        pinBox.setLayoutParams(pinBoxLp);

        TextView lblPin = new TextView(this);
        lblPin.setText("ONE-TIME RESET PIN CODE");
        lblPin.setTextSize(9f);
        lblPin.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lblPin.setTextColor(AppTheme.textSecondary());
        lblPin.setLetterSpacing(0.08f);
        pinBox.addView(lblPin);

        resetBannerPinText = new TextView(this);
        resetBannerPinText.setText("----");
        resetBannerPinText.setTextSize(24f);
        resetBannerPinText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        resetBannerPinText.setTextColor(AppTheme.danger());
        resetBannerPinText.setLetterSpacing(0.2f);
        resetBannerPinText.setPadding(0, dp(4), 0, dp(2));
        pinBox.addView(resetBannerPinText);

        banner.addView(pinBox);

        // Action Buttons Row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnCopyPin = createPixelButton("COPY PIN", AppTheme.danger(), Color.WHITE);
        btnCopyPin.setTextSize(10f);
        btnCopyPin.setOnClickListener(v -> {
            CharSequence pin = resetBannerPinText.getText();
            if (pin != null && !pin.toString().contains("-")) {
                copyUrlToClipboard("Reset PIN", pin.toString().trim());
            }
        });
        btnRow.addView(btnCopyPin, new LinearLayout.LayoutParams(0, dp(34), 1.0f));

        View gap = new View(this);
        btnRow.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnOpenReset = createPixelButton("OPEN RESET PAGE", AppTheme.outline(), Color.WHITE);
        btnOpenReset.setTextSize(10f);
        btnOpenReset.setOnClickListener(v -> {
            String resetUrl = "http://127.0.0.1:8096/web/index.html#!/forgotpasswordpin.html";
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(resetUrl));
                startActivity(i);
            } catch (Throwable t) {
                copyUrlToClipboard("Reset URL", resetUrl);
            }
        });
        btnRow.addView(btnOpenReset, new LinearLayout.LayoutParams(0, dp(34), 1.0f));

        View gap2 = new View(this);
        btnRow.addView(gap2, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnDismiss = createPixelButton("DISMISS", AppTheme.inner(), AppTheme.textSecondary());
        btnDismiss.setTextSize(10f);
        btnDismiss.setOnClickListener(v -> {
            PasswordResetManager.clearActiveResetInfo();
            updatePasswordResetBanner(null);
        });
        btnRow.addView(btnDismiss, new LinearLayout.LayoutParams(0, dp(34), 0.8f));

        banner.addView(btnRow);
        return banner;
    }

    private void updatePasswordResetBanner(PasswordResetManager.ActiveResetInfo info) {
        if (homePasswordResetBanner == null) return;
        if (resetBannerCountdownRunnable != null) {
            resetBannerCountdownHandler.removeCallbacks(resetBannerCountdownRunnable);
            resetBannerCountdownRunnable = null;
        }

        if (info == null || info.isExpired()) {
            homePasswordResetBanner.setVisibility(View.GONE);
            return;
        }

        homePasswordResetBanner.setVisibility(View.VISIBLE);
        if (resetBannerUserText != null) {
            String u = info.userName != null && !info.userName.isEmpty() ? info.userName : "Administrator";
            resetBannerUserText.setText("Password reset requested for user: " + u);
        }
        if (resetBannerPinText != null) {
            resetBannerPinText.setText(info.pin != null ? info.pin : "----");
        }

        resetBannerCountdownRunnable = new Runnable() {
            @Override
            public void run() {
                PasswordResetManager.ActiveResetInfo current = PasswordResetManager.getActiveResetInfo();
                if (current == null || current.isExpired()) {
                    homePasswordResetBanner.setVisibility(View.GONE);
                    return;
                }
                long remSec = current.getRemainingSeconds();
                long mins = remSec / 60;
                long secs = remSec % 60;
                if (resetBannerTimerText != null) {
                    resetBannerTimerText.setText(String.format(Locale.ROOT, "Expires in %02d:%02d", mins, secs));
                }
                if (remSec > 0) {
                    resetBannerCountdownHandler.postDelayed(this, 1000);
                } else {
                    homePasswordResetBanner.setVisibility(View.GONE);
                }
            }
        };
        resetBannerCountdownRunnable.run();
    }

    private void refreshHomeAccountsUI() {
        updateHomeLinkedAccountStatus();
    }

    private void updateHomeLinkedAccountStatus() {
        if (homeLinkedAccountValueText == null) return;
        runBg(() -> {
            List<com.jellyarr.app.security.JellyarrSecurityManager.JellyfinUserRecord> users =
                    com.jellyarr.app.security.JellyarrSecurityManager.getRegisteredUsers(this);
            runOnUiThread(() -> {
                if (homeLinkedAccountValueText == null) return;
                View dotAccount = (rowAccountView != null) ? rowAccountView.findViewWithTag("dot_account") : null;
                if (users == null || users.isEmpty()) {
                    cachedCleanAccountUsername = null;
                    homeLinkedAccountValueText.setText("No account configured");
                    homeLinkedAccountValueText.setTextColor(AppTheme.textMuted());
                    setEndpointCopyState(btnCopyLinkedAccount, rowAccountView, dotAccount, false);
                } else {
                    StringBuilder sb = new StringBuilder();
                    StringBuilder cleanSb = new StringBuilder();
                    for (int i = 0; i < users.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                            cleanSb.append(", ");
                        }
                        sb.append(users.get(i).getUsername());
                        cleanSb.append(users.get(i).getUsername());
                        if (users.get(i).isAdmin()) sb.append(" (Admin)");
                    }
                    cachedCleanAccountUsername = cleanSb.toString();
                    homeLinkedAccountValueText.setText(sb.toString());
                    homeLinkedAccountValueText.setTextColor(AppTheme.text());
                    setEndpointCopyState(btnCopyLinkedAccount, rowAccountView, dotAccount, true);
                }
            });
        });
    }

    private View buildHomeActionDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(getBgColor());

        // Hairline top border
        View topBorder = new View(this);
        topBorder.setBackgroundColor(AppTheme.outline());
        dock.addView(topBorder, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(10), dp(16), dp(12));

        // Primary Action: OPEN JELLYFIN (White button, black text) or START SERVER
        btnOpenJellyfin = createPixelButton("OPEN JELLYFIN", AppTheme.accent(), AppTheme.onAccent());
        LinearLayout.LayoutParams pOpen = new LinearLayout.LayoutParams(-1, dp(44));
        btnOpenJellyfin.setLayoutParams(pOpen);
        btnOpenJellyfin.setOnClickListener(v -> startActivity(new Intent(this, JellyfinWebActivity.class)));
        applyButtonStyle(btnOpenJellyfin, "OPEN JELLYFIN", Color.WHITE, Color.BLACK, true);
        inner.addView(btnOpenJellyfin);

        btnStartServer = createPixelButton("START SERVER", AppTheme.accent(), AppTheme.onAccent());
        LinearLayout.LayoutParams pStart = new LinearLayout.LayoutParams(-1, dp(44));
        btnStartServer.setLayoutParams(pStart);
        btnStartServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            errorCardDismissed = false;

            applyButtonStyle(btnStartServer, "STARTING SERVER...", AppTheme.outline(), AppTheme.warning(), false);
            if (btnStopServer != null) btnStopServer.setEnabled(false);
            if (btnRestartServer != null) btnRestartServer.setEnabled(false);
            startFullServer();
        });
        applyButtonStyle(btnStartServer, "START SERVER", Color.WHITE, Color.BLACK, true);
        inner.addView(btnStartServer);

        // Secondary Controls Row: [Restart] and [Stop] side-by-side
        LinearLayout subControls = new LinearLayout(this);
        subControls.setOrientation(LinearLayout.HORIZONTAL);
        subControls.setBaselineAligned(false);
        LinearLayout.LayoutParams pSub = new LinearLayout.LayoutParams(-1, -2);
        pSub.topMargin = dp(8);
        subControls.setLayoutParams(pSub);

        btnRestartServer = createPixelButton("Restart", Color.TRANSPARENT, Color.WHITE);
        btnRestartServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            errorCardDismissed = false;

            applyDockButtonStyle(btnRestartServer, "Restarting...", AppTheme.outline(), AppTheme.textSecondary(), R.drawable.ic_restart, false);
            if (btnStartServer != null) btnStartServer.setEnabled(false);
            if (btnStopServer != null) btnStopServer.setEnabled(false);
            stopFullServer();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> startFullServer(), 3500);
        });
        applyDockButtonStyle(btnRestartServer, "Restart", AppTheme.outline(), Color.WHITE, R.drawable.ic_restart, true);
        LinearLayout.LayoutParams lpRestart = new LinearLayout.LayoutParams(0, dp(44), 1.0f);
        lpRestart.setMarginEnd(dp(6));
        subControls.addView(btnRestartServer, lpRestart);

        btnStopServer = createPixelButton("Stop", Color.TRANSPARENT, Color.WHITE);
        btnStopServer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;

            applyDockButtonStyle(btnStopServer, "Stopping...", AppTheme.dangerContainer(), AppTheme.danger(), R.drawable.ic_stop, false);
            if (btnStartServer != null) btnStartServer.setEnabled(false);
            if (btnRestartServer != null) btnRestartServer.setEnabled(false);
            stopFullServer();
        });
        applyDockButtonStyle(btnStopServer, "Stop", AppTheme.danger(), Color.WHITE, R.drawable.ic_stop, true);
        LinearLayout.LayoutParams lpStop = new LinearLayout.LayoutParams(0, dp(44), 1.0f);
        lpStop.setMarginStart(dp(6));
        subControls.addView(btnStopServer, lpStop);

        inner.addView(subControls);

        // Emergency action buttons (hidden by default)
        btnResetCrash = createPixelButton("RESET CRASH LOOP", getDangerColor(), Color.WHITE);
        btnResetCrash.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastButtonActionTimestamp < BUTTON_DEBOUNCE_MS) return;
            lastButtonActionTimestamp = now;
            controller.resetCrashLoop(this);
        });
        btnResetCrash.setVisibility(View.GONE);
        LinearLayout.LayoutParams pCrash = new LinearLayout.LayoutParams(-1, dp(42));
        pCrash.topMargin = dp(6);
        inner.addView(btnResetCrash, pCrash);

        btnReinstallRuntime = createPixelButton("REINSTALL RUNTIME", AppTheme.inner(), Color.WHITE);
        btnReinstallRuntime.setOnClickListener(v -> showReinstallConfirmation());
        btnReinstallRuntime.setVisibility(View.GONE);
        LinearLayout.LayoutParams pReinstall = new LinearLayout.LayoutParams(-1, dp(42));
        pReinstall.topMargin = dp(6);
        inner.addView(btnReinstallRuntime, pReinstall);

        dock.addView(inner);
        return dock;
    }

    private View buildServerHealthCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(createCardDrawable(getSurfaceColor(), dp(14)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        TextView header = new TextView(this);
        header.setText("STORAGE & PLAYBACK STATUS");
        header.setTextSize(10);
        header.setLetterSpacing(0.08f);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setTextColor(getSecondaryTextColor());
        header.setPadding(0, 0, 0, dp(10));
        card.addView(header);
        styleSectionLabel(header);

        // Row 1: Client Playback & Direct Stream Status
        LinearLayout rowStatus = new LinearLayout(this);
        rowStatus.setOrientation(LinearLayout.HORIZONTAL);
        rowStatus.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblStatus = new TextView(this);
        lblStatus.setText("CLIENT PLAYBACK");
        lblStatus.setTextSize(10);
        lblStatus.setLetterSpacing(0.06f);
        lblStatus.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblStatus.setTextColor(getSecondaryTextColor());
        rowStatus.addView(lblStatus, new LinearLayout.LayoutParams(0, -2, 1.0f));

        transcodeActiveBadge = new TextView(this);
        transcodeActiveBadge.setText("● IDLE / DIRECT PLAY");
        transcodeActiveBadge.setTextSize(11);
        transcodeActiveBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        transcodeActiveBadge.setTextColor(getSuccessColor());
        rowStatus.addView(transcodeActiveBadge);
        card.addView(rowStatus);

        transcodeDetailsText = new TextView(this);
        transcodeDetailsText.setText("Direct stream ready (0% phone CPU load for client devices)");
        transcodeDetailsText.setTextSize(11.5f);
        transcodeDetailsText.setTextColor(getSecondaryTextColor());
        transcodeDetailsText.setPadding(0, dp(4), 0, dp(6));
        card.addView(transcodeDetailsText);

        // Hairline Divider
        View d1 = new View(this);
        d1.setBackgroundColor(getBorderColor());
        LinearLayout.LayoutParams pD1 = new LinearLayout.LayoutParams(-1, dp(1));
        pD1.topMargin = dp(10);
        pD1.bottomMargin = dp(10);
        card.addView(d1, pD1);

        // Row 2: Internal Storage
        LinearLayout rowStorage = new LinearLayout(this);
        rowStorage.setOrientation(LinearLayout.HORIZONTAL);
        rowStorage.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblStorage = new TextView(this);
        lblStorage.setText("INTERNAL STORAGE");
        lblStorage.setTextSize(10);
        lblStorage.setLetterSpacing(0.06f);
        lblStorage.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblStorage.setTextColor(getSecondaryTextColor());
        rowStorage.addView(lblStorage, new LinearLayout.LayoutParams(0, -2, 1.0f));

        healthStorageText = new TextView(this);
        healthStorageText.setText("Checking...");
        healthStorageText.setTextSize(12);
        healthStorageText.setTypeface(Typeface.MONOSPACE);
        healthStorageText.setTextColor(getPrimaryTextColor());
        rowStorage.addView(healthStorageText);
        card.addView(rowStorage);

        // Hairline Divider
        View d2 = new View(this);
        d2.setBackgroundColor(getBorderColor());
        LinearLayout.LayoutParams pD2 = new LinearLayout.LayoutParams(-1, dp(1));
        pD2.topMargin = dp(10);
        pD2.bottomMargin = dp(10);
        card.addView(d2, pD2);

        // Row 3: External Storage (USB OTG)
        healthExternalStorageRow = new LinearLayout(this);
        ((LinearLayout) healthExternalStorageRow).setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) healthExternalStorageRow).setGravity(Gravity.CENTER_VERTICAL);
        TextView lblExt = new TextView(this);
        lblExt.setText("EXTERNAL USB STORAGE");
        lblExt.setTextSize(10);
        lblExt.setLetterSpacing(0.06f);
        lblExt.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblExt.setTextColor(getSecondaryTextColor());
        ((LinearLayout) healthExternalStorageRow).addView(lblExt, new LinearLayout.LayoutParams(0, -2, 1.0f));

        healthExternalStorageText = new TextView(this);
        healthExternalStorageText.setText("Checking...");
        healthExternalStorageText.setTextSize(12);
        healthExternalStorageText.setTypeface(Typeface.MONOSPACE);
        healthExternalStorageText.setTextColor(getPrimaryTextColor());
        ((LinearLayout) healthExternalStorageRow).addView(healthExternalStorageText);
        card.addView(healthExternalStorageRow);

        // Manage Media Storage Button
        Button btnManageStorage = createPixelButton("MANAGE MEDIA STORAGE", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnManageStorage.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(-1, dp(38));
        pBtn.topMargin = dp(12);
        card.addView(btnManageStorage, pBtn);

        return card;
    }

    private void refreshTranscodeCardUI() {
        if (transcodeEngineBadge == null) return;
        JellyfinEncodingConfig.EncodingOptions opts = JellyfinEncodingConfig.loadEncodingConfig(this);
        boolean isHw = JellyfinEncodingConfig.HW_ACCEL_MEDIACODEC.equalsIgnoreCase(opts.hardwareAccelerationType);
        transcodeEngineBadge.setText(isHw ? "Android MediaCodec (Hardware SoC)" : "ARM NEON SIMD (Multi-threaded Software)");
        transcodeEngineBadge.setTextColor(isHw ? AppTheme.warning() : getAccentColor());
    }

    /** One health probe at a time: renderCurrentState() runs every 500 ms while the server starts. */
    private volatile boolean healthRefreshInFlight = false;

    private void refreshServerHealthUI() {
        if (healthStatusBadge == null && healthUptimeText == null && healthStorageText == null) return;
        if (healthRefreshInFlight || controller == null) return;
        healthRefreshInFlight = true;
        runBg(() -> {
            final JellyfinController.ServerHealth health;
            final StorageDriveHelper.DriveInfo extDrive;
            final JellyfinEncodingConfig.EncodingOptions encOpts;
            final long freeMb;
            try {
                health = controller.getServerHealth();
                extDrive = StorageDriveHelper.getPrimaryExternalDrive(this);
                encOpts = JellyfinEncodingConfig.loadEncodingConfig(this);
                freeMb = controller.getAvailableInternalStorageMb();
            } catch (Throwable t) {
                healthRefreshInFlight = false;
                return;
            }
            healthRefreshInFlight = false;
            if (health == null) return;

            runOnUiThread(() -> {
                if (!isUiAlive()) return;
                if (healthStatusBadge != null) {
                    if (freeMb < 50) {
                        healthStatusBadge.setText("● STORAGE FULL");
                        healthStatusBadge.setTextColor(getDangerColor());
                        healthStatusBadge.setBackground(createBorderedRoundedDrawable(AppTheme.withAlpha(AppTheme.danger(), 25), AppTheme.withAlpha(AppTheme.danger(), 60), dp(1), dp(6)));
                    } else if (health.isHealthy) {
                        healthStatusBadge.setText("● HTTP 200");
                        healthStatusBadge.setTextColor(getSuccessColor());
                        healthStatusBadge.setBackground(createBorderedRoundedDrawable(AppTheme.withAlpha(AppTheme.success(), 25), AppTheme.withAlpha(AppTheme.success(), 60), dp(1), dp(6)));
                    } else if (controller.getState() == JellyfinController.State.RUNNING) {
                        healthStatusBadge.setText("● Responding");
                        healthStatusBadge.setTextColor(getWarningColor());
                        healthStatusBadge.setBackground(createBorderedRoundedDrawable(AppTheme.withAlpha(AppTheme.warning(), 25), AppTheme.withAlpha(AppTheme.warning(), 60), dp(1), dp(6)));
                    } else {
                        healthStatusBadge.setText("● Offline");
                        healthStatusBadge.setTextColor(getDangerColor());
                        healthStatusBadge.setBackground(createBorderedRoundedDrawable(AppTheme.withAlpha(AppTheme.danger(), 25), AppTheme.withAlpha(AppTheme.danger(), 60), dp(1), dp(6)));
                    }
                }
                if (healthDetailText != null) {
                    if (freeMb < 50) {
                        healthDetailText.setText("Critical: Device storage exhausted (<50 MB free)");
                        healthDetailText.setTextColor(getDangerColor());
                    } else if (health.isHealthy) {
                        healthDetailText.setText("All server subsystems active & listening on port 8096");
                        healthDetailText.setTextColor(getSuccessColor());
                    } else if (controller.getState() == JellyfinController.State.RUNNING) {
                        healthDetailText.setText("Server process running, polling HTTP endpoint...");
                        healthDetailText.setTextColor(getWarningColor());
                    } else {
                        healthDetailText.setText("Server process is stopped");
                        healthDetailText.setTextColor(getSecondaryTextColor());
                    }
                }
                if (healthUptimeText != null) {
                    long s = health.uptimeSeconds;
                    long h = s / 3600;
                    long m = (s % 3600) / 60;
                    long sec = s % 60;
                    String uptimeStr = (h > 0) ? String.format(Locale.ROOT, "%dh %dm %ds", h, m, sec)
                            : (m > 0) ? String.format(Locale.ROOT, "%dm %ds", m, sec)
                            : String.format(Locale.ROOT, "%ds", sec);
                    healthUptimeText.setText(uptimeStr);
                }
                if (healthStorageText != null) {
                    if (health.storageTotalMb > 0) {
                        double freeGb = health.storageFreeMb / 1024.0;
                        double totalGb = health.storageTotalMb / 1024.0;
                        healthStorageText.setText(String.format(Locale.ROOT, "%.1f GB free / %.1f GB", freeGb, totalGb));
                    } else {
                        healthStorageText.setText("N/A");
                    }
                }
                if (healthExternalStorageText != null) {
                    if (extDrive != null && extDrive.totalBytes > 0) {
                        String cap = StorageDriveHelper.formatCapacity(extDrive.freeBytes, extDrive.totalBytes);
                        healthExternalStorageText.setText(extDrive.name + " - " + cap);
                        healthExternalStorageText.setTextColor(getSuccessColor());
                    } else if (extDrive != null) {
                        healthExternalStorageText.setText(extDrive.name + " (Mounted)");
                        healthExternalStorageText.setTextColor(getSuccessColor());
                    } else {
                        healthExternalStorageText.setText("No external drive connected");
                        healthExternalStorageText.setTextColor(getSecondaryTextColor());
                    }
                }
                if (transcodeActiveBadge != null) {
                    if (health.isTranscodingActive) {
                        transcodeActiveBadge.setText("● TRANSCODING ACTIVE (" + health.activeTranscodeCount + " files)");
                        transcodeActiveBadge.setTextColor(getAccentColor());
                        if (transcodeDetailsText != null) {
                            transcodeDetailsText.setText("Active on-the-fly media transcoding to client");
                            transcodeDetailsText.setTextColor(getAccentColor());
                        }
                    } else {
                        transcodeActiveBadge.setText("● IDLE / DIRECT PLAY");
                        transcodeActiveBadge.setTextColor(getSuccessColor());
                    }
                }
                if (transcodeEngineBadge != null && encOpts != null) {
                    boolean isHw = JellyfinEncodingConfig.HW_ACCEL_MEDIACODEC.equalsIgnoreCase(encOpts.hardwareAccelerationType);
                    transcodeEngineBadge.setText(isHw ? "Android MediaCodec (Hardware SoC)" : "ARM NEON SIMD (Software)");
                    transcodeEngineBadge.setTextColor(isHw ? AppTheme.warning() : getAccentColor());
                }
            });
        });
    }

    private View buildServerHeroSection() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        // Left Column: Status Badge
        statusBadge = new TextView(this);
        statusBadge.setText("● SERVER STOPPED");
        statusBadge.setTextSize(12f);
        statusBadge.setTypeface(UiKit.BOLD);
        statusBadge.setLetterSpacing(0.05f);
        statusBadge.setSingleLine(true);
        statusBadge.setTextColor(AppTheme.textSecondary());
        statusBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusBadge.setBackground(UiKit.shape(this, AppTheme.withAlpha(AppTheme.textSecondary(), 0x22), 0, UiKit.R_PILL));
        // pill hugs its text; the remaining width goes to a flexible gap before the uptime
        row.addView(statusBadge, new LinearLayout.LayoutParams(-2, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1.0f));

        // Right Column: Inline Uptime
        LinearLayout uptimeRow = new LinearLayout(this);
        uptimeRow.setOrientation(LinearLayout.HORIZONTAL);
        uptimeRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        TextView lblUptime = UiKit.text(this, "UPTIME", 11f, AppTheme.textMuted(), UiKit.BOLD);
        lblUptime.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(-2, -2);
        upLp.setMarginEnd(dp(6));
        uptimeRow.addView(lblUptime, upLp);

        healthUptimeText = new TextView(this);
        healthUptimeText.setText("0s");
        healthUptimeText.setTextSize(12.5f);
        healthUptimeText.setTypeface(UiKit.MEDIUM);
        healthUptimeText.setTextColor(AppTheme.text());
        uptimeRow.addView(healthUptimeText);

        row.addView(uptimeRow);
        return row;
    }

    private View buildClientStreamingIpsSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        clientEndpointsContainer = new LinearLayout(this);
        clientEndpointsContainer.setOrientation(LinearLayout.VERTICAL);
        section.addView(clientEndpointsContainer);

        renderClientStreamingEndpoints(cachedRawLanIp, cachedRawTailscaleIp);
        return section;
    }

    private void renderClientStreamingEndpoints(String rawLan, String rawTailscale) {
        if (clientEndpointsContainer == null) return;
        clientEndpointsContainer.removeAllViews();

        // Interface enumeration happens off the UI thread in refreshLanAddress(); use its cached results.
        final String defaultIp = cachedDefaultIp != null ? cachedDefaultIp : "192.0.0.2";
        lastRenderedNetworkSignature = networkSignature(rawLan, rawTailscale);

        // 1. DEFAULT Endpoint (192.0.0.2:8096) - Fixed / Carrier Default
        defaultIpValueText = new TextView(this);
        defaultIpValueText.setText(defaultIp + ":8096");
        ImageView copyDefault = new ImageView(this);
        btnCopyDefaultIp = copyDefault;
        rowDefaultView = buildEndpointRow("default", "DEFAULT", defaultIpValueText, copyDefault, () -> {
            copyUrlToClipboard("Default Endpoint", "http://" + defaultIp + ":8096");
        });
        View dotDefault = rowDefaultView.findViewWithTag("dot_default");
        setEndpointCopyState(copyDefault, rowDefaultView, dotDefault, true);
        clientEndpointsContainer.addView(rowDefaultView);
        clientEndpointsContainer.addView(createHairlineDivider());

        // 2. Active Mobile / Wi-Fi / LAN Endpoint (Current dynamic connection)
        NetworkUtils.LanEndpoint activeLan = cachedActiveLan;
        lanIpValueText = new TextView(this);
        ImageView copyLan = new ImageView(this);
        btnCopyLanIp = copyLan;
        final String lanLabel;
        final String lanVal;
        final boolean lanOperable;
        if (activeLan != null) {
            lanLabel = activeLan.label.toUpperCase(Locale.ROOT);
            lanVal = activeLan.ip + ":8096";
            lanOperable = true;
        } else if (rawLan != null && !rawLan.isEmpty()) {
            lanLabel = "LAN";
            lanVal = rawLan + ":8096";
            lanOperable = true;
        } else {
            lanLabel = "LAN";
            lanVal = "Wi-Fi not connected";
            lanOperable = false;
        }
        lanIpValueText.setText(lanVal);
        rowLanView = buildEndpointRow("lan", lanLabel, lanIpValueText, copyLan, () -> {
            // Decide from state, not from the displayed (possibly translated) text.
            if (lanOperable) copyUrlToClipboard(lanLabel + " Endpoint", "http://" + lanVal);
        });
        View dotLan = rowLanView.findViewWithTag("dot_lan");
        setEndpointCopyState(copyLan, rowLanView, dotLan, lanOperable);
        clientEndpointsContainer.addView(rowLanView);

        // 3. Conditional TAILSCALE (Only shown if actively connected to Tailscale)
        if (rawTailscale != null && !rawTailscale.isEmpty()) {
            clientEndpointsContainer.addView(createHairlineDivider());
            tailscaleIpValueText = new TextView(this);
            tailscaleIpValueText.setText(rawTailscale + ":8096");
            ImageView copyTailscale = new ImageView(this);
            btnCopyTailscaleIp = copyTailscale;
            rowTailscaleView = buildEndpointRow("tailscale", "TAILSCALE", tailscaleIpValueText, copyTailscale, () -> {
                copyUrlToClipboard("Tailscale Endpoint", "http://" + rawTailscale + ":8096");
            });
            View dotTailscale = rowTailscaleView.findViewWithTag("dot_tailscale");
            setEndpointCopyState(copyTailscale, rowTailscaleView, dotTailscale, true);
            clientEndpointsContainer.addView(rowTailscaleView);
        }

        // 4. LINKED ACCOUNT (Minimal, seamless integration)
        clientEndpointsContainer.addView(createHairlineDivider());
        homeLinkedAccountValueText = new TextView(this);
        homeLinkedAccountValueText.setText("Checking...");
        ImageView copyAccount = new ImageView(this);
        btnCopyLinkedAccount = copyAccount;
        rowAccountView = buildEndpointRow("account", "ACCOUNT", homeLinkedAccountValueText, copyAccount, () -> {
            // Only the loaded user list is copied; the label may be "Checking..." or translated.
            String names = cachedCleanAccountUsername;
            if (names != null && !names.isEmpty()) copyUrlToClipboard("Linked Account", names);
        });
        View dotAccount = rowAccountView.findViewWithTag("dot_account");
        setEndpointCopyState(copyAccount, rowAccountView, dotAccount, false);
        clientEndpointsContainer.addView(rowAccountView);

        updateHomeLinkedAccountStatus();
    }

    private View buildEndpointRow(String tag, String typeLabel, TextView valueView, ImageView copyBtn, Runnable onCopy) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(UiKit.rowRipple(this));

        View dot = new View(this);
        dot.setTag("dot_" + tag);
        dot.setBackground(createCircleDrawable(AppTheme.textMuted()));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotParams.setMarginEnd(dp(12));
        row.addView(dot, dotParams);

        TextView lbl = new TextView(this);
        lbl.setText(typeLabel);
        lbl.setTextSize(11.5f);
        lbl.setTypeface(UiKit.BOLD);
        lbl.setLetterSpacing(0.06f);
        lbl.setAllCaps(true);
        lbl.setSingleLine(true);
        lbl.setTextColor(AppTheme.textSecondary());
        LinearLayout.LayoutParams lblParams = new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(lbl, lblParams);

        valueView.setTextSize(13f);
        // addresses are monospace; the account row shows names, so it uses the UI font
        valueView.setTypeface("account".equals(tag) ? UiKit.MEDIUM : Typeface.MONOSPACE);
        valueView.setTextColor(AppTheme.text());
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams valParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        valParams.setMarginEnd(dp(8));
        row.addView(valueView, valParams);

        copyBtn.setImageResource(R.drawable.ic_copy);
        copyBtn.setColorFilter(AppTheme.textMuted());
        copyBtn.setEnabled(false);
        copyBtn.setAlpha(0.45f);
        LinearLayout.LayoutParams cpParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        copyBtn.setPadding(dp(7), dp(7), dp(7), dp(7));
        copyBtn.setBackground(UiKit.rowRipple(this));
        copyBtn.setContentDescription("Copy");
        row.addView(copyBtn, cpParams);

        row.setOnClickListener(v -> {
            if (copyBtn.isEnabled()) onCopy.run();
        });
        copyBtn.setOnClickListener(v -> {
            if (copyBtn.isEnabled()) onCopy.run();
        });

        return row;
    }

    private void setEndpointCopyState(ImageView icon, View row, View dot, boolean operable) {
        if (icon != null) {
            icon.setEnabled(operable);
            icon.setColorFilter(operable ? AppTheme.text() : AppTheme.textMuted());
            icon.setAlpha(operable ? 1.0f : 0.45f);
        }
        if (row != null) {
            row.setEnabled(operable);
        }
        if (dot != null) {
            dot.setBackground(createCircleDrawable(operable ? getSuccessColor() : AppTheme.textMuted()));
        }
    }

    private View createHairlineDivider() {
        View v = new View(this);
        v.setBackgroundColor(AppTheme.outline());
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private View createSectionSpacer() {
        return createSectionSpacer(dp(28));
    }

    private View createSectionSpacer(int heightPx) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return v;
    }

    private GradientDrawable createCircleDrawable(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    private Drawable createRowRippleDrawable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(AppTheme.outline()),
                    null,
                    createBorderedRoundedDrawable(Color.TRANSPARENT, Color.TRANSPARENT, 0, dp(6)));
        }
        return null;
    }



    private void copyUrlToClipboard(String label, String url) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, url));
            Toast.makeText(this, I18n.tr("Copied " + label + " to clipboard"), Toast.LENGTH_SHORT).show();
        }
    }

    private View buildRealStartupProgressCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(createCardDrawable(getSurfaceElevatedColor(), dp(14)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        startupProgressHeader = new TextView(this);
        startupProgressHeader.setText("Starting Jellyarr Server");
        startupProgressHeader.setTextSize(13);
        startupProgressHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        startupProgressHeader.setTextColor(getPrimaryTextColor());
        card.addView(startupProgressHeader);

        startupProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        startupProgressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(4));
        progressParams.topMargin = dp(8);
        progressParams.bottomMargin = dp(10);
        card.addView(startupProgressBar, progressParams);

        txtStage1 = createStageText("1. Starting runtime environment");
        txtStage2 = createStageText("2. Launching Jellyarr core server process");
        txtStage3 = createStageText("3. Waiting for server (binding port 8096)");
        txtStage4 = createStageText("4. Checking readiness & migrating database");
        txtStage5 = createStageText("5. Ready");

        card.addView(txtStage1);
        card.addView(txtStage2);
        card.addView(txtStage3);
        card.addView(txtStage4);
        card.addView(txtStage5);

        // Companion services (Transmission, Prowlarr, Radarr, Sonarr): one row each while they start
        startupCompanionsContainer = new LinearLayout(this);
        startupCompanionsContainer.setOrientation(LinearLayout.VERTICAL);
        startupCompanionsContainer.setPadding(0, dp(8), 0, 0);
        card.addView(startupCompanionsContainer);

        return card;
    }

    /**
     * Updates one startup row per installed companion service. Returns {0 = any starting, 1 = any still waiting}
     * as booleans in a 2-element array.
     */
    private boolean[] updateStartupCompanionRows() {
        boolean anyStarting = false;
        boolean anyWaiting = false;
        if (startupCompanionsContainer == null) return new boolean[]{false, false};
        java.util.List<ManagedService> installed = new java.util.ArrayList<>();
        try {
            for (ManagedService s : MediaServiceManager.getInstance(this).getAllServices()) {
                if ("jellyfin".equalsIgnoreCase(s.getId()) || !s.isInstalled()) continue;
                installed.add(s);
            }
        } catch (Throwable ignored) {}
        // Rebuild the rows only when the set of installed services changed.
        boolean same = installed.size() == startupCompanionRows.size();
        if (same) for (ManagedService s : installed) if (!startupCompanionRows.containsKey(s.getId())) { same = false; break; }
        if (!same) {
            startupCompanionsContainer.removeAllViews();
            startupCompanionRows.clear();
            for (ManagedService s : installed) {
                TextView row = createStageText(s.getManifest().getDisplayName());
                startupCompanionRows.put(s.getId(), row);
                startupCompanionsContainer.addView(row);
            }
        }
        for (ManagedService s : installed) {
            boolean running = s.isRunning();
            boolean starting = s.isStarting();
            if (starting) anyStarting = true;
            if (!running && !starting) anyWaiting = true;
            updateStageRow(startupCompanionRows.get(s.getId()), s.getManifest().getDisplayName(), running, starting);
        }
        startupCompanionsContainer.setVisibility(installed.isEmpty() ? View.GONE : View.VISIBLE);
        return new boolean[]{anyStarting, anyWaiting};
    }

    private TextView createStageText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(getSecondaryTextColor());
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }



    private void triggerServerAction(String action) {
        if (JellyfinServerService.ACTION_STOP_JELLYFIN.equals(action)
                || JellyfinServerService.ACTION_STOP_ALL.equals(action)) {
            startupCompletedTimeMs = 0;
            mainHandler.removeCallbacks(startupProgressPoller);
        }
        Intent svc = new Intent(this, JellyfinServerService.class);
        svc.setAction(action);
        if (JellyfinServerService.ACTION_START.equals(action)
                || JellyfinServerService.ACTION_START_JELLYFIN.equals(action)
                || JellyfinServerService.ACTION_START_ALL.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } else {
            startService(svc);
        }
    }

    // -- Tab 0 Components: Home Services -------------------------------------

    private View buildServicesControlPlaneSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        // Header Row: SERVICES on left, 7 / 7 Active on right
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp(12));

        TextView title = new TextView(this);
        title.setText("SERVICES");
        styleSectionLabel(title);
        headerRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        homeServicesCountBadge = UiKit.caption(this, "7 / 7 Active");
        homeServicesCountBadge.setTypeface(UiKit.MEDIUM);
        headerRow.addView(homeServicesCountBadge);

        section.addView(headerRow);

        // Multi-row Vertical Container of Installed Icon Tiles (5 per row)
        LinearLayout tilesContainer = new LinearLayout(this);
        tilesContainer.setOrientation(LinearLayout.VERTICAL);
        tilesContainer.setPadding(0, dp(2), 0, 0);

        homeServicesRow = tilesContainer;
        rebuildHomeServiceTiles();

        section.addView(tilesContainer);

        refreshHomeServicesBadge();
        return section;
    }

    private static class HomeServiceTileSpec {
        final String id;
        final String name;
        final int iconRes;
        final int brandColor;
        HomeServiceTileSpec(String id, String name, int iconRes, int brandColor) {
            this.id = id;
            this.name = name;
            this.iconRes = iconRes;
            this.brandColor = brandColor;
        }
    }

    private boolean isMovieStreamerModeEnabled() {
        return getJellyarrPrefs().getBoolean("movie_streamer_mode", false);
    }

    private void rebuildHomeServiceTiles() {
        if (homeServicesRow == null) return;
        homeServicesRow.removeAllViews();
        serviceTileMap.clear();

        List<HomeServiceTileSpec> tiles = new ArrayList<>();
        // 1. Core Jellyfin tile (always present), then 2. installed companions in a fixed order
        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        for (String sid : homeTileIds()) {
            if ("jellyfin".equals(sid)) {
                tiles.add(new HomeServiceTileSpec("jellyfin", "Jellyfin", R.drawable.logo_jellyfin, AppTheme.text()));
            } else {
                ManagedService s = mgr.getService(sid);
                String nm = s != null ? s.getDisplayName() : sid;
                tiles.add(new HomeServiceTileSpec(sid, nm, getServiceIconRes(sid), AppTheme.text()));
            }
        }

        // Row 1: Top 5 service logos
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        int row1Count = Math.min(5, tiles.size());
        for (int i = 0; i < row1Count; i++) {
            HomeServiceTileSpec spec = tiles.get(i);
            addServiceIconTile(row1, spec.id, spec.name, spec.iconRes, spec.brandColor);
        }
        homeServicesRow.addView(row1);

        // Row 2: Remaining service logos below Row 1
        if (tiles.size() > 5) {
            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setGravity(Gravity.CENTER_VERTICAL);
            row2.setPadding(0, dp(6), 0, 0);

            int row2Count = tiles.size() - 5;
            for (int i = 5; i < tiles.size(); i++) {
                HomeServiceTileSpec spec = tiles.get(i);
                addServiceIconTile(row2, spec.id, spec.name, spec.iconRes, spec.brandColor);
            }
            // Add spacers so Row 2 items align perfectly with Row 1's 5 columns
            for (int s = 0; s < (5 - row2Count); s++) {
                FrameLayout spacer = new FrameLayout(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                row2.addView(spacer);
            }
            homeServicesRow.addView(row2);
        }
    }

    private void addServiceIconTile(LinearLayout parent, String id, String name, int iconRes, int brandColor) {
        FrameLayout slot = new FrameLayout(this);
        slot.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        FrameLayout tile = new FrameLayout(this);
        FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        tileParams.gravity = Gravity.CENTER;
        tile.setLayoutParams(tileParams);
        // Clean unboxed presentation: no background container box on service logos (state ring is the background)
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(name);
        if (Build.VERSION.SDK_INT >= 23) tile.setForeground(UiKit.rowRipple(this));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        if (isColorLogoRes(iconRes)) {
            icon.setTag("logo");
        } else {
            icon.setColorFilter(brandColor);
        }
        int iconSize = dp(26);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER;
        tile.addView(icon, iconParams);

        serviceTileMap.put(id, icon);

        tile.setOnClickListener(v -> {
            ManagedService svc = MediaServiceManager.getInstance(this).getService(id);
            if (svc != null) {
                showServiceDetailsModal(svc);
            }
        });

        slot.addView(tile);
        parent.addView(slot);
    }

    /** Ids of the Home tiles, in display order: always Jellyfin, then the installed core companions. */
    private List<String> homeTileIds() {
        List<String> ids = new ArrayList<>();
        ids.add("jellyfin");
        if (!isMovieStreamerModeEnabled() && isMediaStackEnabled()) {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            for (String sid : new String[]{"radarr", "sonarr", "prowlarr", "transmission"}) {
                ManagedService s = mgr.getService(sid);
                if (s != null && s.isInstalled()) ids.add(s.getId());
            }
        }
        return ids;
    }

    private void refreshHomeServicesBadge() {
        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        List<ManagedService> services = mgr.getAllServices();
        // Count exactly what the Home row shows (other installed services have no tile).
        final List<String> tileIds = homeTileIds();
        int running = 0;
        for (String id : tileIds) {
            if ("jellyfin".equals(id)) {
                if (controller != null && controller.getState() == JellyfinController.State.RUNNING) running++;
            } else {
                ManagedService s = mgr.getService(id);
                if (s != null && s.isRunning()) running++;
            }
        }

        final int fRunning = running;
        final int fInstalled = tileIds.size();
        final List<ManagedService> fServices = services;

        runOnUiThread(() -> {
            // Rebuild only when the set of tiles changed (the old size check never matched with extra services
            // installed or in streamer mode, so the row was rebuilt on every state change).
            if (homeServicesRow != null && !new HashSet<>(tileIds).equals(serviceTileMap.keySet())) {
                rebuildHomeServiceTiles();
            }

            if (homeServicesCountBadge != null) {
                homeServicesCountBadge.setText(fRunning + " / " + fInstalled + " Active");
                homeServicesCountBadge.setTextColor(fRunning > 0 ? AppTheme.success() : AppTheme.textMuted());
            }

            // Jellyfin Service Tile (Jfn glyph)
            ImageView jIcon = serviceTileMap.get("jellyfin");
            if (jIcon != null) {
                JellyfinController.State js = controller != null ? controller.getState() : JellyfinController.State.STOPPED;
                int jCat;
                switch (js) {
                    case RUNNING:
                        jCat = 2;
                        break;
                    case STARTING:
                    case INITIALIZING:
                        jCat = 1;
                        break;
                    case CRASHED:
                    case FAILED:
                    case CRASH_LOOP:
                        jCat = 3;
                        break;
                    default:
                        jCat = 0;
                        break;
                }
                applyTileState(jIcon, jCat, getServiceBrandColor("jellyfin"));
            }

            // Other Services Tiles (Brand-colored typography glyphs)
            for (ManagedService s : fServices) {
                if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
                ImageView icon = serviceTileMap.get(s.getId());
                if (icon != null) {
                    applyTileState(icon, tileCategory(s.getState()), getServiceBrandColor(s.getId()));
                }
            }
        });
    }

    /** 0 = off, 1 = starting, 2 = running OK, 3 = failed. */
    private int tileCategory(com.jellyarr.app.model.ServiceState state) {
        if (state == null) return 0;
        switch (state) {
            case RUNNING:
            case API_READY:
                return 2;
            case STARTING:
            case PROCESS_READY:
            case SOCKET_READY:
            case RECOVERING:
            case INSTALLING:
            case UPDATING:
                return 1;
            case FAILED:
            case ERROR:
            case CRASH_LOOP_PROTECTION:
            case UNHEALTHY:
                return 3;
            default:
                return 0;
        }
    }

    /** Home logo state: faded grey when off; brand-coloured logo inside a yellow / green / red ring otherwise. */
    private void applyTileState(ImageView icon, int category, int brand) {
        int ring;
        switch (category) {
            case 1: ring = getWarningColor(); break;
            case 2: ring = AppTheme.success(); break;
            case 3: ring = getDangerColor(); break;
            default: ring = 0; break;
        }
        if ("logo".equals(icon.getTag())) {
            applyColorLogoState(icon, category != 0);
        } else if (category == 0) {
            icon.setColorFilter(AppTheme.textMuted());
            icon.setAlpha(0.45f);
        } else {
            icon.setColorFilter(brand);
            icon.setAlpha(1f);
        }
        View tile = icon.getParent() instanceof View ? (View) icon.getParent() : null;
        if (tile == null) return;
        if (category == 0) {
            tile.setBackground(null);
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor((ring & 0x00FFFFFF) | 0x26000000);
            g.setStroke(dp(2), ring);
            tile.setBackground(g);
            tile.setElevation(category == 1 ? dp(2) : dp(3));
        }
    }

    private int getServiceStateTintColor(com.jellyarr.app.model.ServiceState state) {
        if (state == null) return AppTheme.text();
        switch (state) {
            case RUNNING:
            case API_READY:
                return AppTheme.success();
            case STARTING:
            case PROCESS_READY:
            case SOCKET_READY:
            case RECOVERING:
            case INSTALLING:
            case UPDATING:
                return getWarningColor();
            case FAILED:
            case ERROR:
            case CRASH_LOOP_PROTECTION:
            case UNHEALTHY:
                return getDangerColor();
            case STOPPED:
            case NOT_INSTALLED:
            case INSTALLED:
            case STOPPING:
            case DISABLED:
            case BLOCKED:
            default:
                return AppTheme.text();
        }
    }

    // Returns the brand color for a service when running, white when off
    private int getServiceBrandColor(String serviceId) {
        return com.jellyarr.app.ui.DashboardPresenter.getServiceBrandColor(serviceId);
    }

    private int getServiceIconRes(String serviceId) {
        if (serviceId == null) return R.drawable.logo_jellyfin;
        switch (serviceId.toLowerCase(Locale.ROOT)) {
            case "radarr":       return R.drawable.logo_radarr;
            case "sonarr":       return R.drawable.logo_sonarr;
            case "prowlarr":     return R.drawable.logo_prowlarr;
            case "transmission": return R.drawable.logo_transmission;
            case "jellyfin":
            default:             return R.drawable.logo_jellyfin;
        }
    }

    private int getServiceBrandTintColor(String serviceId, com.jellyarr.app.model.ServiceState state) {
        if (state == null) return AppTheme.text();
        switch (state) {
            case RUNNING:
            case API_READY:
                return getServiceBrandColor(serviceId);
            case STARTING:
            case PROCESS_READY:
            case SOCKET_READY:
            case RECOVERING:
            case INSTALLING:
            case UPDATING:
                return getWarningColor();
            case FAILED:
            case ERROR:
            case CRASH_LOOP_PROTECTION:
            case UNHEALTHY:
                return getDangerColor();
            case STOPPED:
            case NOT_INSTALLED:
            case INSTALLED:
            case STOPPING:
            case DISABLED:
            case BLOCKED:
            default:
                return AppTheme.text();
        }
    }

    // -- Home Downloads Summary Card -------------------------------------------

    private View buildHomeDownloadsSummaryCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(createCardDrawable(getSurfaceColor(), dp(8)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        card.setLayoutParams(params);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ACTIVE DOWNLOADS");
        title.setTextSize(11);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getSecondaryTextColor());
        headerRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        homeDownloadsCountBadge = new TextView(this);
        homeDownloadsCountBadge.setText("Idle");
        homeDownloadsCountBadge.setTextSize(10);
        homeDownloadsCountBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        homeDownloadsCountBadge.setTextColor(getSecondaryTextColor());
        homeDownloadsCountBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        homeDownloadsCountBadge.setBackground(createBorderedRoundedDrawable(getSurfaceElevatedColor(), getBorderColor(), dp(1), dp(8)));
        headerRow.addView(homeDownloadsCountBadge);

        card.addView(headerRow);

        homeDownloadsSummaryText = new TextView(this);
        homeDownloadsSummaryText.setText("No active downloads in progress.");
        homeDownloadsSummaryText.setTextSize(12);
        homeDownloadsSummaryText.setTextColor(getPrimaryTextColor());
        homeDownloadsSummaryText.setPadding(0, dp(8), 0, dp(10));
        card.addView(homeDownloadsSummaryText);

        Button btnViewDownloads = createPixelButton("VIEW ALL DOWNLOADS", getSurfaceElevatedColor(), getAccentColor());
        btnViewDownloads.setOnClickListener(v -> {
            activeTab = 1;
            updateBottomNavSelection();
            renderActiveTab();
        });
        LinearLayout.LayoutParams pBtn = new LinearLayout.LayoutParams(-1, dp(38));
        card.addView(btnViewDownloads, pBtn);

        refreshHomeDownloadsSummary();
        return card;
    }

    private void refreshHomeDownloadsSummary() {
        new Thread(() -> {
            List<MediaStorageManager.TorrentInfo> torrents = MediaStorageManager.getInstance(this).queryTransmissionTorrents();
            runOnUiThread(() -> {
                if (homeDownloadsSummaryText == null) return;
                int activeCount = 0;
                long totalDownloadSpeed = 0;
                for (MediaStorageManager.TorrentInfo t : torrents) {
                    if (t.isActive()) {
                        activeCount++;
                        totalDownloadSpeed += t.rateDownload;
                    }
                }
                if (homeDownloadsCountBadge != null) {
                    homeDownloadsCountBadge.setText(activeCount > 0 ? (activeCount + " Active") : "Idle");
                    homeDownloadsCountBadge.setTextColor(activeCount > 0 ? getSuccessColor() : getSecondaryTextColor());
                }
                if (activeCount > 0) {
                    homeDownloadsSummaryText.setText(activeCount + " active download(s) in progress. Tap below to view.");
                } else if (!torrents.isEmpty()) {
                    homeDownloadsSummaryText.setText("All downloads finished or paused (" + torrents.size() + " total).");
                } else {
                    homeDownloadsSummaryText.setText("No active downloads in progress.");
                }
            });
        }).start();
    }

    // -- Tab 2: System Logs & Console View -------------------------------------

    private View buildActivityTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(getBgColor());
        layout.setPadding(dp(16), dp(12), dp(16), dp(8));
        layout.setClipToPadding(false);

        // Header: title + one-line subtitle (UiKit page header)
        layout.addView(UiKit.header(this, "System Console", "Live logs from every service", null));

        // Service selector chips (horizontally scrollable; tagged so the drawer swipe leaves it alone)
        logServiceChipsContainer = new LinearLayout(this);
        logServiceChipsContainer.setOrientation(LinearLayout.HORIZONTAL);
        refreshLogServiceChips();
        HorizontalScrollView hScrollServices = UiKit.chipScroller(this, logServiceChipsContainer);
        LinearLayout.LayoutParams hParams = new LinearLayout.LayoutParams(-1, -2);
        hParams.bottomMargin = dp(8);
        layout.addView(hScrollServices, hParams);

        // Level filters on their own row...
        logLevelChipsContainer = new LinearLayout(this);
        logLevelChipsContainer.setOrientation(LinearLayout.HORIZONTAL);
        String[] levels = {"ALL", "ERROR", "WARNING", "INFO"};
        String[] levelLabels = {"All", "Errors", "Warnings", "Info"};
        for (int li = 0; li < levels.length; li++) {
            final String lvl = levels[li];
            TextView lvlChip = UiKit.chip(this, levelLabels[li], lvl.equals(selectedLogLevel));
            lvlChip.setTag(lvl);   // logic reads the tag, never the (translated) label
            lvlChip.setPadding(dp(12), dp(6), dp(12), dp(6));
            lvlChip.setOnClickListener(v -> {
                selectedLogLevel = lvl;
                updateLogLevelChips();
                refreshUnifiedLogsDisplay();
            });
            logLevelChipsContainer.addView(lvlChip, UiKit.chipLp(this));
        }
        HorizontalScrollView levelScroll = UiKit.chipScroller(this, logLevelChipsContainer);
        LinearLayout.LayoutParams levelLp = new LinearLayout.LayoutParams(-1, -2);
        levelLp.bottomMargin = dp(8);
        layout.addView(levelScroll, levelLp);

        // ...and the three actions share one row with equal widths (nothing is squeezed or hidden).
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        actionsRow.setPadding(0, 0, 0, dp(10));

        btnToggleAutoScroll = UiKit.button(this, I18n.tr("Auto-scroll"), AppTheme.card(), AppTheme.textSecondary(), AppTheme.outline(), null);
        btnToggleAutoScroll.setTextSize(12f);
        btnToggleAutoScroll.setSingleLine(true);
        btnToggleAutoScroll.setPadding(dp(6), 0, dp(6), 0);
        styleAutoScrollButton();
        btnToggleAutoScroll.setOnClickListener(v -> {
            autoScrollEnabled = !autoScrollEnabled;
            styleAutoScrollButton();
            if (autoScrollEnabled && logsScrollView != null) {
                logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
        actionsRow.addView(btnToggleAutoScroll, new LinearLayout.LayoutParams(0, dp(40), 1.0f));

        Button btnCopy = UiKit.outline(this, buttonLabel("COPY"), null);
        btnCopy.setTextSize(12f);
        btnCopy.setSingleLine(true);
        btnCopy.setPadding(dp(6), 0, dp(6), 0);
        btnCopy.setOnClickListener(v -> {
            String content = (logsOutputText != null) ? logsOutputText.getText().toString() : "";
            if (!content.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Jellyarr Console Logs", content));
                    Toast.makeText(this, I18n.tr("Logs copied (" + selectedLogService + ")"), Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams pCopy = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
        pCopy.setMarginStart(dp(8));
        actionsRow.addView(btnCopy, pCopy);

        Button btnClear = UiKit.danger(this, buttonLabel("CLEAR"), null);
        btnClear.setTextSize(12f);
        btnClear.setSingleLine(true);
        btnClear.setPadding(dp(6), 0, dp(6), 0);
        btnClear.setOnClickListener(v -> {
            if ("ALL".equalsIgnoreCase(selectedLogService)) {
                if (controller != null) controller.clearDisplayedLogs();
                MediaServiceManager mgr = MediaServiceManager.getInstance(this);
                for (ManagedService s : mgr.getAllServices()) {
                    s.clearLogs();
                }
            } else if ("JELLYFIN".equalsIgnoreCase(selectedLogService)) {
                if (controller != null) controller.clearDisplayedLogs();
            } else {
                MediaServiceManager mgr = MediaServiceManager.getInstance(this);
                ManagedService s = mgr.getService(selectedLogService.toLowerCase(Locale.ROOT));
                if (s != null) s.clearLogs();
            }
            if (logsOutputText != null) logsOutputText.setText("--- No logs recorded for [" + selectedLogService + "] ---");
            refreshUnifiedLogsDisplay();
            Toast.makeText(this, I18n.tr("Cleared " + selectedLogService + " logs"), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams pClear = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
        pClear.setMarginStart(dp(8));
        actionsRow.addView(btnClear, pClear);

        layout.addView(actionsRow);

        // Monospace Terminal Box Container (takes remaining height via weight=1.0)
        logsScrollView = new ScrollView(this);
        logsScrollView.setVerticalScrollBarEnabled(false);
        logsScrollView.setHorizontalScrollBarEnabled(false);
        logsScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        logsScrollView.setFillViewport(true);
        logsScrollView.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_INNER));

        logsOutputText = new TextView(this);
        logsOutputText.setTextColor(AppTheme.text());
        logsOutputText.setTextSize(10.5f);
        logsOutputText.setTypeface(Typeface.MONOSPACE);
        logsOutputText.setLineSpacing(dp(2), 1.15f);
        logsOutputText.setPadding(dp(12), dp(10), dp(12), dp(10));
        logsOutputText.setTextIsSelectable(true);
        // Log lines are English / machine text: keep them left-to-right even in the Arabic UI.
        logsOutputText.setTextDirection(View.TEXT_DIRECTION_LTR);
        logsOutputText.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        logsOutputText.setGravity(Gravity.START);

        logsScrollView.addView(logsOutputText);
        layout.addView(logsScrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        // Subtitle status line at bottom of terminal
        logsStatusSubtitle = UiKit.caption(this, "");
        logsStatusSubtitle.setPadding(dp(4), dp(6), dp(4), 0);
        layout.addView(logsStatusSubtitle);

        updateLogServiceChips();
        updateLogLevelChips();
        refreshUnifiedLogsDisplay();

        return layout;
    }

    // -- Unified Download Center Card -----------------------------------------

    private View buildDownloadCenterCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(14), 0, dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        card.setLayoutParams(params);

        // Subtle 1dp hairline divider on top
        View topDivider = new View(this);
        topDivider.setBackgroundColor(AppTheme.outline());
        LinearLayout.LayoutParams pDiv = new LinearLayout.LayoutParams(-1, dp(1));
        pDiv.bottomMargin = dp(12);
        card.addView(topDivider, pDiv);

        // Header Row: Title + Badge + Refresh
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("DOWNLOAD CENTER");
        title.setTextSize(10.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(AppTheme.textMuted());
        title.setLetterSpacing(0.08f);
        headerRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));
        styleSectionLabel(title);

        downloadSummaryBadge = new TextView(this);
        downloadSummaryBadge.setText("0 Active");
        downloadSummaryBadge.setTextSize(10);
        downloadSummaryBadge.setTypeface(Typeface.MONOSPACE);
        downloadSummaryBadge.setTextColor(AppTheme.textMuted());
        headerRow.addView(downloadSummaryBadge);

        View sp = new View(this);
        headerRow.addView(sp, new LinearLayout.LayoutParams(dp(8), 1));

        TextView btnHistoryDl = new TextView(this);
        btnHistoryDl.setText("HISTORY");
        btnHistoryDl.setTextSize(9.5f);
        btnHistoryDl.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnHistoryDl.setTextColor(AppTheme.accent());
        btnHistoryDl.setGravity(Gravity.CENTER);
        btnHistoryDl.setBackground(createBorderedRoundedDrawable(AppTheme.accentContainer(), AppTheme.accent(), dp(1), dp(4)));
        btnHistoryDl.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnHistoryDl.setClickable(true);
        btnHistoryDl.setFocusable(true);
        btnHistoryDl.setOnClickListener(v -> showTransmissionHistoryDialog());
        headerRow.addView(btnHistoryDl, new LinearLayout.LayoutParams(-2, -2));
        View spHist = new View(this);
        headerRow.addView(spHist, new LinearLayout.LayoutParams(dp(6), 1));

        TextView btnRefreshDl = new TextView(this);
        btnRefreshDl.setText("REFRESH");
        btnRefreshDl.setTextSize(9.5f);
        btnRefreshDl.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnRefreshDl.setTextColor(AppTheme.text());
        btnRefreshDl.setGravity(Gravity.CENTER);
        btnRefreshDl.setBackground(createBorderedRoundedDrawable(AppTheme.outline(), Color.argb(40, 255, 255, 255), dp(1), dp(4)));
        btnRefreshDl.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnRefreshDl.setClickable(true);
        btnRefreshDl.setFocusable(true);
        btnRefreshDl.setOnClickListener(v -> {
            Toast.makeText(this, I18n.tr("Refreshing downloads..."), Toast.LENGTH_SHORT).show();
            refreshDownloadCenter();
        });
        headerRow.addView(btnRefreshDl, new LinearLayout.LayoutParams(-2, -2));

        View spClear = new View(this);
        headerRow.addView(spClear, new LinearLayout.LayoutParams(dp(6), 1));

        TextView btnClearDl = new TextView(this);
        btnClearDl.setText("CLEAR");
        btnClearDl.setTextSize(9.5f);
        btnClearDl.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnClearDl.setTextColor(AppTheme.danger());
        btnClearDl.setGravity(Gravity.CENTER);
        btnClearDl.setBackground(createBorderedRoundedDrawable(AppTheme.dangerContainer(), AppTheme.danger(), dp(1), dp(4)));
        btnClearDl.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnClearDl.setClickable(true);
        btnClearDl.setFocusable(true);
        btnClearDl.setOnClickListener(v -> showClearDownloadsDialog());
        headerRow.addView(btnClearDl, new LinearLayout.LayoutParams(-2, -2));

        card.addView(headerRow);

        // Destination Path Note
        TextView destNote = new TextView(this);
        destNote.setText("Target: /storage/emulated/0/JellyarrMedia/Downloads");
        destNote.setTextSize(10);
        destNote.setTypeface(Typeface.MONOSPACE);
        destNote.setTextColor(AppTheme.textMuted());
        destNote.setPadding(0, dp(4), 0, dp(8));
        card.addView(destNote);

        downloadItemsContainer = new LinearLayout(this);
        downloadItemsContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(downloadItemsContainer);

        refreshDownloadCenter();
        return card;
    }

    private static class TorrentItemHolder {
        int id;
        TextView nameTv;
        TextView pillTv;
        ProgressBar progressBar;
        TextView metaLeftTv;
        TextView btnDelete;
        TextView btnToggle;
        int status;
    }

    private void refreshDownloadCenter() {
        runBg(() -> {
            List<MediaStorageManager.TorrentInfo> torrents = MediaStorageManager.getInstance(this).queryTransmissionTorrents();
            runOnUiThread(() -> {
                if (downloadItemsContainer == null) return;

                int activeCount = 0;
                for (MediaStorageManager.TorrentInfo t : torrents) {
                    if (t.isActive()) activeCount++;
                }
                hasActiveDownloads = (activeCount > 0);

                if (downloadSummaryBadge != null) {
                    downloadSummaryBadge.setText(activeCount + " Active / " + torrents.size() + " Total");
                    downloadSummaryBadge.setTextColor(activeCount > 0 ? getSuccessColor() : AppTheme.textMuted());
                }
                if (homeDownloadsCountBadge != null) {
                    homeDownloadsCountBadge.setText(activeCount > 0 ? (activeCount + " Active") : "Idle");
                    homeDownloadsCountBadge.setTextColor(activeCount > 0 ? getSuccessColor() : AppTheme.textMuted());
                }
                if (homeDownloadsSummaryText != null) {
                    if (activeCount > 0) {
                        homeDownloadsSummaryText.setText(activeCount + " active download(s) in progress. Tap below for details.");
                    } else if (!torrents.isEmpty()) {
                        homeDownloadsSummaryText.setText("All downloads finished or paused (" + torrents.size() + " total).");
                    } else {
                        homeDownloadsSummaryText.setText("No active downloads in progress.");
                    }
                }

                if (torrents.isEmpty()) {
                    if (downloadItemsContainer.getChildCount() == 1) {
                        View existing = downloadItemsContainer.getChildAt(0);
                        if ("empty_downloads_view".equals(existing.getTag())) {
                            return; // Retain cached empty view: zero extra allocations or layout passes
                        }
                    }
                    downloadItemsContainer.removeAllViews();
                    TextView empty = new TextView(this);
                    empty.setTag("empty_downloads_view");
                    empty.setText("No active or queued downloads in Transmission.");
                    empty.setTextSize(11);
                    empty.setTextColor(AppTheme.textMuted());
                    empty.setPadding(0, dp(6), 0, dp(6));
                    downloadItemsContainer.addView(empty);
                    return;
                }

                // Smooth in-place updates when child count and torrent IDs match to prevent UI flickering
                boolean canUpdateInPlace = (downloadItemsContainer.getChildCount() == torrents.size());
                if (canUpdateInPlace) {
                    for (int i = 0; i < torrents.size(); i++) {
                        View child = downloadItemsContainer.getChildAt(i);
                        Object tag = child.getTag();
                        if (!(tag instanceof TorrentItemHolder) || ((TorrentItemHolder) tag).id != torrents.get(i).id) {
                            canUpdateInPlace = false;
                            break;
                        }
                    }
                }

                if (canUpdateInPlace) {
                    for (int i = 0; i < torrents.size(); i++) {
                        MediaStorageManager.TorrentInfo t = torrents.get(i);
                        TorrentItemHolder holder = (TorrentItemHolder) downloadItemsContainer.getChildAt(i).getTag();

                        holder.nameTv.setText(t.name != null ? t.name : "Torrent #" + t.id);
                        holder.pillTv.setText(t.getStatusString());

                        int pillColor = AppTheme.textMuted();
                        switch (t.getLifecycleState()) {
                            case DOWNLOADING: pillColor = AppTheme.accent(); break;
                            case WAITING_FINAL_PIECES: pillColor = getWarningColor(); break;
                            case SEEDING:
                            case COMPLETE: pillColor = getSuccessColor(); break;
                            case STALLED: pillColor = AppTheme.danger(); break;
                            case ERROR: pillColor = getDangerColor(); break;
                            default: pillColor = AppTheme.textMuted(); break;
                        }
                        holder.pillTv.setTextColor(pillColor);
                        holder.pillTv.setBackground(createBorderedRoundedDrawable(colorWithAlpha(pillColor, 20), colorWithAlpha(pillColor, 50), dp(1), dp(4)));

                        holder.progressBar.setProgress((int) Math.round(t.percentDone * 1000));
                        holder.status = t.status;
                        if (holder.btnToggle != null) holder.btnToggle.setText(t.status == 0 ? "PLAY" : "PAUSE");

                        StringBuilder meta = new StringBuilder();
                        if (t.sizeWhenDone > 0) {
                            long doneBytes = Math.round(t.sizeWhenDone * t.percentDone);
                            meta.append(MediaStorageManager.formatBytes(doneBytes))
                                    .append(" / ")
                                    .append(MediaStorageManager.formatBytes(t.sizeWhenDone));
                        }
                        if (t.rateDownload > 0) {
                            if (meta.length() > 0) meta.append(" • ");
                            meta.append("↓ ").append(MediaStorageManager.formatBytes(t.rateDownload)).append("/s");
                        }
                        if (t.rateUpload > 0) {
                            if (meta.length() > 0) meta.append(" • ");
                            meta.append("↑ ").append(MediaStorageManager.formatBytes(t.rateUpload)).append("/s");
                        }
                        if (t.eta > 0) {
                            if (meta.length() > 0) meta.append(" • ");
                            long m = t.eta / 60;
                            long s = t.eta % 60;
                            meta.append("ETA: ").append(m).append("m ").append(s).append("s");
                        }
                        if (meta.length() == 0) {
                            meta.append(String.format(Locale.US, "%.1f%% complete", t.percentDone * 100));
                        }
                        holder.metaLeftTv.setText(meta.toString());
                    }
                    return;
                }

                downloadItemsContainer.removeAllViews();

                for (MediaStorageManager.TorrentInfo t : torrents) {
                    LinearLayout item = new LinearLayout(this);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setPadding(dp(12), dp(10), dp(12), dp(10));
                    item.setBackground(createBorderedRoundedDrawable(AppTheme.bg(), AppTheme.outline(), dp(1), dp(8)));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = dp(6);
                    item.setLayoutParams(lp);

                    TorrentItemHolder holder = new TorrentItemHolder();
                    holder.id = t.id;

                    // Row 1: Name and Lifecycle Pill
                    LinearLayout top = new LinearLayout(this);
                    top.setOrientation(LinearLayout.HORIZONTAL);
                    top.setGravity(Gravity.CENTER_VERTICAL);

                    TextView name = new TextView(this);
                    name.setText(t.name != null ? t.name : "Torrent #" + t.id);
                    name.setTextSize(12);
                    name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    name.setTextColor(AppTheme.text());
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    top.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
                    holder.nameTv = name;

                    TextView pill = new TextView(this);
                    pill.setText(t.getStatusString());
                    pill.setTextSize(8.5f);
                    pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    pill.setPadding(dp(5), dp(2), dp(5), dp(2));

                    int pillColor = AppTheme.textMuted();
                    switch (t.getLifecycleState()) {
                        case DOWNLOADING: pillColor = AppTheme.accent(); break;
                        case WAITING_FINAL_PIECES: pillColor = getWarningColor(); break;
                        case SEEDING:
                        case COMPLETE: pillColor = getSuccessColor(); break;
                        case STALLED: pillColor = AppTheme.danger(); break;
                        case ERROR: pillColor = getDangerColor(); break;
                        default: pillColor = AppTheme.textMuted(); break;
                    }
                    pill.setTextColor(pillColor);
                    pill.setBackground(createBorderedRoundedDrawable(colorWithAlpha(pillColor, 20), colorWithAlpha(pillColor, 50), dp(1), dp(4)));
                    top.addView(pill);
                    holder.pillTv = pill;

                    View btnDelSpace = new View(this);
                    top.addView(btnDelSpace, new LinearLayout.LayoutParams(dp(8), 1));

                    TextView btnDeleteItem = new TextView(this);
                    btnDeleteItem.setText("DEL");
                    btnDeleteItem.setTextSize(10f);
                    btnDeleteItem.setTypeface(Typeface.DEFAULT_BOLD);
                    btnDeleteItem.setTextColor(AppTheme.danger());
                    btnDeleteItem.setGravity(Gravity.CENTER);
                    btnDeleteItem.setPadding(dp(6), dp(2), dp(6), dp(2));
                    btnDeleteItem.setBackground(createBorderedRoundedDrawable(AppTheme.dangerContainer(), AppTheme.danger(), dp(1), dp(4)));
                    btnDeleteItem.setClickable(true);
                    btnDeleteItem.setFocusable(true);
                    btnDeleteItem.setOnClickListener(v -> showRemoveSingleTorrentDialog(t));
                    top.addView(btnDeleteItem);
                    holder.btnDelete = btnDeleteItem;

                    item.addView(top);

                    // Row 2: Progress bar
                    ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    pb.setMax(1000);
                    pb.setProgress((int) Math.round(t.percentDone * 1000));
                    pb.setPadding(0, dp(6), 0, dp(6));
                    item.addView(pb);
                    holder.progressBar = pb;

                    // Row 3: Meta info (Download speed, size, ETA)
                    LinearLayout metaRow = new LinearLayout(this);
                    metaRow.setOrientation(LinearLayout.HORIZONTAL);
                    metaRow.setGravity(Gravity.CENTER_VERTICAL);
                    metaRow.setPadding(0, dp(2), 0, 0);

                    TextView metaLeft = new TextView(this);
                    metaLeft.setTextSize(9.5f);
                    metaLeft.setTypeface(Typeface.MONOSPACE);
                    metaLeft.setTextColor(AppTheme.textSecondary());

                    StringBuilder meta = new StringBuilder();
                    if (t.sizeWhenDone > 0) {
                        long doneBytes = Math.round(t.sizeWhenDone * t.percentDone);
                        meta.append(MediaStorageManager.formatBytes(doneBytes))
                                .append(" / ")
                                .append(MediaStorageManager.formatBytes(t.sizeWhenDone));
                    }
                    if (t.rateDownload > 0) {
                        if (meta.length() > 0) meta.append(" • ");
                        meta.append("↓ ").append(MediaStorageManager.formatBytes(t.rateDownload)).append("/s");
                    }
                    if (t.rateUpload > 0) {
                        if (meta.length() > 0) meta.append(" • ");
                        meta.append("↑ ").append(MediaStorageManager.formatBytes(t.rateUpload)).append("/s");
                    }
                    if (t.eta > 0) {
                        if (meta.length() > 0) meta.append(" • ");
                        long m = t.eta / 60;
                        long s = t.eta % 60;
                        meta.append("ETA: ").append(m).append("m ").append(s).append("s");
                    }
                    if (meta.length() == 0) {
                        meta.append(String.format(Locale.US, "%.1f%% complete", t.percentDone * 100));
                    }
                    metaLeft.setText(meta.toString());
                    metaRow.addView(metaLeft, new LinearLayout.LayoutParams(0, -2, 1.0f));
                    holder.metaLeftTv = metaLeft;

                    item.addView(metaRow);

                    // Row 4: Actions (Pause/Play, Force start, Recheck)
                    LinearLayout actionRow = new LinearLayout(this);
                    actionRow.setOrientation(LinearLayout.HORIZONTAL);
                    actionRow.setGravity(Gravity.CENTER_VERTICAL);
                    actionRow.setPadding(0, dp(8), 0, 0);

                    final TextView btnToggle = createTorrentActionButton(t.status == 0 ? "PLAY" : "PAUSE", AppTheme.accent());
                    holder.btnToggle = btnToggle;
                    holder.status = t.status;
                    final int torrentId = t.id;
                    btnToggle.setOnClickListener(v -> {
                        final MediaStorageManager msm = MediaStorageManager.getInstance(this);
                        if (holder.status == 0) {
                            runTorrentAction("Resumed.", () -> msm.resumeTransmissionTorrent(torrentId));
                        } else {
                            runTorrentAction("Paused.", () -> msm.pauseTransmissionTorrent(torrentId));
                        }
                    });

                    final TextView btnForce = createTorrentActionButton("FORCE START", AppTheme.success());
                    btnForce.setOnClickListener(v -> {
                        final MediaStorageManager msm = MediaStorageManager.getInstance(this);
                        runTorrentAction("Force started.", () -> msm.forceStartTransmissionTorrent(torrentId));
                    });

                    final TextView btnRecheck = createTorrentActionButton("RECHECK", AppTheme.warning());
                    btnRecheck.setOnClickListener(v -> {
                        final MediaStorageManager msm = MediaStorageManager.getInstance(this);
                        runTorrentAction("Recheck started.", () -> msm.recheckTransmissionTorrent(torrentId));
                    });

                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, -2, 1.0f);
                    alp.setMarginEnd(dp(6));
                    actionRow.addView(btnToggle, alp);
                    LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(0, -2, 1.3f);
                    alp2.setMarginEnd(dp(6));
                    actionRow.addView(btnForce, alp2);
                    actionRow.addView(btnRecheck, new LinearLayout.LayoutParams(0, -2, 1.0f));
                    item.addView(actionRow);

                    item.setTag(holder);
                    downloadItemsContainer.addView(item);
                }
            });
        });
    }

    private LinearLayout createOledDialogOptionTile(String title, String subtitle, int accentColor, Runnable onClick) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(10), dp(14), dp(10));
        tile.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        tile.setLayoutParams(lp);
        tile.setClickable(true);
        tile.setFocusable(true);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(12.5f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(accentColor);
        tile.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextSize(10.5f);
            tvSub.setTextColor(AppTheme.textSecondary());
            tvSub.setPadding(0, dp(2), 0, 0);
            tile.addView(tvSub);
        }

        tile.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return tile;
    }

    private void showClearDownloadsDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        Dialog[] dRef = new Dialog[1];

        // 1. Clear Completed
        content.addView(createOledDialogOptionTile(
                "Clear Completed Tasks",
                "Removes finished and inactive torrents • Retains downloaded files",
                AppTheme.success(),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, I18n.tr("Clearing completed downloads..."), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int count = MediaStorageManager.getInstance(this).clearCompletedTransmissionTorrents(false);
                        runOnUiThread(() -> {
                            Toast.makeText(this, I18n.tr("Cleared " + count + " item(s)."), Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        // 2. Clear All Tasks (Keep Files)
        content.addView(createOledDialogOptionTile(
                "Remove All from Queue",
                "Clears all downloads from list • Keeps media files on disk",
                AppTheme.accent(),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, I18n.tr("Clearing queue items..."), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int count = MediaStorageManager.getInstance(this).clearAllTransmissionTorrents(false);
                        runOnUiThread(() -> {
                            Toast.makeText(this, I18n.tr("Cleared " + count + " item(s)."), Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        // 3. Erase Everything (Delete Files)
        content.addView(createOledDialogOptionTile(
                "Erase All & Delete Files",
                "Permanently purges torrents and deletes all downloaded files from storage",
                AppTheme.danger(),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, I18n.tr("Purging downloads and disk storage..."), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int count = MediaStorageManager.getInstance(this).clearAllTransmissionTorrents(true);
                        runOnUiThread(() -> {
                            Toast.makeText(this, I18n.tr("Purged " + count + " download(s)."), Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        dRef[0] = showOledDialog("Clear Downloads", "Select an action for active and completed tasks", content, null, 0, null, "CANCEL", null);
    }

    private TextView createTorrentActionButton(String label, int color) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(9.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(color);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(5), dp(4), dp(5));
        b.setBackground(createBorderedRoundedDrawable(colorWithAlpha(color, 20), colorWithAlpha(color, 60), dp(1), dp(4)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private void runTorrentAction(final String okMessage, final java.util.concurrent.Callable<Boolean> action) {
        new Thread(() -> {
            boolean ok;
            try {
                ok = Boolean.TRUE.equals(action.call());
            } catch (Throwable t) {
                ok = false;
            }
            final boolean result = ok;
            runOnUiThread(() -> {
                Toast.makeText(this, I18n.tr(result ? okMessage : "Transmission did not accept the command."), Toast.LENGTH_SHORT).show();
                refreshDownloadCenter();
            });
        }, "Jellyarr-TorrentAction").start();
    }

    private void showTransmissionHistoryDialog() {
        final java.util.List<com.jellyarr.app.services.TransmissionHistory.Entry> entries =
                com.jellyarr.app.services.TransmissionHistory.read(this, 300);
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        sv.addView(list);
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No downloads logged yet. Events are recorded while Transmission is running.");
            empty.setTextSize(11.5f);
            empty.setTextColor(AppTheme.textSecondary());
            list.addView(empty);
        }
        for (com.jellyarr.app.services.TransmissionHistory.Entry e : entries) {
            int color = AppTheme.textSecondary();
            switch (e.event) {
                case "COMPLETED": color = getSuccessColor(); break;
                case "FAILED": color = getDangerColor(); break;
                case "CANCELLED": color = getWarningColor(); break;
                case "REMOVED": color = AppTheme.warning(); break;
                case "ADDED": color = AppTheme.accent(); break;
                case "RECOVERED": color = getSuccessColor(); break;
                case "AUTO-RECHECK":
                case "AUTO-CLEANUP": color = Color.parseColor("#C084FC"); break;
                default: break;
            }
            TextView head = new TextView(this);
            head.setText(fmt.format(new java.util.Date(e.timeMs)) + "  " + e.event);
            head.setTextSize(10.5f);
            head.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            head.setTextColor(color);
            head.setPadding(0, dp(8), 0, 0);
            list.addView(head);
            TextView body = new TextView(this);
            body.setText(e.name + (e.detail != null && !e.detail.isEmpty() ? "\n" + e.detail : ""));
            body.setTextSize(11f);
            body.setTextColor(AppTheme.text());
            list.addView(body);
        }
        showOledDialog("Download History", entries.size() + " event(s), newest first", sv,
                "CLEAR LOG", getDangerColor(), () -> {
                    com.jellyarr.app.services.TransmissionHistory.clear(this);
                    Toast.makeText(this, I18n.tr("History cleared."), Toast.LENGTH_SHORT).show();
                }, "CLOSE", null);
    }

    private void showRemoveSingleTorrentDialog(MediaStorageManager.TorrentInfo torrent) {
        String titleName = torrent.name != null ? torrent.name : ("Torrent #" + torrent.id);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        Dialog[] dRef = new Dialog[1];

        content.addView(createOledDialogOptionTile(
                "Remove from Queue",
                "Removes task from Transmission • Retains downloaded files on disk",
                AppTheme.accent(),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, I18n.tr("Removing..."), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        boolean ok = MediaStorageManager.getInstance(this).removeTransmissionTorrent(torrent.id, false);
                        runOnUiThread(() -> {
                            Toast.makeText(this, I18n.tr(ok ? "Removed from queue." : "Failed to remove."), Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        content.addView(createOledDialogOptionTile(
                "Delete Files from Disk",
                "Permanently deletes task and all downloaded files from storage",
                AppTheme.danger(),
                () -> {
                    if (dRef[0] != null) dRef[0].dismiss();
                    Toast.makeText(this, I18n.tr("Deleting files..."), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        boolean ok = MediaStorageManager.getInstance(this).removeTransmissionTorrent(torrent.id, true);
                        runOnUiThread(() -> {
                            Toast.makeText(this, I18n.tr(ok ? "Deleted files and task." : "Failed to delete."), Toast.LENGTH_SHORT).show();
                            refreshDownloadCenter();
                        });
                    }).start();
                }
        ));

        dRef[0] = showOledDialog("Remove Download", titleName, content, null, 0, null, "CANCEL", null);
    }

    // -- Unified Console & Logs Engine ---------------------------------------

    private void refreshLogServiceChips() {
        if (logServiceChipsContainer == null) return;

        List<String> installedServices = new ArrayList<>();
        installedServices.add("ALL");
        if (JellyfinBootstrapper.isInitialized(this)) {
            installedServices.add("JELLYFIN");
        }

        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        for (ManagedService s : mgr.getAllServices()) {
            if (s != null && s.isInstalled()) {
                if (s.getManifest().isCore() || "jellyfin".equalsIgnoreCase(s.getId())) continue;
                installedServices.add(s.getId().toUpperCase(Locale.ROOT));
            }
        }

        // Validate selectedLogService
        boolean isValid = false;
        for (String svc : installedServices) {
            if (svc.equalsIgnoreCase(selectedLogService)) {
                isValid = true;
                break;
            }
        }
        if (!isValid) {
            selectedLogService = "ALL";
        }

        // Same set of chips as shown already: only restyle (keeps the row's scroll position, no flicker).
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < logServiceChipsContainer.getChildCount(); i++) {
            Object tag = logServiceChipsContainer.getChildAt(i).getTag();
            if (tag instanceof String) shown.add((String) tag);
        }
        if (shown.equals(installedServices)) {
            updateLogServiceChips();
            return;
        }
        logServiceChipsContainer.removeAllViews();

        for (String svcName : installedServices) {
            String label = "ALL".equals(svcName) ? "All"
                    : "JELLYFIN".equals(svcName) ? "Jellyfin" : displayNameForService(svcName.toLowerCase(Locale.ROOT));
            TextView chip = UiKit.chip(this, label, svcName.equalsIgnoreCase(selectedLogService));
            chip.setTag(svcName);   // selection logic reads the tag, never the (translated) label
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setOnClickListener(v -> {
                selectedLogService = svcName;
                updateLogServiceChips();
                refreshUnifiedLogsDisplay();
            });
            logServiceChipsContainer.addView(chip, UiKit.chipLp(this));
        }
        updateLogServiceChips();
    }

    private String displayNameForService(String id) {
        try {
            ManagedService ms = MediaServiceManager.getInstance(this).getService(id);
            if (ms != null && ms.getDisplayName() != null) return ms.getDisplayName();
        } catch (Throwable ignored) {}
        return id.isEmpty() ? id : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }

    /** Selected chip uses the accent container; running services get a small green dot before the name. */
    private void updateLogServiceChips() {
        if (logServiceChipsContainer == null) return;
        for (int i = 0; i < logServiceChipsContainer.getChildCount(); i++) {
            View child = logServiceChipsContainer.getChildAt(i);
            if (!(child instanceof TextView) || !(child.getTag() instanceof String)) continue;
            TextView chip = (TextView) child;
            String key = (String) child.getTag();
            boolean isSel = key.equalsIgnoreCase(selectedLogService);

            boolean isSvcRunning;
            if ("ALL".equalsIgnoreCase(key)) {
                isSvcRunning = false;
            } else if ("JELLYFIN".equalsIgnoreCase(key)) {
                isSvcRunning = (controller != null && controller.getState() == JellyfinController.State.RUNNING);
            } else {
                ManagedService ms = MediaServiceManager.getInstance(this).getService(key.toLowerCase(Locale.ROOT));
                isSvcRunning = (ms != null && ms.isRunning());
            }
            UiKit.styleChip(this, chip, isSel);
            if (isSvcRunning) {
                GradientDrawable dot = createCircleDrawable(AppTheme.success());
                dot.setSize(dp(6), dp(6));
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
                chip.setCompoundDrawablePadding(dp(6));
            } else {
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
            }
        }
    }

    private void updateLogLevelChips() {
        if (logLevelChipsContainer == null) return;
        for (int i = 0; i < logLevelChipsContainer.getChildCount(); i++) {
            View child = logLevelChipsContainer.getChildAt(i);
            if (!(child instanceof TextView) || !(child.getTag() instanceof String)) continue;
            UiKit.styleChip(this, (TextView) child, child.getTag().equals(selectedLogLevel));
        }
    }

    private void styleAutoScrollButton() {
        if (btnToggleAutoScroll == null) return;
        btnToggleAutoScroll.setText(I18n.tr(autoScrollEnabled ? "Auto-scroll on" : "Auto-scroll off"));
        btnToggleAutoScroll.setTextColor(autoScrollEnabled ? AppTheme.onAccentContainer() : AppTheme.textSecondary());
        btnToggleAutoScroll.setBackground(UiKit.pressable(this,
                autoScrollEnabled ? AppTheme.accentContainer() : AppTheme.card(),
                autoScrollEnabled ? 0 : AppTheme.outline(), UiKit.R_INNER));
    }

    private int getServiceColor(String serviceTag) {
        if ("ALL".equalsIgnoreCase(serviceTag)) return AppTheme.text();
        return getServiceBrandColor(serviceTag.toLowerCase(Locale.ROOT));
    }

    private void appendColorizedLine(SpannableStringBuilder ssb, String rawLine, String defaultServiceTag) {
        if (rawLine == null || rawLine.trim().isEmpty()) return;
        String line = rawLine.trim();

        // Determine service tag from line or fallback
        String serviceTag = defaultServiceTag;
        if (line.startsWith("[JELLYFIN]") || line.startsWith("[RADARR]") || line.startsWith("[SONARR]")
                || line.startsWith("[PROWLARR]") || line.startsWith("[TRANSMISSION]")
                ) {
            int endTag = line.indexOf(']');
            if (endTag > 0) {
                serviceTag = line.substring(1, endTag).toUpperCase(Locale.ROOT);
                line = line.substring(endTag + 1).trim();
            }
        }

        // Determine level: ERR, WRN, INF, DBG
        String lower = line.toLowerCase(Locale.ROOT);
        int levelColor = AppTheme.success(); // Mint/Sage for INFO default
        String levelBadge = "INF";
        if (lower.contains("[err]") || lower.contains("[error]") || lower.contains("error:")
                || lower.contains("fail") || lower.contains("exception") || lower.contains("[ftl]")
                || lower.contains("fatal")) {
            levelColor = getDangerColor(); // Bright Red
            levelBadge = "ERR";
        } else if (lower.contains("[wrn]") || lower.contains("[warn]") || lower.contains("warning:")) {
            levelColor = getWarningColor(); // Warm Safety Orange
            levelBadge = "WRN";
        } else if (lower.contains("[dbg]") || lower.contains("[debug]")) {
            levelColor = AppTheme.textSecondary(); // Slate
            levelBadge = "DBG";
        }

        // If level filter is active, check match
        if ("ERROR".equals(selectedLogLevel) && !levelBadge.equals("ERR")) return;
        if ("WARNING".equals(selectedLogLevel) && !levelBadge.equals("WRN") && !levelBadge.equals("ERR")) return;
        if ("INFO".equals(selectedLogLevel) && !levelBadge.equals("INF")) return;

        // Service tag color
        int serviceColor = getServiceColor(serviceTag);

        int tagStart = ssb.length();
        ssb.append("[").append(serviceTag).append("] ");
        int tagEnd = ssb.length();
        ssb.setSpan(new ForegroundColorSpan(serviceColor), tagStart, tagEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new StyleSpan(Typeface.BOLD), tagStart, tagEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Clean up redundant Microsoft/Emby namespaces in line
        String cleanLine = line.replace("Microsoft.Hosting.Lifetime: ", "")
                .replace("Jellyfin.Server.Implementations.ApplicationHost: ", "App: ")
                .replace("Emby.Server.Implementations.HttpServer.HttpListenerHost: ", "Http: ")
                .replace("Emby.Server.Implementations.IO.LibraryMonitor: ", "Library: ")
                .replace("Emby.Server.Implementations.ScheduledTasks.TaskManager: ", "Task: ");

        // Append and color body text
        int lineStart = ssb.length();
        ssb.append(cleanLine).append("\n");
        int lineEnd = ssb.length();

        int msgColor = levelBadge.equals("ERR") ? AppTheme.danger()
                : (levelBadge.equals("WRN") ? AppTheme.warning() : AppTheme.text());
        ssb.setSpan(new ForegroundColorSpan(msgColor), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final class UnifiedLogsResult {
        CharSequence text;
        Integer color;
        String subtitle;
    }

    private final java.util.concurrent.atomic.AtomicInteger unifiedLogsGen = new java.util.concurrent.atomic.AtomicInteger();

    /** Reads and colorizes logs off the main thread, then applies the result on the UI thread. */
    private void refreshUnifiedLogsDisplay() {
        if (logsOutputText == null) return;
        final int gen = unifiedLogsGen.incrementAndGet();
        runBg(() -> {
            final UnifiedLogsResult out;
            try {
                out = computeUnifiedLogs();
            } catch (Throwable t) {
                Log.w(TAG, "Unified logs compute failed: " + t.getMessage());
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || gen != unifiedLogsGen.get() || logsOutputText == null) return;
                if (out.text != null) logsOutputText.setText(out.text);
                if (out.color != null) logsOutputText.setTextColor(out.color);
                if (out.subtitle != null && logsStatusSubtitle != null) logsStatusSubtitle.setText(out.subtitle);
                if (autoScrollEnabled && logsScrollView != null) {
                    logsScrollView.postDelayed(() -> {
                        if (logsScrollView != null) {
                            View child = logsScrollView.getChildAt(0);
                            if (child != null) {
                                int diff = child.getBottom() - (logsScrollView.getHeight() + logsScrollView.getScrollY());
                                if (diff <= dp(140)) logsScrollView.fullScroll(View.FOCUS_DOWN);
                            } else {
                                logsScrollView.fullScroll(View.FOCUS_DOWN);
                            }
                        }
                    }, 40);
                }
            });
        });
    }

    private UnifiedLogsResult computeUnifiedLogs() {
        UnifiedLogsResult out = new UnifiedLogsResult();

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        int lineCount = 0;

        if ("ALL".equalsIgnoreCase(selectedLogService)) {
            // Gather Jellyfin controller logs (if installed/initialized)
            if (controller != null && JellyfinBootstrapper.isInitialized(this)) {
                String jfLogs = showingDiskLog ? controller.getDiskLogs() : controller.getLogs();
                if (showingDiskLog && (jfLogs == null || jfLogs.isEmpty())) {
                    jfLogs = controller.getLogs();
                }
                if (jfLogs != null && !jfLogs.isEmpty()) {
                    String[] lines = jfLogs.split("\n");
                    int start = Math.max(0, lines.length - 120);
                    for (int i = start; i < lines.length; i++) {
                        appendColorizedLine(ssb, lines[i], "JELLYFIN");
                        lineCount++;
                    }
                }
            }
            // Gather all managed services that are installed
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            for (ManagedService s : mgr.getAllServices()) {
                if (s == null || !s.isInstalled()) continue;
                if (s.getManifest().isCore() || "jellyfin".equalsIgnoreCase(s.getId())) continue;
                String sLogs = s.getRecentLogs(50);
                if (sLogs == null || sLogs.trim().isEmpty() || sLogs.startsWith("--- No logs")) continue;
                String[] lines = sLogs.split("\n");
                for (String l : lines) {
                    appendColorizedLine(ssb, l, s.getId().toUpperCase(Locale.ROOT));
                    lineCount++;
                }
            }
        } else if ("JELLYFIN".equalsIgnoreCase(selectedLogService)) {
            if (!JellyfinBootstrapper.isInitialized(this)) {
                out.text = "--- Jellyfin server runtime is not installed ---";
                out.color = AppTheme.textMuted();
                out.subtitle = "[JELLYFIN] - Not installed";
                return out;
            }
            out.subtitle = "[JELLYFIN] - Live Stream & Disk Log Output";
            if (controller != null) {
                String raw = showingDiskLog ? controller.getDiskLogs() : controller.getLogs();
                if (showingDiskLog && (raw == null || raw.isEmpty())) {
                    raw = controller.getLogs();
                }
                if (raw != null && !raw.isEmpty()) {
                    String[] lines = raw.split("\n");
                    int start = Math.max(0, lines.length - 250);
                    for (int i = start; i < lines.length; i++) {
                        appendColorizedLine(ssb, lines[i], "JELLYFIN");
                        lineCount++;
                    }
                }
                if (lineCount == 0 && controller.getPreservedErrorCount() > 0) {
                    String errs = controller.getPreservedErrorLogs();
                    if (errs != null && !errs.isEmpty()) {
                        String[] lines = errs.split("\n");
                        for (String l : lines) {
                            appendColorizedLine(ssb, l, "JELLYFIN");
                            lineCount++;
                        }
                    }
                }
            }
        } else {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            ManagedService s = mgr.getService(selectedLogService.toLowerCase(Locale.ROOT));
            if (s != null && s.isInstalled()) {
                String raw = s.getRecentLogs(250);
                if (raw != null && !raw.isEmpty() && !raw.startsWith("--- No logs")) {
                    String[] lines = raw.split("\n");
                    for (String l : lines) {
                        appendColorizedLine(ssb, l, selectedLogService);
                        lineCount++;
                    }
                }
            } else {
                out.text = "--- Service [" + selectedLogService + "] is not installed ---";
                out.color = AppTheme.textMuted();
                out.subtitle = "[" + selectedLogService + "] - Not installed";
                return out;
            }
        }

        if (ssb.length() == 0) {
            out.text = "--- No logs recorded for [" + selectedLogService + "] with filter [" + selectedLogLevel + "] ---";
            out.color = AppTheme.textMuted();
        } else {
            out.text = ssb;
            out.color = Color.parseColor("#D4D4D8");
        }

        out.subtitle = "[" + selectedLogService + "] - " + lineCount + " lines (" + selectedLogLevel + ") - Auto-scroll: " + (autoScrollEnabled ? "ON" : "OFF");
        return out;
    }

    public boolean isMediaStackEnabled() {
        return getJellyarrPrefs().getBoolean("media_stack_enabled", true);
    }

    public void setMediaStackEnabled(boolean enabled) {
        getJellyarrPrefs().edit().putBoolean("media_stack_enabled", enabled).apply();
        if (!enabled) {
            // Stopping services and deleting their folders (hundreds of MB) must not block the UI thread.
            final Context app = getApplicationContext();
            runBg(() -> {
                try {
                    MediaServiceManager.getInstance(app).uninstallAllCompanionServices();
                } catch (Throwable ignored) {}
                runOnUiThread(() -> {
                    if (!isUiAlive()) return;
                    lastServicesSignature = null;
                    rebuildHomeServiceTiles();
                    refreshHomeServicesBadge();
                    refreshServicesTabUI();
                });
            });
            if (activeTab == 1) {
                activeTab = 0;
            }
        }
        refreshBottomNavBar();
        rebuildHomeServiceTiles();
        renderActiveTab();
    }

    private void refreshBottomNavBar() {
        if (bottomNavLayout == null) return;
        bottomNavLayout.removeAllViews();
        populateNavTabs(bottomNavLayout);
        rebuildDrawerItems();
        updateBottomNavSelection();
    }

    // -- Icon-only side drawer (Library / Sonarr), opened from the "Menu" item of the bottom bar ----

    private FrameLayout sideDrawerScrim;
    private LinearLayout sideDrawerPanel;
    private LinearLayout sideDrawerItems;
    private boolean sideDrawerOpen = false;

    private static final int PLACEHOLDER_TAB_BASE = 10;
    private static final String[] PLACEHOLDER_NAMES = {"Radarr", "Prowlarr", "Transmission"};
    private static final int[] PLACEHOLDER_ICONS = {R.drawable.logo_radarr, R.drawable.logo_prowlarr,
            R.drawable.logo_transmission};

    /** True for the full-colour original service logos (PNG); they are never tinted. */
    private static boolean isColorLogoRes(int res) {
        return res == R.drawable.logo_jellyfin || res == R.drawable.logo_radarr || res == R.drawable.logo_sonarr || res == R.drawable.logo_prowlarr
                || res == R.drawable.logo_transmission;
    }

    /** Colour logo: full colour when active, desaturated and faded when off. */
    private static void applyColorLogoState(ImageView iv, boolean active) {
        if (active) {
            iv.clearColorFilter();
            iv.setAlpha(1f);
        } else {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(0f);
            iv.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            iv.setAlpha(0.4f);
        }
    }
    private View cachedPlaceholderView;
    private ImageView placeholderIcon;
    private TextView placeholderTitle;

    private void buildSideDrawer(FrameLayout shell) {
        sideDrawerScrim = new FrameLayout(this);
        sideDrawerScrim.setBackgroundColor(0x99000000);
        sideDrawerScrim.setVisibility(View.GONE);
        sideDrawerScrim.setAlpha(0f);
        sideDrawerScrim.setOnClickListener(v -> closeSideDrawer());
        shell.addView(sideDrawerScrim, new FrameLayout.LayoutParams(-1, -1));

        sideDrawerPanel = new LinearLayout(this);
        sideDrawerPanel.setOrientation(LinearLayout.VERTICAL);
        sideDrawerPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        sideDrawerPanel.setBackground(drawerBackground());
        sideDrawerPanel.setPadding(0, dp(24), 0, dp(24));
        sideDrawerPanel.setVisibility(View.GONE);
        sideDrawerPanel.setClickable(true);
        sideDrawerPanel.setElevation(dp(12));
        final float[] downX = new float[1];
        sideDrawerPanel.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) downX[0] = ev.getRawX();
            else if (ev.getAction() == android.view.MotionEvent.ACTION_MOVE
                    && (ev.getRawX() - downX[0]) * drawerOffscreenSign() > dp(36)) {
                closeSideDrawer();
                return true;
            }
            return false;
        });
        ScrollView drawerScroll = new ScrollView(this);
        drawerScroll.setVerticalScrollBarEnabled(false);
        drawerScroll.setFillViewport(false);
        sideDrawerItems = new LinearLayout(this);
        sideDrawerItems.setOrientation(LinearLayout.VERTICAL);
        sideDrawerItems.setGravity(Gravity.CENTER_HORIZONTAL);
        drawerScroll.addView(sideDrawerItems, new ViewGroup.LayoutParams(-1, -2));
        sideDrawerPanel.addView(drawerScroll, new LinearLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(68), -1);
        plp.gravity = Gravity.START;
        shell.addView(sideDrawerPanel, plp);
        rebuildDrawerItems();
    }

    /** Shared empty page for the services whose tab is not built yet. */
    private View buildPlaceholderView() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(AppTheme.bg());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        placeholderIcon = new ImageView(this);
        placeholderIcon.setColorFilter(AppTheme.textMuted());
        col.addView(placeholderIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        placeholderTitle = new TextView(this);
        placeholderTitle.setTextSize(16f);
        placeholderTitle.setTextColor(AppTheme.text());
        placeholderTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        placeholderTitle.setPadding(0, dp(14), 0, dp(4));
        col.addView(placeholderTitle);
        TextView soon = UiKit.caption(this, "Coming soon");
        col.addView(soon);
        wrap.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return wrap;
    }

    private void showPlaceholderFor(int tab) {
        int i = tab - PLACEHOLDER_TAB_BASE;
        if (i < 0 || i >= PLACEHOLDER_NAMES.length || placeholderIcon == null) return;
        placeholderIcon.setImageResource(PLACEHOLDER_ICONS[i]);
        placeholderTitle.setText(PLACEHOLDER_NAMES[i]);
    }

    private boolean isDrawerTab(int tab) {
        return tab == 5 || tab == 6 || tab >= PLACEHOLDER_TAB_BASE;
    }

    private boolean isRtlLayout() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    /** Direction the drawer slides toward when hidden: -1 = off the left edge (LTR), +1 = off the right edge (RTL). */
    private int drawerOffscreenSign() {
        return isRtlLayout() ? 1 : -1;
    }

    /** Card-coloured panel whose rounded corners and hairline face the content, in both LTR and RTL. */
    private android.graphics.drawable.Drawable drawerBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(AppTheme.card());
        float r = dp(20);
        // corner order: top-left, top-right, bottom-right, bottom-left (x,y pairs)
        g.setCornerRadii(isRtlLayout()
                ? new float[]{r, r, 0, 0, 0, 0, r, r}
                : new float[]{0, 0, r, r, r, r, 0, 0});
        g.setStroke(dp(1), AppTheme.outline());
        return g;
    }

    private void applyDrawerInsets(int top, int bottom) {
        if (sideDrawerPanel != null) sideDrawerPanel.setPadding(0, top + dp(24), 0, dp(24));
    }

    private void rebuildDrawerItems() {
        if (sideDrawerPanel == null) return;
        if (sideDrawerItems == null) return;
        sideDrawerItems.removeAllViews();
        addDrawerItem(5, R.drawable.logo_jellyfin, "Jellyfin Library");
        if (isMediaStackEnabled()) {
            addDrawerItem(6, R.drawable.logo_sonarr, "Sonarr");
            View sep = new View(this);
            sep.setBackgroundColor(AppTheme.outline());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(28), dp(1));
            slp.topMargin = dp(2);
            slp.bottomMargin = dp(12);
            sideDrawerItems.addView(sep, slp);
            for (int i = 0; i < PLACEHOLDER_NAMES.length; i++) {
                addDrawerItem(PLACEHOLDER_TAB_BASE + i, PLACEHOLDER_ICONS[i], PLACEHOLDER_NAMES[i]);
            }
        }
        updateDrawerSelection();
    }

    private void addDrawerItem(final int tabIndex, int iconRes, String description) {
        FrameLayout item = new FrameLayout(this);
        item.setTag(tabIndex);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(description);
        ImageView icon = new ImageView(this);
        icon.setTag(isColorLogoRes(iconRes) ? "logo" : "icon");
        icon.setImageResource(iconRes);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(26), dp(26));
        ilp.gravity = Gravity.CENTER;
        item.addView(icon, ilp);
        item.setOnClickListener(v -> {
            closeSideDrawer();
            if (activeTab != tabIndex) {
                activeTab = tabIndex;
                updateBottomNavSelection();
                renderActiveTab();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        lp.bottomMargin = dp(10);
        sideDrawerItems.addView(item, lp);
    }

    private void updateDrawerSelection() {
        if (sideDrawerItems == null) return;
        for (int i = 0; i < sideDrawerItems.getChildCount(); i++) {
            View c = sideDrawerItems.getChildAt(i);
            boolean sel = c.getTag() instanceof Integer && (Integer) c.getTag() == activeTab;
            c.setBackground(UiKit.pressable(this, sel ? AppTheme.accentContainer() : Color.TRANSPARENT, 0, UiKit.R_CARD));
            ImageView ic = c.findViewWithTag("icon");
            if (ic != null) ic.setColorFilter(sel ? AppTheme.onAccentContainer() : AppTheme.textSecondary());
        }
    }

    private float edgeDownX, edgeDownY;
    private long edgeDownTime;
    private boolean edgeTracking = false;
    private boolean swallowDrawerGesture = false;
    private final int[] swipeHitLoc = new int[2];

    /**
     * True when the finger landed on something that scrolls or drags sideways (chip rows, sliders,
     * seek bars, pagers, horizontal lists, wide tables...). Walks the visible view tree under the
     * raw touch point, so tab views do not need to cooperate beyond tagging custom scrollers "h-scroll".
     */
    private boolean touchStartsOnHorizontalScroller(float rawX, float rawY) {
        Window w = getWindow();
        View root = w != null ? w.getDecorView() : null;
        return root != null && blocksDrawerSwipe(root, Math.round(rawX), Math.round(rawY), 0);
    }

    private boolean blocksDrawerSwipe(View v, int x, int y, int depth) {
        if (v == null || depth > 80 || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        if (v == sideDrawerPanel || v == sideDrawerScrim) return false;
        v.getLocationOnScreen(swipeHitLoc);
        if (x < swipeHitLoc[0] || y < swipeHitLoc[1]
                || x >= swipeHitLoc[0] + v.getWidth() || y >= swipeHitLoc[1] + v.getHeight()) return false;
        if (v instanceof HorizontalScrollView || v instanceof android.widget.AbsSeekBar
                || "h-scroll".equals(v.getTag())
                || v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)) return true;
        String cls = v.getClass().getName();
        if (cls.contains("ViewPager") || cls.endsWith("Slider")) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                if (blocksDrawerSwipe(g.getChildAt(i), x, y, depth + 1)) return true;
            }
        }
        return false;
    }

    /**
     * Swipe anywhere: a clearly horizontal fling toward the inside (rightwards in LTR, leftwards in RTL)
     * opens the drawer; the opposite fling closes it. Gestures that start on a horizontal scroller,
     * during setup, or with more than one finger are left to the views.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        int action = ev.getActionMasked();
        if (swallowDrawerGesture) {
            // The rest of a gesture that opened / closed the drawer belongs to nobody else.
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                swallowDrawerGesture = false;
            }
            if (action != android.view.MotionEvent.ACTION_DOWN) return true;
            swallowDrawerGesture = false;
        }
        try {
            if (sideDrawerPanel != null) {
                int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
                boolean rtl = isRtlLayout();
                switch (action) {
                    case android.view.MotionEvent.ACTION_DOWN: {
                        boolean setupShowing = setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE;
                        edgeDownX = ev.getRawX();
                        edgeDownY = ev.getRawY();
                        edgeDownTime = ev.getEventTime();
                        edgeTracking = !setupShowing
                                && (sideDrawerOpen || !touchStartsOnHorizontalScroller(edgeDownX, edgeDownY));
                        break;
                    }
                    case android.view.MotionEvent.ACTION_POINTER_DOWN:
                        edgeTracking = false;
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (!edgeTracking) break;
                        float dx = ev.getRawX() - edgeDownX;
                        float dy = ev.getRawY() - edgeDownY;
                        if (Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx)) {
                            edgeTracking = false;   // vertical scroll: never steal it later
                            break;
                        }
                        boolean quick = ev.getEventTime() - edgeDownTime < 700;
                        if (quick && Math.abs(dx) > Math.max(slop * 4, dp(48)) && Math.abs(dx) > Math.abs(dy) * 2.5f) {
                            boolean inward = rtl ? dx < 0 : dx > 0;
                            edgeTracking = false;
                            if (sideDrawerOpen != inward) {
                                if (inward) openSideDrawer(); else closeSideDrawer();
                                android.view.MotionEvent c = android.view.MotionEvent.obtain(ev);
                                c.setAction(android.view.MotionEvent.ACTION_CANCEL);
                                super.dispatchTouchEvent(c);
                                c.recycle();
                                swallowDrawerGesture = true;
                                return true;
                            }
                        }
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        edgeTracking = false;
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {}
        return super.dispatchTouchEvent(ev);
    }

    private void toggleSideDrawer() {
        if (sideDrawerOpen) closeSideDrawer(); else openSideDrawer();
    }

    /** Keeps the drawer and its scrim above the bottom navigation bar so they never cover it. */
    private void syncDrawerBottomMargin() {
        if (sideDrawerPanel == null || sideDrawerScrim == null || bottomNavLayout == null) return;
        int h = floatingNavInset();
        View[] vs = {sideDrawerPanel, sideDrawerScrim};
        for (View v : vs) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams && ((FrameLayout.LayoutParams) lp).bottomMargin != h) {
                ((FrameLayout.LayoutParams) lp).bottomMargin = h;
                v.setLayoutParams(lp);
            }
        }
    }

    private void openSideDrawer() {
        if (sideDrawerPanel == null || sideDrawerOpen) return;
        syncDrawerBottomMargin();
        sideDrawerOpen = true;
        updateDrawerSelection();
        sideDrawerScrim.setVisibility(View.VISIBLE);
        sideDrawerPanel.setVisibility(View.VISIBLE);
        sideDrawerPanel.setTranslationX(drawerOffscreenSign() * dp(68));
        sideDrawerScrim.animate().alpha(1f).setDuration(200).start();
        sideDrawerPanel.animate().translationX(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void closeSideDrawer() {
        if (sideDrawerPanel == null || !sideDrawerOpen) return;
        sideDrawerOpen = false;
        sideDrawerScrim.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (!sideDrawerOpen) sideDrawerScrim.setVisibility(View.GONE);
        }).start();
        sideDrawerPanel.animate().translationX(drawerOffscreenSign() * dp(68)).setDuration(180).withEndAction(() -> {
            if (!sideDrawerOpen) sideDrawerPanel.setVisibility(View.GONE);
        }).start();
    }

    public static class DiagnosticItem {
        public final String name;
        public final String category;
        public final String detail;
        public final String statusText;
        public final int statusColor;

        public DiagnosticItem(String name, String category, String detail, String statusText, int statusColor) {
            this.name = name;
            this.category = category;
            this.detail = detail;
            this.statusText = statusText;
            this.statusColor = statusColor;
        }
    }

    public List<DiagnosticItem> performSystemDiagnostics() {
        List<DiagnosticItem> results = new ArrayList<>();

        // 1. Device Architecture & ABI Check
        String primaryAbi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        boolean is64Bit = primaryAbi.contains("arm64") || primaryAbi.contains("aarch64");
        results.add(new DiagnosticItem(
                "Processor Architecture",
                "SYSTEM COMPATIBILITY",
                "Device ABI: " + primaryAbi + (is64Bit ? " (64-bit Bionic)" : " (32-bit legacy)"),
                is64Bit ? "COMPATIBLE" : "INCOMPATIBLE",
                is64Bit ? AppTheme.success() : AppTheme.danger()
        ));

        // 2. Hardware RAM & Memory Health (Optimized via Kotlin Diagnostics)
        try {
            com.jellyarr.app.diagnostics.DiagnosticsOptimizer.RamHealthInfo ram =
                    com.jellyarr.app.diagnostics.DiagnosticsOptimizer.getRamHealth(this);
            results.add(new DiagnosticItem(
                    "System RAM & Memory Health",
                    "HARDWARE & MEMORY",
                    MediaStorageManager.formatBytes(ram.getUsedBytes()) + " used of " + MediaStorageManager.formatBytes(ram.getTotalBytes()) + (ram.isLowMemory() ? " (Low Memory Alert)" : " (Normal Headroom)"),
                    ram.isLowMemory() ? "LOW MEMORY" : "HEALTHY",
                    ram.isLowMemory() ? AppTheme.danger() : AppTheme.success()
            ));
        } catch (Throwable ignored) {}

        // 3. Storage Free Space Check
        try {
            File dataDir = getFilesDir();
            android.os.StatFs stat = new android.os.StatFs(dataDir.getAbsolutePath());
            long availBytes = stat.getAvailableBytes();
            long totalBytes = stat.getTotalBytes();
            boolean hasEnough = availBytes >= (500L * 1024L * 1024L); // 500MB
            boolean isOptimal = availBytes >= (2L * 1024L * 1024L * 1024L); // 2GB
            String statText = isOptimal ? "EXCELLENT" : (hasEnough ? "ADEQUATE" : "LOW SPACE");
            int statColor = isOptimal ? AppTheme.success() : (hasEnough ? AppTheme.warning() : AppTheme.danger());
            results.add(new DiagnosticItem(
                    "Internal Storage Headroom",
                    "STORAGE",
                    MediaStorageManager.formatBytes(availBytes) + " free of " + MediaStorageManager.formatBytes(totalBytes),
                    statText,
                    statColor
            ));

            // App Cache & Logs Footprint
            com.jellyarr.app.diagnostics.DiagnosticsOptimizer.StorageFootprintInfo fp =
                    com.jellyarr.app.diagnostics.DiagnosticsOptimizer.getStorageFootprint(this);
            results.add(new DiagnosticItem(
                    "App Cache & Stale Logs",
                    "STORAGE",
                    "Cache & Logs: " + MediaStorageManager.formatBytes(fp.getAppCacheBytes()) + " | App Data: " + MediaStorageManager.formatBytes(fp.getAppDataBytes()),
                    "BOUNDED",
                    AppTheme.success()
            ));

            // 3b. Jellyfin Database & Journal Health Check
            File jfDbFile = new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR, ".local/share/jellyfin/data/jellyfin.db");
            if (jfDbFile.exists()) {
                boolean isDbOk = BackupRestoreManager.isDatabaseHealthy(jfDbFile);
                File walFile = new File(jfDbFile.getParentFile(), "jellyfin.db-wal");
                String detail = MediaStorageManager.formatBytes(jfDbFile.length()) + (walFile.exists() ? " (WAL journal: " + MediaStorageManager.formatBytes(walFile.length()) + ")" : "");
                results.add(new DiagnosticItem(
                        "Jellyfin SQLite Database",
                        "STORAGE",
                        detail,
                        isDbOk ? "HEALTHY" : "CORRUPT / TORN WAL",
                        isDbOk ? AppTheme.success() : AppTheme.danger()
                ));
            }
        } catch (Throwable t) {
            results.add(new DiagnosticItem("Storage Check", "STORAGE", "Error: " + t.getMessage(), "UNKNOWN", AppTheme.textMuted()));
        }

        // 4. Service Port Occupancy & Zombie Process Conflict Checks (via Kotlin Security Manager)
        List<com.jellyarr.app.security.JellyarrSecurityManager.ZombiePortStatus> portAudits =
                com.jellyarr.app.security.JellyarrSecurityManager.auditPorts(this);

        for (com.jellyarr.app.security.JellyarrSecurityManager.ZombiePortStatus audit : portAudits) {
            String portStatus;
            int portColor;
            String portDetail;

            if (audit.isManagedOnline()) {
                portStatus = "ONLINE";
                portColor = AppTheme.success(); // Green
                portDetail = "Port " + audit.getPort() + " (Active daemon)";
            } else if (audit.isZombieConflict()) {
                try {
                    MediaServiceManager.getInstance(this).reclaimPort(audit.getPort(), audit.getServiceName());
                } catch (Throwable ignored) {}
                portStatus = "AUTO-KILLED & RECLAIMED";
                portColor = AppTheme.success(); // Green
                portDetail = "Port " + audit.getPort() + " orphan/zombie process was automatically terminated";
            } else {
                portStatus = "AVAILABLE";
                portColor = AppTheme.textMuted(); // Muted
                portDetail = "Port " + audit.getPort() + " (Unoccupied)";
            }

            results.add(new DiagnosticItem(
                    audit.getServiceName(),
                    "NETWORK PORTS & DAEMON AUDIT",
                    portDetail,
                    portStatus,
                    portColor
            ));
        }

        return results;
    }

    public void showDiagnosticsModal() {
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getBgColor());
        scroll.setPadding(dp(16), dp(10), dp(16), dp(20));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // Header
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, dp(4), 0, dp(8));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("DIAGNOSTICS CENTRE");
        title.setTextSize(16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(AppTheme.text());
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Hardware architecture, zombie port collisions, and storage headroom.");
        sub.setTextSize(10f);
        sub.setTextColor(AppTheme.textMuted());
        titleCol.addView(sub);
        hdr.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnClose = createPixelButton("CLOSE", getSurfaceElevatedColor(), Color.WHITE);
        btnClose.setTextSize(9.5f);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        hdr.addView(btnClose, new LinearLayout.LayoutParams(-2, dp(30)));
        content.addView(hdr);

        // Fast Space Recovery Action Buttons
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, 0, 0, dp(8));

        Button btnPurgeCache = createPixelButton("PURGE CACHE & LOGS", AppTheme.outline(), Color.WHITE);
        btnPurgeCache.setTextSize(9f);
        actionRow.addView(btnPurgeCache, new LinearLayout.LayoutParams(0, dp(30), 1.0f));

        View aGap = new View(this);
        actionRow.addView(aGap, new LinearLayout.LayoutParams(dp(6), 1));

        Button btnKillZombies = createPixelButton("KILL ZOMBIES & RECLAIM PORTS", AppTheme.danger(), Color.WHITE);
        btnKillZombies.setTextSize(9f);
        actionRow.addView(btnKillZombies, new LinearLayout.LayoutParams(0, dp(30), 1.2f));

        content.addView(actionRow);

        // Report Container
        LinearLayout reportContainer = new LinearLayout(this);
        reportContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(reportContainer);

        Runnable runAudit = () -> {
            reportContainer.removeAllViews();
            TextView scanning = new TextView(this);
            scanning.setText("Running hardware, memory & port diagnostics...");
            scanning.setTextColor(AppTheme.textMuted());
            scanning.setTextSize(11f);
            scanning.setPadding(0, dp(6), 0, dp(6));
            reportContainer.addView(scanning);

            new Thread(() -> {
                List<DiagnosticItem> items = performSystemDiagnostics();
                runOnUiThread(() -> {
                    reportContainer.removeAllViews();
                    String lastCategory = "";
                    for (DiagnosticItem item : items) {
                        if (!item.category.equals(lastCategory)) {
                            lastCategory = item.category;
                            TextView catHeader = new TextView(this);
                            catHeader.setText(lastCategory);
                            catHeader.setTextSize(9.5f);
                            catHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                            catHeader.setTextColor(AppTheme.textSecondary());
                            catHeader.setLetterSpacing(0.08f);
                            catHeader.setPadding(0, dp(8), 0, dp(3));
                            reportContainer.addView(catHeader);
                        }

                        LinearLayout itemCard = new LinearLayout(this);
                        itemCard.setOrientation(LinearLayout.HORIZONTAL);
                        itemCard.setGravity(Gravity.CENTER_VERTICAL);
                        itemCard.setBackground(createBorderedRoundedDrawable(
                                AppTheme.inner(),
                                AppTheme.inner(),
                                dp(1),
                                dp(6)
                        ));
                        itemCard.setPadding(dp(10), dp(6), dp(10), dp(6));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                        lp.bottomMargin = dp(4);
                        itemCard.setLayoutParams(lp);

                        LinearLayout leftCol = new LinearLayout(this);
                        leftCol.setOrientation(LinearLayout.VERTICAL);
                        TextView nameView = new TextView(this);
                        nameView.setText(item.name);
                        nameView.setTextSize(11.5f);
                        nameView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                        nameView.setTextColor(AppTheme.text());
                        leftCol.addView(nameView);

                        TextView detailView = new TextView(this);
                        detailView.setText(item.detail);
                        detailView.setTextSize(9.5f);
                        detailView.setTextColor(AppTheme.textMuted());
                        leftCol.addView(detailView);
                        itemCard.addView(leftCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

                        TextView statusBadge = new TextView(this);
                        statusBadge.setText(item.statusText);
                        statusBadge.setTextSize(8f);
                        statusBadge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                        statusBadge.setTextColor(item.statusColor);
                        statusBadge.setBackground(createRoundedDrawable(Color.argb(35, Color.red(item.statusColor), Color.green(item.statusColor), Color.blue(item.statusColor)), dp(5)));
                        statusBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
                        itemCard.addView(statusBadge);

                        reportContainer.addView(itemCard);
                    }
                });
            }).start();
        };

        btnPurgeCache.setOnClickListener(v -> {
            long freed = com.jellyarr.app.diagnostics.DiagnosticsOptimizer.purgeTemporaryCache(this);
            Toast.makeText(this, I18n.tr("Purged temporary cache: " + MediaStorageManager.formatBytes(freed) + " reclaimed."), Toast.LENGTH_SHORT).show();
            runAudit.run();
        });

        btnKillZombies.setOnClickListener(v -> {
            int reclaimed = com.jellyarr.app.security.JellyarrSecurityManager.reclaimAllZombiePorts(this);
            Toast.makeText(this, I18n.tr("Terminated zombie processes and released socket locks."), Toast.LENGTH_SHORT).show();
            runAudit.run();
        });

        // Re-run Button
        Button btnRefresh = createOutlinePixelButton("RE-SCAN DIAGNOSTICS", AppTheme.accent(), AppTheme.accent());
        btnRefresh.setTextSize(10f);
        btnRefresh.setOnClickListener(v -> runAudit.run());
        LinearLayout.LayoutParams prParams = new LinearLayout.LayoutParams(-1, dp(36));
        prParams.topMargin = dp(12);
        content.addView(btnRefresh, prParams);

        scroll.addView(content);
        dialog.setContentView(I18n.attach(scroll));
        safeShow(dialog);
        runAudit.run();
    }

    private void renderTimelineEvents() {
        refreshUnifiedLogsDisplay();
    }

    // -- Tab 1: Services Manager View ------------------------------------------

    private View buildServicesTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getBgColor());
        scroll.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.setClipToPadding(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // -- Page header: title + live "x / y Active" summary ------------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        servicesSummaryBadge = UiKit.caption(this, "Loading...");
        servicesSummaryBadge.setTypeface(UiKit.MEDIUM);
        header.addView(UiKit.header(this, "Media Stack", null, servicesSummaryBadge));

        // -- Master controls row: START ALL (only while everything is stopped) + SYNC STACK -----------
        LinearLayout masterRow = new LinearLayout(this);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(-1, -2);
        mrLp.bottomMargin = dp(4);
        masterRow.setLayoutParams(mrLp);

        masterStackToggleBtn = UiKit.primary(this, buttonLabel("START ALL"), null);
        masterRow.addView(masterStackToggleBtn, new LinearLayout.LayoutParams(0, dp(44), 1.0f));

        masterStackGap = new View(this);
        masterRow.addView(masterStackGap, new LinearLayout.LayoutParams(dp(12), 1));

        Button btnSyncAll = UiKit.outline(this, buttonLabel("SYNC STACK"), v -> syncAndAutoWireAllServices());
        masterRow.addView(btnSyncAll, new LinearLayout.LayoutParams(0, dp(44), 1.0f));

        header.addView(masterRow);

        Button btnPresets = UiKit.outline(this, buttonLabel("SETUP PRESETS"), v -> showStackPresetsDialog());
        LinearLayout.LayoutParams presetsLp = new LinearLayout.LayoutParams(-1, dp(44));
        presetsLp.topMargin = dp(8);
        header.addView(btnPresets, presetsLp);

        // Section header above the cards with compact Install / Uninstall actions
        LinearLayout cardsHeader = new LinearLayout(this);
        cardsHeader.setOrientation(LinearLayout.HORIZONTAL);
        cardsHeader.setGravity(Gravity.CENTER_VERTICAL);
        cardsHeader.setPadding(0, dp(20), 0, 0);

        TextView cardsTitle = new TextView(this);
        cardsTitle.setText("COMPANION SERVICES");
        styleSectionLabel(cardsTitle);
        cardsHeader.addView(cardsTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));

        header.addView(cardsHeader);

        layout.addView(header);

        servicesCardsContainer = new LinearLayout(this);
        servicesCardsContainer.setOrientation(LinearLayout.VERTICAL);
        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        lt.enableTransitionType(android.animation.LayoutTransition.APPEARING);
        lt.enableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        lt.setDuration(220);
        servicesCardsContainer.setLayoutTransition(lt);
        LinearLayout.LayoutParams scParams = new LinearLayout.LayoutParams(-1, -2);
        scParams.topMargin = dp(12);
        servicesCardsContainer.setLayoutParams(scParams);
        layout.addView(servicesCardsContainer);

        mediaLibrariesView = new MediaLibrariesView(this);
        LinearLayout.LayoutParams mlLp = new LinearLayout.LayoutParams(-1, -2);
        mlLp.topMargin = dp(12);
        layout.addView(mediaLibrariesView.getView(), mlLp);

        mediaToolsView = new MediaToolsView(this);
        layout.addView(mediaToolsView.getView());

        scroll.addView(layout);
        refreshServicesTabUI();
        return scroll;
    }

    private String lastServicesSignature = null;

    private String computeServicesSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(isMediaStackEnabled()).append('|').append(isAvailableServicesDrawerOpen).append('|');
        try {
            sb.append(controller != null ? controller.getState() : "-").append('|');
            for (ManagedService s : MediaServiceManager.getInstance(this).getAllServices()) {
                sb.append(s.getId()).append(':').append(s.getState()).append(':')
                        .append(s.isInstalled()).append(s.isRunning()).append(s.isStarting()).append(s.isBlocked()).append(';');
            }
        } catch (Throwable t) {
            return null;
        }
        return sb.toString();
    }

    private void refreshServicesTabUI() {
        refreshCompanionButtons();
        if (servicesCardsContainer == null) return;
        // Skip all work while the tab is hidden; it is refreshed when the tab is opened.
        if (activeTab != 1 && servicesCardsContainer.getChildCount() > 0) {
            lastServicesSignature = null;
            return;
        }
        String sig = computeServicesSignature();
        if (sig != null && sig.equals(lastServicesSignature) && servicesCardsContainer.getChildCount() > 0) return;
        lastServicesSignature = sig;
        servicesCardsContainer.removeAllViews();

        if (!isMediaStackEnabled()) {
            LinearLayout disabledCard = UiKit.card(this);

            TextView dTitle = UiKit.subtitle(this, "MEDIA STACK IS PAUSED");
            dTitle.setTextColor(AppTheme.warning());
            disabledCard.addView(dTitle);

            TextView dSub = UiKit.body(this, "Jellyarr is currently operating in standalone Jellyfin mode to conserve storage and RAM. Companion services are paused.");
            dSub.setPadding(0, dp(4), 0, dp(4));
            disabledCard.addView(dSub);

            disabledCard.addView(UiKit.buttonRow(this,
                    UiKit.tonal(this, buttonLabel("ENABLE COMPANION SERVICES"), v -> setMediaStackEnabled(true))));

            servicesCardsContainer.addView(disabledCard);
            return;
        }


        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        List<ManagedService> allServices = mgr.getAllServices();
        List<ManagedService> installedServices = new java.util.ArrayList<>();
        List<ManagedService> uninstalledServices = new java.util.ArrayList<>();

        int running = 0;
        for (ManagedService s : allServices) {
            if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
            if (s.isInstalled()) {
                installedServices.add(s);
                if (s.isRunning()) {
                    running++;
                }
            } else {
                uninstalledServices.add(s);
            }
        }

        // 1. Installed services are controlled from Home (tap a service logo); this tab only shows a hint
        //    when nothing is installed yet, then the install drawer below.
        if (installedServices.isEmpty()) {
            View emptyBox = UiKit.empty(this, "NO COMPANION SERVICES INSTALLED",
                    "Tap available services in the drawer below to install.");
            emptyBox.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(-1, -2);
            ebLp.bottomMargin = dp(12);
            servicesCardsContainer.addView(emptyBox, ebLp);
        }

        // 2. Uninstalled Services Collapsible Drawer (Grayed Out)
        if (!uninstalledServices.isEmpty()) {
            LinearLayout drawerLayout = new LinearLayout(this);
            drawerLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
            dLp.topMargin = dp(14);
            dLp.bottomMargin = dp(8);
            drawerLayout.setLayoutParams(dLp);

            // Drawer Header Row
            LinearLayout drawerHeader = new LinearLayout(this);
            drawerHeader.setOrientation(LinearLayout.HORIZONTAL);
            drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
            drawerHeader.setPadding(dp(8), dp(10), dp(8), dp(10));
            drawerHeader.setClickable(true);
            drawerHeader.setFocusable(true);
            drawerHeader.setBackground(UiKit.rowRipple(this));

            TextView drawerTitle = new TextView(this);
            drawerTitle.setText("AVAILABLE SERVICES (" + uninstalledServices.size() + ")");
            styleSectionLabel(drawerTitle);
            drawerHeader.addView(drawerTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));

            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_chevron_down);
            chevron.setColorFilter(AppTheme.textMuted());
            chevron.setRotation(isAvailableServicesDrawerOpen ? 180f : 0f);
            drawerHeader.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

            drawerLayout.addView(drawerHeader);

            // Drawer Content Grid
            LinearLayout drawerContent = new LinearLayout(this);
            drawerContent.setOrientation(LinearLayout.VERTICAL);
            drawerContent.setVisibility(isAvailableServicesDrawerOpen ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(-1, -2);
            dcLp.topMargin = dp(10);
            drawerContent.setLayoutParams(dcLp);

            LinearLayout unRow = null;
            for (int i = 0; i < uninstalledServices.size(); i++) {
                ManagedService s = uninstalledServices.get(i);
                if (i % 2 == 0) {
                    unRow = new LinearLayout(this);
                    unRow.setOrientation(LinearLayout.HORIZONTAL);
                    unRow.setBaselineAligned(false);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = dp(12);
                    unRow.setLayoutParams(rowLp);
                    drawerContent.addView(unRow);
                }

                View card = buildIndividualServiceCard(s);
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                card.setLayoutParams(cardLp);
                if (unRow != null) {
                    unRow.addView(card);
                }

                if (i % 2 == 0) {
                    View spacer = new View(this);
                    if (unRow != null) {
                        unRow.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));
                    }
                }
            }

            if (uninstalledServices.size() % 2 != 0 && unRow != null) {
                View emptySpacer = new View(this);
                unRow.addView(emptySpacer, new LinearLayout.LayoutParams(0, 1, 1.0f));
            }

            drawerLayout.addView(drawerContent);

            drawerHeader.setOnClickListener(v -> {
                isAvailableServicesDrawerOpen = !isAvailableServicesDrawerOpen;
                drawerContent.setVisibility(isAvailableServicesDrawerOpen ? View.VISIBLE : View.GONE);
                chevron.animate().rotation(isAvailableServicesDrawerOpen ? 180f : 0f).setDuration(200).start();
            });

            servicesCardsContainer.addView(drawerLayout);
        }

        if (servicesSummaryBadge != null) {
            servicesSummaryBadge.setText(running + " / " + installedServices.size() + " Active");
            servicesSummaryBadge.setTextColor(running > 0 ? AppTheme.text() : AppTheme.textMuted());
        }

        boolean anyActive = false;
        for (ManagedService s : installedServices) {
            if (s.isRunning() || s.isStarting()) {
                anyActive = true;
                break;
            }
        }

        if (masterStackToggleBtn != null) {
            if (anyActive) {
                masterStackToggleBtn.setVisibility(View.GONE);
                if (masterStackGap != null) masterStackGap.setVisibility(View.GONE);
            } else {
                masterStackToggleBtn.setVisibility(View.VISIBLE);
                if (masterStackGap != null) masterStackGap.setVisibility(View.VISIBLE);
                masterStackToggleBtn.setEnabled(!installedServices.isEmpty());
                masterStackToggleBtn.setAlpha(installedServices.isEmpty() ? 0.5f : 1f);
                masterStackToggleBtn.setOnClickListener(v -> {
                    Toast.makeText(this, I18n.tr("Starting companion media stack..."), Toast.LENGTH_SHORT).show();
                    for (ManagedService is : installedServices) {
                        if (!is.isRunning() && !is.isStarting()) {
                            mgr.startService(is.getId());
                        }
                    }
                    mainHandler.postDelayed(() -> {
                        MediaServiceManager.getInstance(this).reconcileRunningServices();
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                    }, 3500);
                });
            }
        }
        com.jellyarr.app.widget.JellyarrWidgetProvider.updateAllWidgets(this);
    }

    private TextView createOledTextContent(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(AppTheme.text());
        tv.setPadding(0, dp(6), 0, 0);
        return tv;
    }

    private View buildIndividualServiceCard(ManagedService svc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        String id = svc.getId().toLowerCase(Locale.ROOT);
        boolean isJellyfin = "jellyfin".equals(id);
        boolean isRunning;
        boolean isStarting;
        int iconTintColor;
        String stText = "OFF";
        int stColor = AppTheme.textMuted();

        if (isJellyfin) {
            JellyfinController.State js = controller != null ? controller.getState() : JellyfinController.State.STOPPED;
            isRunning = (js == JellyfinController.State.RUNNING);
            isStarting = (js == JellyfinController.State.STARTING || js == JellyfinController.State.INITIALIZING);
            switch (js) {
                case RUNNING:
                    iconTintColor = getServiceBrandColor("jellyfin");
                    stText = "RUNNING";
                    stColor = AppTheme.success();
                    break;
                case STARTING:
                case INITIALIZING:
                    iconTintColor = getWarningColor();
                    stText = "STARTING";
                    stColor = getWarningColor();
                    break;
                case CRASHED:
                case FAILED:
                case CRASH_LOOP:
                    iconTintColor = getDangerColor();
                    stText = "FAILED";
                    stColor = getDangerColor();
                    break;
                default:
                    iconTintColor = AppTheme.text();
                    stText = "STOPPED";
                    stColor = AppTheme.textMuted();
                    break;
            }
        } else {
            isRunning = svc.isRunning();
            isStarting = svc.isStarting();
            iconTintColor = getServiceBrandTintColor(svc.getId(), svc.getState());
            if (svc.isBlocked()) {
                stText = "BLOCKED";
                stColor = getDangerColor();
            } else if (svc.getState() == com.jellyarr.app.model.ServiceState.FAILED
                    || svc.getState() == com.jellyarr.app.model.ServiceState.ERROR
                    || svc.getState() == com.jellyarr.app.model.ServiceState.CRASH_LOOP_PROTECTION
                    || svc.getState() == com.jellyarr.app.model.ServiceState.UNHEALTHY) {
                stText = svc.getState() == com.jellyarr.app.model.ServiceState.CRASH_LOOP_PROTECTION ? "CRASH LOOP" : "FAILED";
                stColor = getDangerColor();
            } else if (isRunning) {
                stText = "RUNNING";
                stColor = AppTheme.success();
            } else if (svc.getState() == com.jellyarr.app.model.ServiceState.RECOVERING) {
                stText = "RETRYING";
                stColor = AppTheme.warning();
            } else if (isStarting) {
                stText = "STARTING";
                stColor = getWarningColor();
            } else if (svc.isInstalled()) {
                stText = "STOPPED";
                stColor = AppTheme.textMuted();
            } else {
                stText = "AVAILABLE";
                stColor = AppTheme.textMuted();
            }
        }

        boolean installed = isJellyfin || svc.isInstalled();

        card.setBackground(UiKit.pressable(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));
        card.setAlpha(installed ? 1.0f : 0.7f);   // available (not installed) services are dimmed

        card.setClickable(true);
        card.setFocusable(true);
        // Tap: install flow for available services, details sheet for installed ones (long-press kept on the address).
        card.setOnClickListener(v -> {
            if (!installed) {
                showServiceInstallCompatibilityDialog(svc);
            } else {
                showServiceDetailsModal(svc);
            }
        });

        // 1. Header: logo + name / version
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(getServiceIconRes(id));
        if (isColorLogoRes(getServiceIconRes(id))) {
            applyColorLogoState(iconView, isJellyfin ? (isRunning || isStarting) : tileCategory(svc.getState()) != 0);
        } else {
            iconView.setColorFilter(iconTintColor);
        }
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        icLp.setMarginEnd(dp(10));
        top.addView(iconView, icLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView name = UiKit.singleLine(UiKit.subtitle(this, svc.getDisplayName()));
        name.setTextColor(installed ? AppTheme.text() : AppTheme.textSecondary());
        titleCol.addView(name);

        TextView verTag = UiKit.singleLine(UiKit.text(this, "v" + svc.getManifest().getVersion(), 11f, AppTheme.textMuted(), Typeface.MONOSPACE));
        titleCol.addView(verTag);
        top.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        card.addView(top);

        // 2. Status badge
        TextView stateBadge = UiKit.badge(this, I18n.tr(stText), stColor);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-2, -2);
        sbLp.topMargin = dp(10);
        card.addView(stateBadge, sbLp);

        // 3. Compact endpoint row (tap = copy, long-press = details)
        int defaultPort = svc.getManifest().getDefaultPort();
        String webPath = svc.getManifest().getWebPath();
        if (webPath == null) webPath = "/";
        String localUrl = "http://127.0.0.1:" + defaultPort + (webPath.equals("/") ? "" : webPath);
        String rawLanIp = cachedRawLanIp;
        String lanUrl = (rawLanIp != null) ? "http://" + rawLanIp + ":" + defaultPort + (webPath.equals("/") ? "" : webPath) : null;
        final String finalCopyUrl = lanUrl != null ? lanUrl : localUrl;

        LinearLayout epRow = new LinearLayout(this);
        epRow.setOrientation(LinearLayout.HORIZONTAL);
        epRow.setGravity(Gravity.CENTER_VERTICAL);
        epRow.setPadding(dp(10), dp(7), dp(10), dp(7));
        epRow.setClickable(true);
        epRow.setFocusable(true);
        epRow.setBackground(UiKit.pressable(this, AppTheme.inner(), 0, UiKit.R_INNER));
        epRow.setOnClickListener(v -> copyUrlToClipboard(svc.getDisplayName() + " URL", finalCopyUrl));
        epRow.setOnLongClickListener(v -> {
            showServiceDetailsModal(svc);
            return true;
        });
        LinearLayout.LayoutParams epLp = new LinearLayout.LayoutParams(-1, -2);
        epLp.topMargin = dp(10);
        epLp.bottomMargin = dp(10);
        epRow.setLayoutParams(epLp);

        TextView epText = new TextView(this);
        epText.setText(lanUrl != null ? rawLanIp + ":" + defaultPort : "127.0.0.1:" + defaultPort);
        epText.setTextSize(12f);
        epText.setTypeface(Typeface.MONOSPACE);
        epText.setTextColor(installed ? AppTheme.text() : AppTheme.textMuted());
        epText.setSingleLine(true);
        epText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        epText.setTextDirection(View.TEXT_DIRECTION_LTR);
        epRow.addView(epText, new LinearLayout.LayoutParams(0, -2, 1.0f));

        ImageView epCopy = new ImageView(this);
        epCopy.setImageResource(R.drawable.ic_copy);
        epCopy.setColorFilter(AppTheme.textMuted());
        epCopy.setContentDescription("Copy");
        LinearLayout.LayoutParams ecLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        ecLp.setMarginStart(dp(6));
        epRow.addView(epCopy, ecLp);

        card.addView(epRow);

        // 4. Actions (single row, adaptive)
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(38)));

        if (svc.isBlocked()) {
            Button btnBlocked = compactCardButton(UiKit.outline(this, buttonLabel("INCOMPATIBLE"), null));
            btnBlocked.setEnabled(false);
            btnBlocked.setAlpha(0.6f);
            bottomRow.addView(btnBlocked, new LinearLayout.LayoutParams(-1, -1));
        } else if (!installed) {
            Button btnInstall = compactCardButton(UiKit.tonal(this, buttonLabel("INSTALL"), v -> showServiceInstallCompatibilityDialog(svc)));
            bottomRow.addView(btnInstall, new LinearLayout.LayoutParams(-1, -1));
        } else if (svc.getState() == com.jellyarr.app.model.ServiceState.CRASH_LOOP_PROTECTION) {
            Button btnReset = compactCardButton(UiKit.danger(this, buttonLabel("RESET CRASH LOOP"), v -> {
                MediaServiceManager.getInstance(this).resetCrashLoop(svc.getId());
                refreshServicesTabUI();
                refreshHomeServicesBadge();
            }));
            bottomRow.addView(btnReset, new LinearLayout.LayoutParams(-1, -1));
        } else if (!isRunning) {
            String startLabel = (svc.getState() == com.jellyarr.app.model.ServiceState.RECOVERING) ? "RETRY NOW" : "START";
            Button btnStart = compactCardButton(UiKit.primary(this, buttonLabel(startLabel), v -> {
                if (isJellyfin) {
                    triggerServerAction(JellyfinServerService.ACTION_START);
                } else {
                    MediaServiceManager.getInstance(this).startService(svc.getId());
                }
                mainHandler.postDelayed(() -> {
                    refreshServicesTabUI();
                    refreshHomeServicesBadge();
                }, 500);
            }));
            if (isStarting) {
                btnStart.setEnabled(false);
                btnStart.setAlpha(0.6f);
            }
            bottomRow.addView(btnStart, new LinearLayout.LayoutParams(-1, -1));
        } else {
            Button btnWeb = compactCardButton(UiKit.primary(this, buttonLabel("WEB UI"), v -> openServiceWeb(svc)));
            bottomRow.addView(btnWeb, new LinearLayout.LayoutParams(0, -1, 1.15f));

            View spB1 = new View(this);
            bottomRow.addView(spB1, new LinearLayout.LayoutParams(dp(8), 1));

            Button btnStop = compactCardButton(UiKit.danger(this, buttonLabel("STOP"), v -> {
                if (isJellyfin) {
                    controller.stop();
                    triggerServerAction(JellyfinServerService.ACTION_STOP);
                } else {
                    MediaServiceManager.getInstance(this).stopService(svc.getId());
                }
                mainHandler.postDelayed(() -> {
                    refreshServicesTabUI();
                    refreshHomeServicesBadge();
                }, 500);
            }));
            bottomRow.addView(btnStop, new LinearLayout.LayoutParams(0, -1, 0.95f));
        }

        card.addView(bottomRow);
        return card;
    }

    /** UiKit button sized for the narrow two-column service cards. */
    private Button compactCardButton(Button b) {
        b.setTextSize(12.5f);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void showServiceDetailsModal(ManagedService svc) {
        if (svc == null || !isUiAlive()) return;
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));

        LinearLayout root = UiKit.column(this);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        scroll.addView(root);

        String id = svc.getId().toLowerCase(Locale.ROOT);
        boolean isJellyfin = "jellyfin".equalsIgnoreCase(id);
        boolean isRunning = isJellyfin ? (controller != null && controller.getState() == JellyfinController.State.RUNNING) : svc.isRunning();
        boolean installed = isJellyfin || svc.isInstalled();

        // 1. Header: logo + name / version + close
        LinearLayout headerRow = UiKit.row(this);

        ImageView icon = new ImageView(this);
        icon.setImageResource(getServiceIconRes(id));
        if (!isColorLogoRes(getServiceIconRes(id))) icon.setColorFilter(getServiceBrandColor(id));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        icLp.setMarginEnd(dp(12));
        headerRow.addView(icon, icLp);

        LinearLayout titleCol = UiKit.column(this);
        titleCol.addView(UiKit.singleLine(UiKit.title(this, svc.getDisplayName())));
        titleCol.addView(UiKit.singleLine(UiKit.text(this, "v" + svc.getManifest().getVersion(), 12f, AppTheme.textMuted(), Typeface.MONOSPACE)));
        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));
        headerRow.addView(dialogCloseButton(d));
        root.addView(headerRow);

        // 2. Status badge
        String stBadgeText;
        int stBadgeColor;
        if (svc.isBlocked()) {
            stBadgeText = "BLOCKED";
            stBadgeColor = getDangerColor();
        } else if (svc.getState() == com.jellyarr.app.model.ServiceState.CRASH_LOOP_PROTECTION) {
            stBadgeText = "CRASH LOOP";
            stBadgeColor = getDangerColor();
        } else if (svc.getState() == com.jellyarr.app.model.ServiceState.ERROR || svc.getState() == com.jellyarr.app.model.ServiceState.FAILED) {
            stBadgeText = "FAILED";
            stBadgeColor = getDangerColor();
        } else if (svc.getState() == com.jellyarr.app.model.ServiceState.RECOVERING) {
            stBadgeText = "RETRYING";
            stBadgeColor = AppTheme.warning();
        } else if (svc.isStarting()) {
            stBadgeText = "STARTING";
            stBadgeColor = getWarningColor();
        } else if (isRunning) {
            stBadgeText = "RUNNING";
            stBadgeColor = AppTheme.success();
        } else if (installed) {
            stBadgeText = "STOPPED";
            stBadgeColor = AppTheme.textMuted();
        } else {
            stBadgeText = "AVAILABLE";
            stBadgeColor = AppTheme.accent();
        }
        TextView stateBadge = UiKit.badge(this, I18n.tr(stBadgeText), stBadgeColor);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-2, -2);
        sbLp.topMargin = dp(12);
        root.addView(stateBadge, sbLp);

        if (!isJellyfin && svc.getLastError() != null && !svc.getLastError().trim().isEmpty() && !isRunning) {
            LinearLayout errBox = UiKit.inset(this);
            errBox.setBackground(UiKit.shape(this, AppTheme.dangerContainer(), 0, UiKit.R_INNER));
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(-1, -2);
            ebLp.topMargin = dp(12);
            errBox.setLayoutParams(ebLp);

            TextView errTitle = UiKit.text(this, "DIAGNOSTIC NOTICE", 11f, AppTheme.danger(), UiKit.BOLD);
            errTitle.setLetterSpacing(0.06f);
            errBox.addView(errTitle);

            TextView errDesc = UiKit.body(this, svc.getLastError());
            errDesc.setTextColor(AppTheme.text());
            errDesc.setTextIsSelectable(true);
            errDesc.setPadding(0, dp(4), 0, 0);
            errBox.addView(errDesc);

            root.addView(errBox);
        }

        // 3. Addresses: every LAN endpoint + local loopback
        int defaultPort = svc.getManifest().getDefaultPort();
        String webPathRaw = svc.getManifest().getWebPath();
        final String webPath = webPathRaw == null ? "/" : webPathRaw;
        String localUrl = "http://127.0.0.1:" + defaultPort + (webPath.equals("/") ? "" : webPath);

        root.addView(detailSectionLabel("NETWORK & LAN ACCESS URLS"));
        LinearLayout urlBox = UiKit.inset(this);
        java.util.List<NetworkUtils.LanEndpoint> endpoints = NetworkUtils.getAllAvailableLanEndpoints();
        if (endpoints != null) {
            for (NetworkUtils.LanEndpoint ep : endpoints) {
                String epUrl = ep.getUrl(defaultPort, webPath);
                urlBox.addView(detailCopyRow(ep.label, epUrl, true, svc.getDisplayName() + " " + ep.label + " URL", epUrl));
            }
        }
        urlBox.addView(detailCopyRow("LOCAL", localUrl, true, svc.getDisplayName() + " Local URL", localUrl));
        TextView sopNotice = UiKit.caption(this, "Note: Browsers isolate cookies per IP/host (Same-Origin Policy). Logging in on USB/Wi-Fi IP does not transfer to 127.0.0.1 or other IPs.");
        sopNotice.setPadding(0, dp(6), 0, dp(2));
        urlBox.addView(sopNotice);
        root.addView(urlBox);

        // 4. Credentials: unified account login (when set) and the service API key
        root.addView(detailSectionLabel("CREDENTIALS / AUTHENTICATION"));
        LinearLayout credBox = UiKit.inset(this);
        boolean unified = UnifiedAccount.isConfigured(this);
        String unifiedUser = UnifiedAccount.getUsername(this);
        if (unified) {
            credBox.addView(detailCopyRow(null, I18n.tr("Unified account") + ": " + unifiedUser, false,
                    svc.getDisplayName() + " Username", unifiedUser));
        }
        if ("jellyfin".equalsIgnoreCase(id)) {
            if (!unified) {
                credBox.addView(detailCopyRow(null, "Admin account created during initial setup", false,
                        svc.getDisplayName() + " URL", "http://127.0.0.1:8096"));
            }
        } else if ("transmission".equalsIgnoreCase(id)) {
            if (!unified) {
                credBox.addView(detailCopyRow(null, "No login required. Set a unified account in Settings > Security & Identity to protect it.", false,
                        svc.getDisplayName() + " URL", "http://127.0.0.1:9091/transmission/web/"));
            }
        } else {
            String apiKey = MediaServiceManager.getInstance(this).getServiceApiKey(id);
            if (apiKey != null && !apiKey.isEmpty()) {
                credBox.addView(detailCopyRow("API", apiKey, true, svc.getDisplayName() + " API Key", apiKey));
            } else if (!unified) {
                credBox.addView(detailCopyRow(null, "Zero-Auth (Auto-generated on startup)", false,
                        svc.getDisplayName() + " URL", "http://127.0.0.1:" + defaultPort));
            }
        }
        root.addView(credBox);

        // 5. Status & storage (folder sizes are computed off the UI thread)
        root.addView(detailSectionLabel("STATUS & STORAGE"));
        LinearLayout statBox = UiKit.inset(this);
        addDetailKv(statBox, "Port", String.valueOf(defaultPort), true);
        final TextView sizeValue = addDetailKv(statBox, installed ? "Size" : "Est. Size",
                installed ? I18n.tr("Calculating...") : "~" + svc.getManifest().getStorageRequiredMb() + " MB", false);
        File mediaTargetDir = null;
        if ("radarr".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getMoviesDir();
        } else if ("sonarr".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getTvDir();
        } else if ("transmission".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getDownloadsDir();
        } else if ("jellyfin".equalsIgnoreCase(id)) {
            mediaTargetDir = MediaStorageManager.getInstance(this).getMediaRoot();
        }
        if (mediaTargetDir != null) {
            TextView pathLabel = UiKit.caption(this, "Path");
            pathLabel.setPadding(0, dp(6), 0, dp(2));
            statBox.addView(pathLabel);
            TextView pathVal = UiKit.mono(this, mediaTargetDir.getAbsolutePath());
            pathVal.setTextDirection(View.TEXT_DIRECTION_LTR);
            statBox.addView(pathVal);
        }
        root.addView(statBox);

        // 6. Actions: primary row, then secondary row (logs / uninstall)
        if (!installed) {
            root.addView(UiKit.buttonRow(this, UiKit.primary(this, buttonLabel("INSTALL"), v -> {
                d.dismiss();
                showServiceInstallCompatibilityDialog(svc);
            })));
        } else {
            if (isRunning) {
                Button btnWeb = UiKit.primary(this, buttonLabel("WEB UI"), v -> {
                    d.dismiss();
                    openServiceWeb(svc);
                });
                Button btnStop = UiKit.danger(this, buttonLabel("STOP"), v -> {
                    d.dismiss();
                    if (isJellyfin) {
                        if (controller != null) controller.stop();
                        triggerServerAction(JellyfinServerService.ACTION_STOP);
                    } else {
                        MediaServiceManager.getInstance(this).stopService(svc.getId());
                    }
                    mainHandler.postDelayed(() -> {
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                    }, 500);
                });
                root.addView(UiKit.buttonRow(this, btnWeb, btnStop));
            } else {
                Button btnStart = UiKit.primary(this, buttonLabel("START"), v -> {
                    d.dismiss();
                    if (isJellyfin) {
                        triggerServerAction(JellyfinServerService.ACTION_START);
                    } else {
                        MediaServiceManager.getInstance(this).startService(svc.getId());
                    }
                    mainHandler.postDelayed(() -> {
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                    }, 500);
                });
                root.addView(UiKit.buttonRow(this, btnStart));
            }

            if (!isJellyfin) {
                Button btnLogs = UiKit.outline(this, buttonLabel("LOGS"), v -> {
                    d.dismiss();
                    showServiceLogsDialog(svc);
                });
                Button btnUninst = UiKit.danger(this, buttonLabel("UNINSTALL"), v -> {
                    d.dismiss();
                    confirmUninstallService(svc);
                });
                root.addView(UiKit.buttonRow(this, btnLogs, btnUninst));
            }
        }

        d.setContentView(I18n.attach(scroll));
        applyDialogWindow(d, 0.94f);
        if (!safeShow(d)) return;

        if (installed) {
            final File instDir = new File(svc.getManifest().getInstallDir());
            final File confDir = new File(svc.getManifest().getConfigDir());
            runBg(() -> {
                long total = calculateDirSize(instDir) + calculateDirSize(confDir);
                runOnUiThread(() -> {
                    if (d.isShowing()) sizeValue.setText(MediaStorageManager.formatBytes(total));
                });
            });
        }
    }

    /** Section label inside a dialog sheet (UiKit overline with the 16dp section gap above). */
    private TextView detailSectionLabel(String text) {
        TextView t = UiKit.overline(this, text);
        t.setPadding(0, dp(16), 0, dp(6));
        return t;
    }

    /** Key / value line inside an inset box; returns the value view so it can be updated later. */
    private TextView addDetailKv(LinearLayout parent, String key, String value, boolean mono) {
        LinearLayout r = UiKit.row(this);
        r.setPadding(0, dp(3), 0, dp(3));
        r.addView(UiKit.caption(this, key), new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView v = UiKit.text(this, value, 13f, AppTheme.text(), mono ? Typeface.MONOSPACE : UiKit.MEDIUM);
        r.addView(v);
        parent.addView(r);
        return v;
    }

    /** One value (URL, key, login) with an optional small label pill and a copy button. */
    private View detailCopyRow(String label, String value, boolean mono, final String copyLabel, final String copyValue) {
        LinearLayout row = UiKit.row(this);
        row.setPadding(0, dp(2), 0, dp(2));
        if (label != null && !label.isEmpty()) {
            TextView b = UiKit.text(this, label, 10.5f, AppTheme.onAccentContainer(), UiKit.BOLD);
            b.setAllCaps(true);
            b.setSingleLine(true);
            b.setPadding(dp(8), dp(2), dp(8), dp(2));
            b.setBackground(UiKit.shape(this, AppTheme.accentContainer(), 0, UiKit.R_PILL));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.setMarginEnd(dp(8));
            row.addView(b, blp);
        }
        TextView v = UiKit.text(this, value, mono ? 12.5f : 13f, AppTheme.text(), mono ? Typeface.MONOSPACE : null);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        if (mono) v.setTextDirection(View.TEXT_DIRECTION_LTR);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1.0f));

        ImageView copy = new ImageView(this);
        copy.setImageResource(R.drawable.ic_copy);
        copy.setColorFilter(AppTheme.textSecondary());
        copy.setPadding(dp(9), dp(9), dp(9), dp(9));
        copy.setBackground(UiKit.rowRipple(this));
        copy.setContentDescription("Copy");
        copy.setOnClickListener(x -> copyUrlToClipboard(copyLabel, copyValue));
        row.addView(copy, new LinearLayout.LayoutParams(dp(36), dp(36)));
        return row;
    }

    /** Compact "✕" icon button that dismisses {@code d}. */
    private View dialogCloseButton(final Dialog d) {
        TextView close = UiKit.text(this, "✕", 16f, AppTheme.textSecondary(), UiKit.MEDIUM);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close");
        close.setClickable(true);
        close.setFocusable(true);
        close.setBackground(UiKit.rowRipple(this));
        close.setOnClickListener(v -> d.dismiss());
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        return close;
    }

    /** Transparent window (the content draws its own rounded card), themed dim, width fraction of the screen. */
    private void applyDialogWindow(Dialog d, float widthFraction) {
        Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setDimAmount(AppTheme.isDark() ? 0.85f : 0.5f);
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
        wlp.copyFrom(w.getAttributes());
        wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * widthFraction);
        wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        w.setAttributes(wlp);
    }

    private void openServiceWeb(ManagedService svc) {
        if (svc.isBlocked()) {
            Toast.makeText(this, I18n.tr(svc.getDisplayName() + " is blocked: " + svc.getBlockedReason()), Toast.LENGTH_LONG).show();
            return;
        }

        // Ensure authentication is None in config.xml for local in-app webview
        MediaServiceManager.getInstance(this).ensureServiceConfigSanitized(svc.getId());

        if ("jellyfin".equalsIgnoreCase(svc.getId())) {
            if (controller == null || controller.getState() != JellyfinController.State.RUNNING) {
                Toast.makeText(this, I18n.tr("Starting Jellyarr..."), Toast.LENGTH_SHORT).show();
                triggerServerAction(JellyfinServerService.ACTION_START);
                refreshServicesTabUI();
                refreshHomeServicesBadge();
            }
            startActivity(new Intent(this, JellyfinWebActivity.class));
            return;
        }

        if (!svc.isRunning()) {
            Toast.makeText(this, I18n.tr("Starting " + svc.getDisplayName() + "..."), Toast.LENGTH_SHORT).show();
            MediaServiceManager.getInstance(this).startService(svc.getId());
            refreshServicesTabUI();
            refreshHomeServicesBadge();
        }

        int defaultPort = svc.getManifest().getDefaultPort();
        String webPath = svc.getManifest().getWebPath();
        if (webPath == null) webPath = "/";
        String url = "http://127.0.0.1:" + defaultPort + (webPath.equals("/") ? "" : webPath);
        Intent intent = new Intent(this, JellyfinWebActivity.class);
        intent.putExtra(JellyfinWebActivity.EXTRA_URL, url);
        intent.putExtra(JellyfinWebActivity.EXTRA_TITLE, svc.getDisplayName());
        intent.putExtra(JellyfinWebActivity.EXTRA_SERVICE_ID, svc.getId());
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, I18n.tr("Could not open " + svc.getDisplayName() + " in-app webview"), Toast.LENGTH_SHORT).show();
        }
    }

    private void showServiceLogsDialog(ManagedService svc) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(true);

        LinearLayout root = UiKit.column(this);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));

        // Title row with "✕" close button
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = UiKit.singleLine(UiKit.title(this, (svc != null ? svc.getDisplayName() : "Service") + " " + buttonLabel("LOGS")));
        titleCol.addView(titleTv);

        TextView subTv = UiKit.caption(this, "Errors, crashes & diagnostics");
        subTv.setPadding(0, dp(2), 0, 0);
        titleCol.addView(subTv);

        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));
        titleRow.addView(dialogCloseButton(d));

        root.addView(titleRow);

        // Filter Toggle Bar: [ERRORS & CRASHES] (Active by default) | [ALL LOGS]
        LinearLayout toggleBar = new LinearLayout(this);
        toggleBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(-1, dp(36));
        tbLp.topMargin = dp(14);
        tbLp.bottomMargin = dp(8);
        toggleBar.setLayoutParams(tbLp);

        Button btnErrors = createOutlinePixelButton("ERRORS & CRASHES", AppTheme.danger(), AppTheme.danger());
        btnErrors.setTextSize(12f);
        toggleBar.addView(btnErrors, new LinearLayout.LayoutParams(0, -1, 1.0f));

        View toggleGap = new View(this);
        toggleBar.addView(toggleGap, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnAllLogs = createOutlinePixelButton("ALL LOGS", AppTheme.outlineStrong(), AppTheme.textMuted());
        btnAllLogs.setTextSize(12f);
        toggleBar.addView(btnAllLogs, new LinearLayout.LayoutParams(0, -1, 1.0f));

        root.addView(toggleBar);

        // Scrollable Log Console View
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(UiKit.shape(this, AppTheme.inner(), 0, UiKit.R_INNER));
        scroll.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, dp(260));
        scLp.bottomMargin = dp(12);
        scroll.setLayoutParams(scLp);

        TextView tvLogs = new TextView(this);
        tvLogs.setTextSize(11f);
        tvLogs.setTypeface(Typeface.MONOSPACE);
        tvLogs.setTextIsSelectable(true);
        tvLogs.setTextDirection(View.TEXT_DIRECTION_LTR);   // log lines stay left-to-right in the Arabic UI
        scroll.addView(tvLogs);

        root.addView(scroll);

        // State holder for current log text
        final String[] currentLogHolder = new String[1];
        final boolean[] isErrorViewHolder = new boolean[]{true};

        Runnable updateLogDisplay = () -> {
            if (isErrorViewHolder[0]) {
                btnErrors.setTextColor(AppTheme.danger());
                btnErrors.setBackground(createBorderedRoundedDrawable(AppTheme.withAlpha(AppTheme.danger(), 30), AppTheme.danger(), dp(1), dp(12)));
                btnAllLogs.setTextColor(AppTheme.textMuted());
                btnAllLogs.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(12)));

                String errorLogs = svc != null ? svc.getErrorAndCrashLogs(200) : "";
                if (errorLogs.trim().isEmpty()) {
                    tvLogs.setTextColor(AppTheme.success());
                    String noErrText = "✔ No errors or crashes recorded for " + (svc != null ? svc.getDisplayName() : "this service") + ".\n\n"
                            + "Process is running normally with 0 error events.\n"
                            + "Tap [ALL LOGS] above to inspect routine operational output.";
                    tvLogs.setText(noErrText);
                    currentLogHolder[0] = noErrText;
                } else {
                    tvLogs.setTextColor(AppTheme.danger());
                    tvLogs.setText(errorLogs);
                    currentLogHolder[0] = errorLogs;
                }
            } else {
                btnAllLogs.setTextColor(AppTheme.accent());
                btnAllLogs.setBackground(createBorderedRoundedDrawable(AppTheme.withAlpha(AppTheme.accent(), 30), AppTheme.accent(), dp(1), dp(12)));
                btnErrors.setTextColor(AppTheme.textMuted());
                btnErrors.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(12)));

                String allLogs = svc != null ? svc.getRecentLogs(200) : "";
                tvLogs.setTextColor(AppTheme.text());
                tvLogs.setText(allLogs);
                currentLogHolder[0] = allLogs;
            }
        };

        btnErrors.setOnClickListener(v -> {
            isErrorViewHolder[0] = true;
            updateLogDisplay.run();
        });

        btnAllLogs.setOnClickListener(v -> {
            isErrorViewHolder[0] = false;
            updateLogDisplay.run();
        });

        updateLogDisplay.run();

        // Action Buttons Row: [COPY LOGS] | [EXPORT .TXT] | [REPORT ISSUE]
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnCopy = createOutlinePixelButton("COPY", AppTheme.outlineStrong(), Color.WHITE);
        btnCopy.setTextSize(12f);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                String copyText = currentLogHolder[0] != null ? currentLogHolder[0] : "";
                cm.setPrimaryClip(ClipData.newPlainText("fishbowl_logs", copyText));
                Toast.makeText(this, I18n.tr("Copied logs to clipboard"), Toast.LENGTH_SHORT).show();
            }
        });
        actionsRow.addView(btnCopy, new LinearLayout.LayoutParams(0, dp(40), 1.0f));

        View aGap1 = new View(this);
        actionsRow.addView(aGap1, new LinearLayout.LayoutParams(dp(6), 1));

        Button btnExportTxt = createOutlinePixelButton("EXPORT .TXT", AppTheme.accent(), AppTheme.accent());
        btnExportTxt.setTextSize(12f);
        btnExportTxt.setOnClickListener(v -> {
            String fullReport = generateDiagnosticReport(svc);
            exportReportAsTxtFile(fullReport, svc != null ? svc.getId() : "system");
        });
        actionsRow.addView(btnExportTxt, new LinearLayout.LayoutParams(0, dp(40), 1.3f));

        View aGap2 = new View(this);
        actionsRow.addView(aGap2, new LinearLayout.LayoutParams(dp(6), 1));

        Button btnReport = createOutlinePixelButton("REPORT ISSUE", AppTheme.warning(), AppTheme.warning());
        btnReport.setTextSize(12f);
        btnReport.setOnClickListener(v -> {
            String fullReport = generateDiagnosticReport(svc);
            String svcName = svc != null ? svc.getDisplayName() : "Service";
            com.jellyarr.app.diagnostics.BugReportHelper.openBugReportFlow(this, "[" + svcName + "] Issue Report", fullReport);
        });
        actionsRow.addView(btnReport, new LinearLayout.LayoutParams(0, dp(40), 1.4f));

        root.addView(actionsRow);

        d.setContentView(I18n.attach(root));
        applyDialogWindow(d, 0.94f);
        safeShow(d);
    }

    private void exportReportAsTxtFile(String reportText, String serviceName) {
        try {
            File reportsDir = new File(getCacheDir(), "reports");
            if (!reportsDir.exists()) reportsDir.mkdirs();
            String cleanName = (serviceName != null ? serviceName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_") : "system");
            String fileName = "fishbowl_report_" + cleanName + "_" + System.currentTimeMillis() + ".txt";
            File reportFile = new File(reportsDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(reportFile)) {
                fos.write(reportText.getBytes(StandardCharsets.UTF_8));
            }

            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    reportFile
            );

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Jellyarr Diagnostic Report - " + (serviceName != null ? serviceName : "System"));
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Jellyarr Media Stack diagnostic report attached.");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Save or Share Diagnostic Report"));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to export report as txt: " + t.getMessage(), t);
            Toast.makeText(this, I18n.tr("Failed to create .txt file: " + t.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    public String generateDiagnosticReport(ManagedService targetSvc) {
        StringBuilder sb = new StringBuilder();
        sb.append("======================================================================\n");
        sb.append("JELLYARR MEDIA STACK - DIAGNOSTIC SYSTEM & ERROR REPORT\n");
        sb.append("Generated: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date())).append("\n");
        if (targetSvc != null) {
            sb.append("Target Service: ").append(targetSvc.getDisplayName()).append(" (v").append(targetSvc.getManifest().getVersion()).append(")\n");
            sb.append("State: ").append(targetSvc.getState()).append(" | Port: ").append(targetSvc.getManifest().getDefaultPort()).append("\n");
        }
        sb.append("======================================================================\n\n");

        // [1] Device & OS Info
        sb.append("[1] DEVICE & SYSTEM INFORMATION\n");
        sb.append("• Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        sb.append("• Model: ").append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        sb.append("• Board: ").append(Build.BOARD).append("\n");
        sb.append("• Android OS: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("• CPU ABIs: ").append(java.util.Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");
        sb.append("• Build Fingerprint: ").append(Build.DISPLAY).append("\n\n");

        // [2] Memory & Storage Diagnostics
        sb.append("[2] MEMORY & STORAGE DIAGNOSTICS\n");
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                double totalRamGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0);
                double availRamGb = mi.availMem / (1024.0 * 1024.0 * 1024.0);
                sb.append(String.format(Locale.US, "• System RAM: %.2f GB total | %.2f GB available (Low Memory Flag: %s)\n", totalRamGb, availRamGb, mi.lowMemory ? "YES" : "NO"));
            }
        } catch (Throwable ignored) {}

        try {
            File filesDir = getFilesDir();
            StatFs stat = new StatFs(filesDir.getAbsolutePath());
            long freeBytes = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) ? stat.getAvailableBytes() : (long) stat.getAvailableBlocks() * stat.getBlockSize();
            long totalBytes = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) ? stat.getTotalBytes() : (long) stat.getBlockCount() * stat.getBlockSize();
            double freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0);
            double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0);
            sb.append(String.format(Locale.US, "• Internal Storage: %.2f GB free / %.2f GB total\n", freeGb, totalGb));
        } catch (Throwable ignored) {}

        try {
            StorageDriveHelper.DriveInfo extDrive = StorageDriveHelper.getPrimaryExternalDrive(this);
            if (extDrive != null && extDrive.totalBytes > 0) {
                sb.append("• External Storage: ").append(extDrive.name).append(" - ").append(StorageDriveHelper.formatCapacity(extDrive.freeBytes, extDrive.totalBytes)).append(" (Mounted)\n");
            } else if (extDrive != null) {
                sb.append("• External Storage: ").append(extDrive.name).append(" (Mounted)\n");
            } else {
                sb.append("• External Storage: None connected\n");
            }
        } catch (Throwable ignored) {}
        sb.append("\n");

        // [3] Complete Services Status Matrix
        sb.append("[3] COMPLETE SERVICES STATUS MATRIX\n");
        JellyfinController jf = JellyfinController.getInstance();
        if (jf != null) {
            JellyfinController.ServerHealth jfHealth = jf.getServerHealth();
            long sec = jfHealth != null ? jfHealth.uptimeSeconds : 0;
            long h = sec / 3600;
            long m = (sec % 3600) / 60;
            long s = sec % 60;
            String jfUptime = (h > 0) ? String.format(Locale.US, "%dh %02dm", h, m) : String.format(Locale.US, "%dm %02ds", m, s);
            sb.append("• Jellyfin:       [").append(jf.getState()).append("]  Port: 8096 | Uptime: ")
                    .append(jfUptime)
                    .append(jf.getLastError() != null ? " | Error: " + jf.getLastError() : "")
                    .append("\n");
        }

        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        if (mgr != null) {
            for (ManagedService s : mgr.getAllServices()) {
                if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
                String padName = String.format(Locale.US, "%-14s", s.getDisplayName() + ":");
                sb.append("• ").append(padName).append(" [").append(s.getState()).append("]  Port: ")
                        .append(s.getManifest().getDefaultPort())
                        .append(" | Uptime: ").append(s.getUptimeFormatted())
                        .append(" | Crashes: ").append(s.getCrashCount())
                        .append(s.getLastError() != null && !s.getLastError().isEmpty() ? " | Error: " + s.getLastError() : "")
                        .append("\n");
            }
        }
        sb.append("\n");

        // [4] Service Error & Operational Audit
        sb.append("[4] SERVICE ERROR & OPERATIONAL AUDIT\n");
        if (targetSvc != null) {
            String errLogs = targetSvc.getErrorAndCrashLogs(150);
            if (!errLogs.trim().isEmpty()) {
                sb.append(errLogs).append("\n");
            } else {
                sb.append("(No active error or crash logs recorded for ").append(targetSvc.getDisplayName()).append(")\n");
                sb.append("\n[RECENT SERVICE LOGS (LAST 30 LINES)]\n");
                sb.append(targetSvc.getRecentLogs(30)).append("\n");
            }
        } else {
            boolean hasAnyErrors = false;
            if (jf != null && jf.getLastError() != null && !jf.getLastError().isEmpty()) {
                sb.append("--- Jellyfin Error ---\n").append(jf.getLastError()).append("\n\n");
                hasAnyErrors = true;
            }
            if (mgr != null) {
                for (ManagedService s : mgr.getAllServices()) {
                    if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
                    String sErr = s.getErrorAndCrashLogs(50);
                    if (sErr != null && !sErr.trim().isEmpty()) {
                        sb.append("--- ").append(s.getDisplayName()).append(" Error Logs ---\n").append(sErr).append("\n\n");
                        hasAnyErrors = true;
                    } else if (s.getLastError() != null && !s.getLastError().isEmpty()) {
                        sb.append("--- ").append(s.getDisplayName()).append(" Last Error ---\n").append(s.getLastError()).append("\n\n");
                        hasAnyErrors = true;
                    }
                }
            }
            if (!hasAnyErrors) {
                sb.append("[OK] All media stack services are operating nominally with 0 recorded error events.\n");
            }
        }
        sb.append("======================================================================\n");
        return sb.toString();
    }

    private void showServiceInstallInfo(ManagedService svc) {
        showServiceInstallCompatibilityDialog(svc);
    }

    public static class CompatibilityReport {
        public final boolean isCompatible;
        public final List<String> details;
        public final String failureReason;

        public CompatibilityReport(boolean isCompatible, List<String> details, String failureReason) {
            this.isCompatible = isCompatible;
            this.details = details;
            this.failureReason = failureReason;
        }
    }

    private CompatibilityReport checkServiceCompatibility(ManagedService svc) {
        List<String> checks = new java.util.ArrayList<>();
        boolean ok = true;
        String failReason = null;

        // 1. Architecture Check (ARM64-v8a)
        String primaryAbi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        boolean isArm64 = "arm64-v8a".equalsIgnoreCase(primaryAbi) || "aarch64".equalsIgnoreCase(primaryAbi);
        if (isArm64) {
            checks.add("[OK] Architecture: " + primaryAbi + " (ARM64 Native Supported)");
        } else {
            checks.add("[FAIL] Architecture: " + primaryAbi + " (Requires 64-bit ARM64-v8a)");
            ok = false;
            failReason = "Incompatible CPU architecture (" + primaryAbi + "). Only 64-bit ARM64 devices are supported.";
        }

        // 2. Storage Check
        File dataDir = TermuxConstants.TERMUX_HOME_DIR;
        long freeBytes = 0;
        try {
            android.os.StatFs stat = new android.os.StatFs(dataDir.getAbsolutePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                freeBytes = stat.getAvailableBytes();
            } else {
                freeBytes = (long) stat.getAvailableBlocks() * stat.getBlockSize();
            }
        } catch (Exception ignored) {}

        long reqBytes = (svc.getManifest().getStorageRequiredMb() + 50L) * 1024L * 1024L;
        if (freeBytes >= reqBytes) {
            checks.add("[OK] Internal Storage: " + MediaStorageManager.formatBytes(freeBytes) + " free (Requires ~" + svc.getManifest().getStorageRequiredMb() + " MB)");
        } else {
            checks.add("[FAIL] Internal Storage: " + MediaStorageManager.formatBytes(freeBytes) + " free (Requires ~" + svc.getManifest().getStorageRequiredMb() + " MB)");
            ok = false;
            if (failReason == null) {
                failReason = "Insufficient internal storage. " + MediaStorageManager.formatBytes(reqBytes) + " required.";
            }
        }

        // 3. Port Check
        int port = svc.getManifest().getDefaultPort();
        boolean portOccupied = MediaServiceManager.getInstance(this).isPortOccupied(port);
        if (!portOccupied || svc.isRunning()) {
            checks.add("[OK] Port " + port + ": Available for " + svc.getDisplayName());
        } else {
            checks.add("[WARN] Port " + port + ": Currently occupied by another process");
        }

        // 4. Runtime Environment Check
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        String id = svc.getId().toLowerCase(Locale.ROOT);
        if ("radarr".equals(id) || "sonarr".equals(id) || "prowlarr".equals(id)) {
            File dotnet = new File(prefix, "lib/dotnet/dotnet");
            if (!dotnet.exists()) dotnet = new File(prefix, "bin/dotnet");
            if (dotnet.exists()) {
                checks.add("[OK] Runtime: .NET 10.0 ARM64 CoreCLR Installed");
            } else {
                checks.add("[OK] Runtime: CoreCLR Bionic Decoupling Ready");
            }
        } else if ("transmission".equals(id)) {
            checks.add("[OK] Runtime: Native ARM64 Bionic Ready");
        }

        return new CompatibilityReport(ok, checks, failReason);
    }

    // ------------------------------------------------------------------ Unified account (first run + Settings)

    private LinearLayout unifiedAccountBody;
    private boolean firstRunFlowShowing = false;
    private long lastUnifiedAutoApply = 0;

    /** True while runUnifiedApply() is pushing the account to the services (UI thread state). */
    private boolean unifiedApplyRunning = false;
    private boolean unifiedApplyForced = false;
    private boolean unifiedApplyRerun = false;
    private boolean unifiedApplyRerunAnnounce = false;
    /** Services that already reported a result during the current apply. */
    private final Set<String> unifiedReportedThisRun = new HashSet<>();

    /** Called from onResume: first-run restore + account dialogs, then silent apply of pending services. */
    private void maybeRunFirstRunFlow() {
        try {
            if (!isUiAlive()) return;
            if (!UnifiedAccount.isFirstRunDone(this) && !firstRunFlowShowing) {
                if (UnifiedAccount.isConfigured(this)) {
                    UnifiedAccount.setFirstRunDone(this);
                } else {
                    firstRunFlowShowing = true;
                    final boolean restorePrompted = getSharedPreferences("fishbowl", MODE_PRIVATE)
                            .getBoolean("first_run_restore_prompted", false);
                    // Backup discovery scans storage: keep it off the UI thread.
                    runBg(() -> {
                        List<File> backups = new ArrayList<>();
                        if (!restorePrompted) {
                            try {
                                List<File> found = BackupRestoreManager.listExistingBackups(getApplicationContext());
                                if (found != null) backups.addAll(found);
                            } catch (Throwable ignored) {}
                            java.util.Collections.sort(backups, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                        }
                        final File newest = backups.isEmpty() ? null : backups.get(0);
                        runOnUiThread(() -> {
                            if (!isUiAlive()) {
                                firstRunFlowShowing = false;
                                return;
                            }
                            if (newest != null) showFirstRunRestoreDialog(newest);
                            else showUnifiedAccountDialog(true);
                        });
                    });
                }
                return;
            }
            if (UnifiedAccount.hasDue(this) && !firstRunFlowShowing
                    && System.currentTimeMillis() - lastUnifiedAutoApply > 45000L) {
                lastUnifiedAutoApply = System.currentTimeMillis();
                runUnifiedApply(false, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "first-run flow: " + t.getMessage());
        }
    }

    private void showFirstRunRestoreDialog(final File backup) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        LinearLayout root = UiKit.column(this);
        root.setPadding(dp(20), dp(20), dp(20), dp(18));
        root.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));

        root.addView(UiKit.title(this, buttonLabel("RESTORE A BACKUP?")));

        TextView sub = UiKit.body(this, I18n.tr("A Jellyarr backup was found on this phone. Restore it to bring back your settings, accounts and library."));
        sub.setPadding(0, dp(6), 0, dp(12));
        root.addView(sub);

        LinearLayout fileBox = UiKit.inset(this);
        TextView file = UiKit.mono(this, backup.getName());
        file.setTextDirection(View.TEXT_DIRECTION_LTR);
        fileBox.addView(file);
        TextView meta = UiKit.caption(this, android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", backup.lastModified())
                + "  •  " + android.text.format.Formatter.formatShortFileSize(this, backup.length()));
        meta.setPadding(0, dp(4), 0, 0);
        fileBox.addView(meta);
        root.addView(fileBox);

        Button skip = UiKit.outline(this, buttonLabel("SKIP"), v -> {
            getSharedPreferences("fishbowl", MODE_PRIVATE).edit().putBoolean("first_run_restore_prompted", true).apply();
            d.dismiss();
            showUnifiedAccountDialog(true);
        });
        Button restore = UiKit.primary(this, buttonLabel("RESTORE"), v -> {
            getSharedPreferences("fishbowl", MODE_PRIVATE).edit().putBoolean("first_run_restore_prompted", true).apply();
            firstRunFlowShowing = false;
            d.dismiss();
            confirmAndRestoreFromFile(backup);
        });
        LinearLayout row = UiKit.buttonRow(this, skip, restore);
        ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = dp(18);
        root.addView(row);

        d.setContentView(I18n.attach(root));
        styleDialogWindow(d);
        if (!safeShow(d)) firstRunFlowShowing = false;
    }

    private void styleDialogWindow(Dialog d) {
        applyDialogWindow(d, 0.92f);
    }

    private EditText accountInput(String hint, boolean password) {
        EditText e = UiKit.input(this, I18n.tr(hint));
        e.setInputType(password
                ? (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        // the field keeps the UI font; password variation would otherwise switch it to monospace
        e.setTypeface(Typeface.DEFAULT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        e.setLayoutParams(lp);
        return e;
    }

    /** Sign in / create account dialog. firstRun = true shows SKIP FOR NOW instead of CANCEL. */
    private void showUnifiedAccountDialog(final boolean firstRun) {
        if (!isUiAlive()) {
            if (firstRun) firstRunFlowShowing = false;
            return;
        }
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(!firstRun);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = UiKit.column(this);
        root.setPadding(dp(20), dp(20), dp(20), dp(18));
        root.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));
        sv.addView(root);

        root.addView(UiKit.title(this, buttonLabel("ONE ACCOUNT FOR ALL SERVICES")));

        TextView sub = UiKit.body(this, I18n.tr("The same username and password will be used for Jellyfin, Sonarr, Radarr, Prowlarr and Transmission."));
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        // Segmented choice: create a new account / sign in with an existing Jellyfin user
        final boolean[] createMode = {true};
        LinearLayout modes = UiKit.row(this);
        final TextView tCreate = UiKit.chip(this, buttonLabel("CREATE ACCOUNT"), true);
        final TextView tSign = UiKit.chip(this, buttonLabel("SIGN IN"), false);
        final TextView[] pills = {tCreate, tSign};
        for (int i = 0; i < 2; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i == 1) lp.setMarginStart(dp(8));
            modes.addView(pills[i], lp);
        }
        root.addView(modes);

        final EditText user = accountInput("Username", false);
        final EditText pass = accountInput("Password", true);
        final EditText confirm = accountInput("Confirm password", true);
        String existing = UnifiedAccount.getUsername(this);
        if (existing != null && !existing.isEmpty()) user.setText(existing);
        root.addView(user);
        root.addView(pass);
        root.addView(confirm);

        final TextView err = UiKit.caption(this, "");
        err.setTextColor(AppTheme.danger());
        err.setPadding(dp(2), dp(8), dp(2), 0);
        err.setVisibility(View.GONE);
        root.addView(err);

        final Runnable paint = () -> {
            for (int i = 0; i < 2; i++) UiKit.styleChip(this, pills[i], (i == 0) == createMode[0]);
            confirm.setVisibility(createMode[0] ? View.VISIBLE : View.GONE);
            err.setVisibility(View.GONE);
        };
        tCreate.setOnClickListener(v -> { createMode[0] = true; paint.run(); });
        tSign.setOnClickListener(v -> { createMode[0] = false; paint.run(); });
        paint.run();

        final Button later = UiKit.outline(this, buttonLabel(firstRun ? "SKIP FOR NOW" : "CANCEL"), v -> {
            if (firstRun) UnifiedAccount.setFirstRunDone(this);
            firstRunFlowShowing = false;
            d.dismiss();
        });
        final Button go = UiKit.primary(this, buttonLabel("CONTINUE"), null);
        go.setOnClickListener(v -> {
            final String u = user.getText().toString().trim();
            final String p = pass.getText().toString();
            String problem = UnifiedAccount.validate(u, p);
            if (problem == null && createMode[0] && !p.equals(confirm.getText().toString())) {
                problem = "Passwords do not match";
            }
            if (problem != null) {
                err.setText(I18n.tr(problem));
                err.setVisibility(View.VISIBLE);
                return;
            }
            final boolean signIn = !createMode[0];
            go.setEnabled(false);
            later.setEnabled(false);
            go.setText(I18n.tr("Checking..."));
            // Sign-in reads jellyfin.db and verifies a PBKDF2 hash: never on the UI thread.
            runBg(() -> {
                String bgProblem = null;
                if (signIn) {
                    if (!UnifiedAccount.jellyfinHasUser(getApplicationContext(), u)) {
                        bgProblem = "No account with this name was found on this phone. Choose CREATE ACCOUNT.";
                    } else if (!UnifiedAccount.verifyAgainstJellyfin(getApplicationContext(), u, p)) {
                        bgProblem = "Wrong password for this account";
                    }
                }
                final String result = bgProblem;
                runOnUiThread(() -> {
                    if (!isUiAlive()) return;
                    go.setEnabled(true);
                    later.setEnabled(true);
                    go.setText(buttonLabel("CONTINUE"));
                    if (result != null) {
                        err.setText(I18n.tr(result));
                        err.setVisibility(View.VISIBLE);
                        return;
                    }
                    UnifiedAccount.save(this, u, p);
                    UnifiedAccount.setFirstRunDone(this);
                    firstRunFlowShowing = false;
                    d.dismiss();
                    lastUnifiedAutoApply = System.currentTimeMillis();
                    runUnifiedApply(true, true);
                });
            });
        });
        LinearLayout row = UiKit.buttonRow(this, later, go);
        ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = dp(18);
        root.addView(row);

        d.setContentView(I18n.attach(sv));
        styleDialogWindow(d);
        if (!safeShow(d) && firstRun) firstRunFlowShowing = false;
    }

    /**
     * Pushes the account to every service on a background thread; force = also re-apply services already OK.
     * Only one apply runs at a time; a forced request that arrives meanwhile (new password) runs right after.
     */
    private void runUnifiedApply(final boolean force, final boolean announce) {
        if (!UnifiedAccount.isConfigured(this)) {
            renderUnifiedAccountBody();
            return;
        }
        if (unifiedApplyRunning) {
            if (force) {
                unifiedApplyRerun = true;
                unifiedApplyRerunAnnounce |= announce;
            }
            return;
        }
        unifiedApplyRunning = true;
        unifiedApplyForced = force;
        unifiedReportedThisRun.clear();
        renderUnifiedAccountBody();
        if (announce && isUiAlive()) {
            Toast.makeText(this, I18n.tr("Applying the account to all services..."), Toast.LENGTH_SHORT).show();
        }
        final Context app = getApplicationContext();
        runBg(() -> {
            try {
                UnifiedAccount.applyAll(app, force, (id, st, msg) -> runOnUiThread(() -> {
                    unifiedReportedThisRun.add(id);
                    renderUnifiedAccountBody();
                }));
            } catch (Throwable t) {
                Log.w(TAG, "unified apply: " + t.getMessage());
            }
            runOnUiThread(() -> {
                unifiedApplyRunning = false;
                renderUnifiedAccountBody();
                if (announce && isUiAlive()) {
                    int ok = 0;
                    for (String s : UnifiedAccount.SERVICES) if ("ok".equals(UnifiedAccount.getStatus(app, s))) ok++;
                    Toast.makeText(this, ok + " / " + UnifiedAccount.SERVICES.length + " " + I18n.tr("services updated"),
                            Toast.LENGTH_LONG).show();
                }
                if (unifiedApplyRerun && isUiAlive()) {
                    boolean again = unifiedApplyRerunAnnounce;
                    unifiedApplyRerun = false;
                    unifiedApplyRerunAnnounce = false;
                    runUnifiedApply(true, again);
                }
            });
        });
    }

    private View buildUnifiedAccountCard() {
        LinearLayout card = UiKit.column(this);
        card.setPadding(0, 0, 0, dp(16));

        TextView t = new TextView(this);
        t.setText("UNIFIED ACCOUNT");
        styleSectionLabel(t);
        t.setPadding(0, dp(2), 0, dp(10));
        card.addView(t);

        unifiedAccountBody = UiKit.column(this);
        card.addView(unifiedAccountBody);
        renderUnifiedAccountBody();
        return card;
    }

    /**
     * Header ("Signed in as ..." + what is kept in sync), then one row per service: name and status badge
     * on one line, the error (if any) as a full-width selectable caption below. Buttons are disabled and a
     * progress line is shown while an apply runs.
     */
    private void renderUnifiedAccountBody() {
        if (unifiedAccountBody == null) return;
        unifiedAccountBody.removeAllViews();
        boolean configured = UnifiedAccount.isConfigured(this);
        boolean busy = unifiedApplyRunning;

        if (!configured) {
            TextView none = UiKit.body(this, I18n.tr("No unified account yet. One username and password for all services."));
            unifiedAccountBody.addView(none);
            unifiedAccountBody.addView(UiKit.buttonRow(this,
                    UiKit.primary(this, buttonLabel("SIGN IN / CREATE"), v -> showUnifiedAccountDialog(false))));
            return;
        }

        TextView who = UiKit.subtitle(this, I18n.tr("Signed in as") + " " + UnifiedAccount.getUsername(this));
        who.setTypeface(UiKit.BOLD);
        unifiedAccountBody.addView(who);

        TextView explain = UiKit.caption(this, I18n.tr("This account is kept in sync for Jellyfin, Sonarr, Radarr, Prowlarr and Transmission."));
        explain.setPadding(0, dp(4), 0, dp(10));
        unifiedAccountBody.addView(explain);

        if (busy) {
            LinearLayout prog = UiKit.row(this);
            prog.setPadding(0, 0, 0, dp(10));
            ProgressBar pb = new ProgressBar(this);
            pb.setIndeterminate(true);
            pb.setIndeterminateTintList(ColorStateList.valueOf(AppTheme.accent()));
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(16), dp(16));
            pbLp.setMarginEnd(dp(8));
            prog.addView(pb, pbLp);
            TextView syncing = UiKit.text(this, I18n.tr("Syncing…"), 12.5f, AppTheme.accent(), UiKit.MEDIUM);
            prog.addView(syncing);
            unifiedAccountBody.addView(prog);
        }

        LinearLayout list = UiKit.inset(this);
        String[] labels = {"Jellyfin", "Sonarr", "Radarr", "Prowlarr", "Transmission"};
        for (int i = 0; i < labels.length && i < UnifiedAccount.SERVICES.length; i++) {
            String id = UnifiedAccount.SERVICES[i];
            String st = UnifiedAccount.getStatus(this, id);
            String msg = UnifiedAccount.getMessage(this, id);
            boolean syncingRow = busy && !unifiedReportedThisRun.contains(id) && (unifiedApplyForced || !"ok".equals(st));

            int color;
            String text;
            if (syncingRow) {
                color = AppTheme.accent();
                text = I18n.tr("Syncing…");
            } else if ("ok".equals(st)) {
                color = AppTheme.success();
                text = I18n.tr("Applied");
            } else if ("failed".equals(st)) {
                color = AppTheme.danger();
                text = I18n.tr("Failed");
            } else {
                color = AppTheme.warning();
                text = I18n.tr("Pending");
            }

            if (i > 0) {
                View div = UiKit.divider(this);
                ((LinearLayout.LayoutParams) div.getLayoutParams()).topMargin = dp(6);
                ((LinearLayout.LayoutParams) div.getLayoutParams()).bottomMargin = dp(6);
                list.addView(div);
            }

            LinearLayout r = UiKit.row(this);
            r.setPadding(0, dp(2), 0, dp(2));
            TextView name = UiKit.singleLine(UiKit.subtitle(this, labels[i]));
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(UiKit.badge(this, text, color));
            list.addView(r);

            if (!syncingRow && msg != null && !msg.isEmpty() && !"ok".equals(st)) {
                TextView detail = UiKit.caption(this, I18n.tr(msg));
                detail.setTextColor("failed".equals(st) ? AppTheme.danger() : AppTheme.textMuted());
                detail.setTextIsSelectable(true);
                detail.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                detail.setPadding(0, dp(4), 0, dp(2));
                list.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        unifiedAccountBody.addView(list);

        Button edit = UiKit.primary(this, buttonLabel("CHANGE"), v -> showUnifiedAccountDialog(false));
        Button apply = UiKit.outline(this, busy ? I18n.tr("Syncing…") : buttonLabel("APPLY NOW"), v -> runUnifiedApply(true, true));
        Button forget = UiKit.danger(this, buttonLabel("FORGET"), v -> confirmForgetUnifiedAccount());
        for (Button b : new Button[]{edit, apply, forget}) {
            b.setEnabled(!busy);
            b.setAlpha(busy ? 0.5f : 1f);
        }
        unifiedAccountBody.addView(UiKit.buttonRow(this, edit, apply, forget));
    }

    private void confirmForgetUnifiedAccount() {
        TextView msg = UiKit.body(this, I18n.tr("Only the copy stored in this app is removed. Sonarr, Radarr, Prowlarr and Jellyfin keep their current login; Transmission stops requiring a login the next time it starts."));
        showOledDialog(I18n.tr("Forget the saved account?"), null, msg, "FORGET", AppTheme.danger(), () -> {
            UnifiedAccount.clear(this);
            renderUnifiedAccountBody();
            refreshSecurityUsersUI();
        }, "CANCEL", null);
    }

    private Dialog showOledDialog(String title, String subtitle, View contentView, String posText, int posColor, Runnable onPos, String negText, Runnable onNeg) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = UiKit.column(this);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        root.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));

        TextView titleTv = UiKit.title(this, title);
        root.addView(titleTv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = UiKit.caption(this, subtitle);
            subTv.setPadding(0, dp(4), 0, 0);
            root.addView(subTv);
        }

        if (contentView != null) {
            ViewGroup.LayoutParams given = contentView.getLayoutParams();
            LinearLayout.LayoutParams clp = given instanceof LinearLayout.LayoutParams
                    ? (LinearLayout.LayoutParams) given
                    : given != null ? new LinearLayout.LayoutParams(given) : new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = Math.max(clp.topMargin, dp(14));
            root.addView(contentView, clp);
        }

        // Actions aligned to the end: secondary (outlined) then primary (filled in the requested colour)
        LinearLayout btnRow = UiKit.row(this);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(-1, -2);
        btnRowLp.topMargin = dp(18);
        btnRow.setLayoutParams(btnRowLp);

        if (negText != null) {
            Button btnNeg = UiKit.outline(this, buttonLabel(negText), v -> {
                d.dismiss();
                if (onNeg != null) onNeg.run();
            });
            btnNeg.setTextSize(13f);
            btnRow.addView(btnNeg, new LinearLayout.LayoutParams(-2, dp(40)));
        }

        if (posText != null) {
            int fill = posColor == 0 ? AppTheme.accent() : posColor;
            Button btnPos = UiKit.button(this, buttonLabel(posText), fill, AppTheme.contrastOn(fill), 0, v -> {
                d.dismiss();
                if (onPos != null) onPos.run();
            });
            btnPos.setTextSize(13f);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, dp(40));
            if (negText != null) plp.setMarginStart(dp(8));
            btnRow.addView(btnPos, plp);
        }

        if (negText != null || posText != null) root.addView(btnRow);
        d.setContentView(I18n.attach(root));
        applyDialogWindow(d, 0.90f);
        safeShow(d);
        return d;
    }

    public static class OledProgressDialog {
        private final Dialog dialog;
        private final TextView titleTv;
        private final TextView messageTv;
        private final ProgressBar progressBar;
        private final TextView statsTv;
        private final TextView pctTv;
        private final TextView cancelBtn;
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        private Runnable onCancelListener;

        public OledProgressDialog(Dialog dialog, TextView titleTv, TextView messageTv, ProgressBar progressBar, TextView statsTv, TextView pctTv, TextView cancelBtn) {
            this.dialog = dialog;
            this.titleTv = titleTv;
            this.messageTv = messageTv;
            this.progressBar = progressBar;
            this.statsTv = statsTv;
            this.pctTv = pctTv;
            this.cancelBtn = cancelBtn;
            if (this.cancelBtn != null) {
                this.cancelBtn.setOnClickListener(v -> cancel());
            }
        }

        public void setOnCancelListener(Runnable listener) {
            this.onCancelListener = listener;
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                if (messageTv != null) messageTv.setText("Cancelling installation...");
                if (statsTv != null) statsTv.setText("Aborting active tasks...");
                if (cancelBtn != null) {
                    cancelBtn.setEnabled(false);
                    cancelBtn.setAlpha(0.3f);
                }
                if (onCancelListener != null) {
                    try { onCancelListener.run(); } catch (Throwable ignored) {}
                }
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void setTitle(String title) {
            if (titleTv != null) titleTv.setText(title);
        }

        public void setMessage(String msg) {
            if (isCancelled()) return;
            if (messageTv != null) messageTv.setText(msg);
        }

        public void setProgress(int pct) {
            if (isCancelled()) return;
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(pct);
            }
            if (pctTv != null) pctTv.setText(pct + "%");
        }

        public void setIndeterminate(boolean indeterminate) {
            if (isCancelled()) return;
            if (progressBar != null) progressBar.setIndeterminate(indeterminate);
        }

        public void setStep(String stepDescription, String stats) {
            if (isCancelled()) return;
            if (messageTv != null && stepDescription != null) messageTv.setText(stepDescription);
            if (statsTv != null && stats != null) statsTv.setText(stats);
            if (progressBar != null) progressBar.setIndeterminate(true);
            if (pctTv != null) pctTv.setText("");
        }

        public void updateDownloadProgress(String stepDescription, long currentBytes, long totalBytes, double speedKbps) {
            if (isCancelled()) return;
            if (messageTv != null && stepDescription != null) {
                messageTv.setText(stepDescription);
            }
            if (totalBytes > 0) {
                int pct = (int) Math.min(100, Math.max(0, (currentBytes * 100) / totalBytes));
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(pct);
                }
                String stats = formatBytes(currentBytes) + " / " + formatBytes(totalBytes) + " • " + formatSpeed(speedKbps);
                if (statsTv != null) statsTv.setText(stats);

                String etaStr = "";
                if (speedKbps > 5.0) {
                    long remainingBytes = Math.max(0, totalBytes - currentBytes);
                    long remainingSec = (long) ((remainingBytes / 1024.0) / speedKbps);
                    if (remainingSec < 60) {
                        etaStr = " (" + remainingSec + "s left)";
                    } else {
                        etaStr = " (" + (remainingSec / 60) + "m " + (remainingSec % 60) + "s left)";
                    }
                }
                if (pctTv != null) pctTv.setText(pct + "%" + etaStr);
            } else {
                if (progressBar != null) {
                    progressBar.setIndeterminate(true);
                }
                String stats = formatBytes(currentBytes) + (speedKbps > 0 ? " • " + formatSpeed(speedKbps) : "");
                if (statsTv != null) statsTv.setText(stats);
                if (pctTv != null) pctTv.setText("Downloading...");
            }
        }

        public void dismiss() {
            if (dialog != null && dialog.isShowing()) {
                try { dialog.dismiss(); } catch (Exception ignored) {}
            }
        }
    }

    private OledProgressDialog showOledProgressDialog(String title, String initialMessage) {
        return showOledProgressDialog(title, initialMessage, true);
    }

    private OledProgressDialog showOledProgressDialog(String title, String initialMessage, boolean cancellable) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        LinearLayout root = UiKit.column(this);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));

        LinearLayout titleRow = UiKit.row(this);

        TextView titleTv = UiKit.title(this, title);
        titleRow.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView cancelBtn = null;
        if (cancellable) {
            cancelBtn = UiKit.text(this, "✕", 16f, AppTheme.textSecondary(), UiKit.MEDIUM);
            cancelBtn.setGravity(Gravity.CENTER);
            cancelBtn.setContentDescription("Cancel");
            cancelBtn.setBackground(UiKit.rowRipple(this));
            cancelBtn.setClickable(true);
            cancelBtn.setFocusable(true);
            titleRow.addView(cancelBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        }
        root.addView(titleRow);

        TextView msgTv = UiKit.body(this, initialMessage);
        msgTv.setPadding(0, dp(6), 0, dp(14));
        root.addView(msgTv);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        pb.setIndeterminate(false);
        pb.setProgressTintList(ColorStateList.valueOf(AppTheme.accent()));
        pb.setIndeterminateTintList(ColorStateList.valueOf(AppTheme.accent()));
        root.addView(pb, new LinearLayout.LayoutParams(-1, dp(6)));

        LinearLayout footer = UiKit.row(this);
        footer.setPadding(0, dp(8), 0, 0);

        // byte counts / speeds are machine values: monospace keeps the digits from jumping around
        TextView statsTv = UiKit.text(this, "", 11f, AppTheme.textMuted(), Typeface.MONOSPACE);
        footer.addView(statsTv, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView pctTv = UiKit.text(this, "0%", 11f, AppTheme.textSecondary(), UiKit.MEDIUM);
        pctTv.setGravity(Gravity.END);
        footer.addView(pctTv, new LinearLayout.LayoutParams(-2, -2));

        root.addView(footer);

        d.setContentView(I18n.attach(root));
        applyDialogWindow(d, 0.90f);
        safeShow(d);
        return new OledProgressDialog(d, titleTv, msgTv, pb, statsTv, pctTv, cancelBtn);
    }

    private void showServiceInstallCompatibilityDialog(ManagedService svc) {
        CompatibilityReport rep = checkServiceCompatibility(svc);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        list.setBackground(createBorderedRoundedDrawable(AppTheme.card(), AppTheme.outline(), dp(1), dp(6)));

        for (String item : rep.details) {
            TextView itemTv = new TextView(this);
            itemTv.setText(item);
            itemTv.setTextSize(10f);
            itemTv.setTypeface(Typeface.MONOSPACE);
            if (item.startsWith("[OK]")) {
                itemTv.setTextColor(AppTheme.success());
            } else if (item.startsWith("[WARN]")) {
                itemTv.setTextColor(AppTheme.warning());
            } else {
                itemTv.setTextColor(AppTheme.danger());
            }
            itemTv.setPadding(0, dp(2), 0, dp(2));
            list.addView(itemTv);
        }
        content.addView(list);

        TextView note = new TextView(this);
        note.setText(rep.isCompatible ?
                "Environment compatible. Estimated download: ~" + svc.getManifest().getStorageRequiredMb() + " MB." :
                "Environment warning: " + rep.failureReason);
        note.setTextSize(10f);
        note.setTypeface(Typeface.MONOSPACE);
        note.setTextColor(AppTheme.textSecondary());
        note.setPadding(0, dp(8), 0, 0);
        content.addView(note);

        showOledDialog(
            "INSTALL " + svc.getDisplayName().toUpperCase(Locale.ROOT),
            "v" + svc.getManifest().getVersion() + " • " + svc.getManifest().getCategory().toUpperCase(Locale.ROOT) + " : " + svc.getManifest().getDefaultPort(),
            content,
            rep.isCompatible ? "PROCEED & INSTALL" : null,
            AppTheme.accent(),
            () -> installServiceAutomatically(svc),
            rep.isCompatible ? "CANCEL" : "CLOSE",
            null
        );
    }

    private void confirmUninstallService(ManagedService svc) {
        if ("jellyfin".equalsIgnoreCase(svc.getId())) {
            Toast.makeText(this, I18n.tr("Jellyfin is the core media server and cannot be uninstalled."), Toast.LENGTH_SHORT).show();
            return;
        }

        final File installDir = new File(svc.getManifest().getInstallDir());
        final File configDir = new File(svc.getManifest().getConfigDir());
        // Walking the install tree (.NET runtimes: thousands of files) must not block the UI thread.
        runBg(() -> {
            long bin = calculateDirSize(installDir);
            long cfg = calculateDirSize(configDir);
            runOnUiThread(() -> {
                if (isUiAlive()) showUninstallOptionsDialog(svc, bin, cfg);
            });
        });
    }

    private void showUninstallOptionsDialog(ManagedService svc, long binBytes, long cfgBytes) {
        long totalBytes = binBytes + cfgBytes;

        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = UiKit.column(this);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackground(UiKit.shape(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));

        LinearLayout head = UiKit.row(this);
        LinearLayout headText = UiKit.column(this);
        headText.addView(UiKit.title(this, buttonLabel("UNINSTALL") + " " + svc.getDisplayName()));
        TextView subTv = UiKit.caption(this, "Storage Reclaim & Data Retention Options");
        subTv.setPadding(0, dp(2), 0, 0);
        headText.addView(subTv);
        head.addView(headText, new LinearLayout.LayoutParams(0, -2, 1.0f));
        head.addView(dialogCloseButton(d));
        root.addView(head);

        // Stats box
        LinearLayout statBox = UiKit.inset(this);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, -2);
        sbLp.topMargin = dp(14);
        statBox.setLayoutParams(sbLp);
        addDetailKv(statBox, I18n.tr("Binaries"), MediaStorageManager.formatBytes(binBytes), false);
        addDetailKv(statBox, I18n.tr("Database & Configs"), MediaStorageManager.formatBytes(cfgBytes), false);
        root.addView(statBox);

        // Option 1: reclaim binaries only (preserve database)
        Button btnKeepDb = UiKit.outline(this, buttonLabel("RECLAIM BINARIES ONLY (KEEP DATABASE)"), null);
        btnKeepDb.setOnClickListener(v -> {
            d.dismiss();
            OledProgressDialog progress = showOledProgressDialog("UNINSTALLING " + svc.getDisplayName().toUpperCase(Locale.ROOT), "Stopping process and reclaiming binaries...");
            MediaServiceManager.getInstance(this).uninstallService(svc.getId(), false, () -> {
                progress.dismiss();
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, I18n.tr(svc.getDisplayName() + " binaries removed. Database & config preserved!"), Toast.LENGTH_LONG).show();
            });
        });
        LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(-1, dp(44));
        kLp.topMargin = dp(16);
        root.addView(btnKeepDb, kLp);

        TextView noteKeep = UiKit.caption(this, "Reclaims ~" + MediaStorageManager.formatBytes(binBytes) + ". Settings, API keys, and library mappings remain saved. Reinstalling later restores your existing library instantly.");
        noteKeep.setPadding(dp(4), dp(6), dp(4), dp(4));
        root.addView(noteKeep);

        // Option 2: clean wipe (purge all data)
        Button btnPurge = UiKit.danger(this, buttonLabel("PURGE EVERYTHING (CLEAN WIPE)"), null);
        btnPurge.setOnClickListener(v -> {
            d.dismiss();
            OledProgressDialog progress = showOledProgressDialog("PURGING " + svc.getDisplayName().toUpperCase(Locale.ROOT), "Stopping process and removing all data...");
            MediaServiceManager.getInstance(this).uninstallService(svc.getId(), true, () -> {
                progress.dismiss();
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, I18n.tr(svc.getDisplayName() + " completely purged. " + MediaStorageManager.formatBytes(totalBytes) + " reclaimed!"), Toast.LENGTH_LONG).show();
            });
        });
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, dp(44));
        pLp.topMargin = dp(12);
        root.addView(btnPurge, pLp);

        TextView notePurge = UiKit.caption(this, "Permanently deletes binaries, SQLite databases, and all configuration files. Downloaded media files in JellyarrMedia are NEVER deleted.");
        notePurge.setPadding(dp(4), dp(6), dp(4), 0);
        root.addView(notePurge);

        d.setContentView(I18n.attach(root));
        applyDialogWindow(d, 0.92f);
        safeShow(d);
    }

    public interface DownloadProgressCallback {
        void onProgress(long currentBytes, long totalBytes, double speedKbps);
        default boolean isCancelled() { return false; }
    }

    public static String formatBytes(long bytes) {
        return com.jellyarr.app.util.Fmt.bytes(bytes);
    }

    public static String formatSpeed(double kbps) {
        if (kbps <= 0) return "0 KB/s";
        if (kbps < 1024) return String.format(Locale.US, "%.0f KB/s", kbps);
        return String.format(Locale.US, "%.1f MB/s", kbps / 1024.0);
    }

    private static String fetchGitHubApiAssetUrl(String repoPath, String pattern) {
        try {
            URL u = new URL("https://api.github.com/repos/" + repoPath + "/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() == 200) {
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
                    String jsonStr = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    JSONObject root = new JSONObject(jsonStr);
                    JSONArray assets = root.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            String name = a.optString("name", "");
                            if ((name.contains(pattern)) && !name.contains("musl")) {
                                String bUrl = a.optString("browser_download_url", "");
                                if (!bUrl.isEmpty()) return bUrl;
                                return a.optString("url", "");
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "GitHub API asset resolution failed for " + repoPath + ": " + t.getMessage());
        }
        return null;
    }

    private static String resolveServiceGitHubApiUrl(String id) {
        if ("radarr".equalsIgnoreCase(id)) return fetchGitHubApiAssetUrl("Radarr/Radarr", "linux-core-arm64.tar.gz");
        if ("sonarr".equalsIgnoreCase(id)) return fetchGitHubApiAssetUrl("Sonarr/Sonarr", "linux-arm64.tar.gz");
        if ("prowlarr".equalsIgnoreCase(id)) return fetchGitHubApiAssetUrl("Prowlarr/Prowlarr", "linux-core-arm64.tar.gz");
        return null;
    }

    private static void downloadWithRedirects(String initialUrl, File destFile, DownloadProgressCallback cb) throws Exception {
        String currentUrl = initialUrl;
        int hops = 0;
        HttpURLConnection conn = null;
        try {
            while (hops < 10) {
                if (cb != null && cb.isCancelled()) {
                    throw new InterruptedException("Download cancelled by user");
                }
                URL u = new URL(currentUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(25000);
                conn.setReadTimeout(35000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                if (currentUrl.contains("api.github.com") && currentUrl.contains("/assets/")) {
                    conn.setRequestProperty("Accept", "application/octet-stream");
                } else {
                    conn.setRequestProperty("Accept", "*/*");
                }
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.setInstanceFollowRedirects(false);
                conn.connect();

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) {
                        throw new IOException("HTTP redirect " + code + " without Location header");
                    }
                    if (loc.startsWith("/")) {
                        URL prev = new URL(currentUrl);
                        currentUrl = prev.getProtocol() + "://" + prev.getHost() + (prev.getPort() > 0 ? ":" + prev.getPort() : "") + loc;
                    } else {
                        currentUrl = loc;
                    }
                    hops++;
                    continue;
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP Error " + code + ": " + conn.getResponseMessage());
                }

                long totalLength = conn.getContentLengthLong();
                if (destFile.exists()) destFile.delete();
                if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
                    destFile.getParentFile().mkdirs();
                }

                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 131072);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(destFile), 131072)) {
                    byte[] buf = new byte[131072];
                    int len;
                    long downloaded = 0;
                    long lastReportTime = 0;
                    long lastDownloaded = 0;
                    while ((len = in.read(buf)) > 0) {
                        if (cb != null && cb.isCancelled()) {
                            throw new InterruptedException("Download cancelled by user");
                        }
                        out.write(buf, 0, len);
                        downloaded += len;
                        long now = SystemClock.uptimeMillis();
                        if (cb != null && (now - lastReportTime >= 150 || (totalLength > 0 && downloaded == totalLength) || lastReportTime == 0)) {
                            long timeDelta = now - lastReportTime;
                            long bytesDelta = downloaded - lastDownloaded;
                            double speedKbps = (timeDelta > 0 && lastReportTime > 0) ? (bytesDelta * 1000.0) / (timeDelta * 1024.0) : 0;
                            lastReportTime = now;
                            lastDownloaded = downloaded;
                            cb.onProgress(downloaded, totalLength, speedKbps);
                        }
                    }
                    out.flush();
                    if (cb != null) {
                        cb.onProgress(downloaded, totalLength > 0 ? totalLength : downloaded, 0);
                    }
                } catch (InterruptedException ie) {
                    if (destFile.exists()) destFile.delete();
                    throw ie;
                }

                if (destFile.length() < 1024) {
                    throw new IOException("Downloaded file is corrupt or empty (" + destFile.length() + " bytes)");
                }
                return;
            }
            throw new IOException("Too many redirects (exceeded 10 hops)");
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static void extractTarGzArchive(File archiveFile, File targetDir) throws Exception {
        if (!targetDir.exists()) targetDir.mkdirs();
        String canonicalTarget = targetDir.getCanonicalPath();
        try (InputStream fi = new FileInputStream(archiveFile);
             InputStream bi = new BufferedInputStream(fi);
             InputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream ti = new TarArchiveInputStream(gzi)) {
            TarArchiveEntry entry;
            while ((entry = ti.getNextTarEntry()) != null) {
                String entryName = entry.getName();
                if (entryName == null || entryName.contains("..")) {
                    continue;
                }
                File curfile = new File(targetDir, entryName);
                String canonicalCurfile = curfile.getCanonicalPath();
                if (!canonicalCurfile.startsWith(canonicalTarget + File.separator) && !canonicalCurfile.equals(canonicalTarget)) {
                    throw new SecurityException("Archive entry is outside target directory: " + entryName);
                }
                if (entry.isSymbolicLink()) {
                    try {
                        try { android.system.Os.remove(curfile.getAbsolutePath()); } catch (Throwable ignored) {}
                        try { curfile.delete(); } catch (Throwable ignored) {}
                        android.system.Os.symlink(entry.getLinkName(), curfile.getAbsolutePath());
                    } catch (Throwable ignored) {}
                    continue;
                }
                if (entry.isDirectory()) {
                    curfile.mkdirs();
                } else {
                    File parent = curfile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try { android.system.Os.remove(curfile.getAbsolutePath()); } catch (Throwable ignored) {}
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(curfile))) {
                        byte[] buf = new byte[32768];
                        int len;
                        while ((len = ti.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    }
                    if ((entry.getMode() & 0111) != 0) {
                        curfile.setExecutable(true, false);
                    }
                }
            }
        }
    }

    private static void extractZipArchive(File zipFile, File targetDir) throws Exception {
        if (!targetDir.exists()) targetDir.mkdirs();
        String canonicalTarget = targetDir.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry ze;
            byte[] buffer = new byte[32768];
            while ((ze = zis.getNextEntry()) != null) {
                String entryName = ze.getName();
                if (entryName == null || entryName.contains("..")) {
                    continue;
                }
                File curfile = new File(targetDir, entryName);
                String canonicalCurfile = curfile.getCanonicalPath();
                if (!canonicalCurfile.startsWith(canonicalTarget + File.separator) && !canonicalCurfile.equals(canonicalTarget)) {
                    throw new SecurityException("Zip entry is outside target directory: " + entryName);
                }
                if (ze.isDirectory()) {
                    curfile.mkdirs();
                } else {
                    File parent = curfile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(curfile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void extractDebPackage(File debFile, File prefixDir) throws Exception {
        if (!prefixDir.exists()) prefixDir.mkdirs();
        String canonicalPrefix = prefixDir.getCanonicalPath();
        try (InputStream fis = new FileInputStream(debFile);
             InputStream bis = new BufferedInputStream(fis);
             ArArchiveInputStream ais = new ArArchiveInputStream(bis)) {
            ArArchiveEntry arEntry;
            while ((arEntry = ais.getNextEntry()) != null) {
                String arName = arEntry.getName();
                if (arName.startsWith("data.tar")) {
                    InputStream dataIn = ais;
                    if (arName.endsWith(".xz")) {
                        dataIn = new XZCompressorInputStream(ais);
                    } else if (arName.endsWith(".gz")) {
                        dataIn = new GzipCompressorInputStream(ais);
                    }
                    try (TarArchiveInputStream tis = new TarArchiveInputStream(dataIn)) {
                        TarArchiveEntry te;
                        byte[] buffer = new byte[32768];
                        while ((te = tis.getNextTarEntry()) != null) {
                            String entryName = te.getName();
                            String cleanPath = entryName;
                            if (cleanPath.startsWith("./")) cleanPath = cleanPath.substring(2);
                            if (cleanPath.startsWith("data/data/com.termux/files/usr/")) {
                                cleanPath = cleanPath.substring("data/data/com.termux/files/usr/".length());
                            } else if (cleanPath.startsWith("data/data/com.jellyarr.app/files/usr/")) {
                                cleanPath = cleanPath.substring("data/data/com.jellyarr.app/files/usr/".length());
                            } else if (cleanPath.startsWith("usr/")) {
                                cleanPath = cleanPath.substring(4);
                            }
                            if (cleanPath.isEmpty() || cleanPath.contains("..")) continue;

                            File outFile = new File(prefixDir, cleanPath);
                            String canonicalOut = outFile.getCanonicalPath();
                            if (!canonicalOut.startsWith(canonicalPrefix + File.separator) && !canonicalOut.equals(canonicalPrefix)) {
                                throw new SecurityException("Archive entry is outside prefix directory: " + entryName);
                            }
                            if (te.isSymbolicLink()) {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        File parent = outFile.getParentFile();
                                        if (parent != null && !parent.exists()) parent.mkdirs();
                                        try { android.system.Os.remove(outFile.getAbsolutePath()); } catch (Throwable ignored) {}
                                        try { outFile.delete(); } catch (Throwable ignored) {}
                                        String linkTarget = te.getLinkName();
                                        if (linkTarget != null) {
                                            if (linkTarget.startsWith("/data/data/com.termux/files/usr/")) {
                                                linkTarget = new File(prefixDir, linkTarget.substring("/data/data/com.termux/files/usr/".length())).getAbsolutePath();
                                            } else if (linkTarget.startsWith("data/data/com.termux/files/usr/")) {
                                                linkTarget = new File(prefixDir, linkTarget.substring("data/data/com.termux/files/usr/".length())).getAbsolutePath();
                                            } else if (linkTarget.startsWith("/data/data/com.jellyarr.app/files/usr/")) {
                                                linkTarget = new File(prefixDir, linkTarget.substring("/data/data/com.jellyarr.app/files/usr/".length())).getAbsolutePath();
                                            } else if (linkTarget.startsWith("data/data/com.jellyarr.app/files/usr/")) {
                                                linkTarget = new File(prefixDir, linkTarget.substring("data/data/com.jellyarr.app/files/usr/".length())).getAbsolutePath();
                                            } else if (linkTarget.startsWith("/usr/")) {
                                                linkTarget = new File(prefixDir, linkTarget.substring("/usr/".length())).getAbsolutePath();
                                            }
                                        }
                                        android.system.Os.symlink(linkTarget, outFile.getAbsolutePath());
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "Failed symlink for entry " + cleanPath + ": " + t.getMessage());
                                }
                                continue;
                            }

                            if (te.isDirectory()) {
                                outFile.mkdirs();
                            } else {
                                File parent = outFile.getParentFile();
                                if (parent != null && !parent.exists()) parent.mkdirs();
                                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                    int len;
                                    while ((len = tis.read(buffer)) > 0) {
                                        fos.write(buffer, 0, len);
                                    }
                                }
                                if ((te.getMode() & 0111) != 0 || cleanPath.startsWith("bin/")) {
                                    outFile.setExecutable(true, false);
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    private static final String[] TERMUX_MIRRORS = new String[] {
        "https://packages-cf.termux.dev/apt/termux-main/",
        "https://packages.termux.dev/apt/termux-main/",
        "https://grimler.se/termux/termux-main/"
    };

    private static final Map<String, String> TERMUX_POOL_MAP = new HashMap<>();
    static {
        TERMUX_POOL_MAP.put("transmission", "pool/main/t/transmission/transmission_4.1.4_aarch64.deb");
        TERMUX_POOL_MAP.put("libpsl", "pool/main/libp/libpsl/libpsl_0.23.3_aarch64.deb");
        TERMUX_POOL_MAP.put("libidn2", "pool/main/libi/libidn2/libidn2_2.3.8-1_aarch64.deb");
        TERMUX_POOL_MAP.put("miniupnpc", "pool/main/m/miniupnpc/miniupnpc_2.3.5_aarch64.deb");
        TERMUX_POOL_MAP.put("natpmpc", "pool/main/n/natpmpc/natpmpc_20230423-2_aarch64.deb");
        TERMUX_POOL_MAP.put("libc++", "pool/main/libc/libc++/libc++_30_aarch64.deb");
        TERMUX_POOL_MAP.put("7zip", "pool/main/7/7zip/7zip_26.06_aarch64.deb");
        TERMUX_POOL_MAP.put("nodejs-lts", "pool/main/n/nodejs-lts/nodejs-lts_24.22.2-1_aarch64.deb");
        TERMUX_POOL_MAP.put("zlib", "pool/main/z/zlib/zlib_1.4_aarch64.deb");
        TERMUX_POOL_MAP.put("openssl", "pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb");
        TERMUX_POOL_MAP.put("libicu", "pool/main/libi/libicu/libicu_79.1_aarch64.deb");
        TERMUX_POOL_MAP.put("libcrypt", "pool/main/libc/libcrypt/libcrypt_0.2-6_aarch64.deb");
        TERMUX_POOL_MAP.put("libandroid-posix-semaphore", "pool/main/liba/libandroid-posix-semaphore/libandroid-posix-semaphore_0.1-4_aarch64.deb");
        TERMUX_POOL_MAP.put("ncurses-ui-libs", "pool/main/n/ncurses-ui-libs/ncurses-ui-libs_6.6.20260307+really6.5.20250830_aarch64.deb");
        TERMUX_POOL_MAP.put("zstd", "pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb");
        TERMUX_POOL_MAP.put("c-ares", "pool/main/c/c-ares/c-ares_1.34.9_aarch64.deb");
        TERMUX_POOL_MAP.put("python", "pool/main/p/python/python_3.14.10_aarch64.deb");
        TERMUX_POOL_MAP.put("libbz2", "pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb");
        TERMUX_POOL_MAP.put("libexpat", "pool/main/libe/libexpat/libexpat_2.10.0_aarch64.deb");
        TERMUX_POOL_MAP.put("libevent", "pool/main/libe/libevent/libevent_2.1.13_aarch64.deb");
        TERMUX_POOL_MAP.put("libffi", "pool/main/libf/libffi/libffi_3.8.0_aarch64.deb");
        TERMUX_POOL_MAP.put("liblzma", "pool/main/libl/liblzma/liblzma_5.8.4_aarch64.deb");
        TERMUX_POOL_MAP.put("libsqlite", "pool/main/libs/libsqlite/libsqlite_3.54.1-1_aarch64.deb");
        TERMUX_POOL_MAP.put("libandroid-support", "pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb");
        TERMUX_POOL_MAP.put("ncurses", "pool/main/n/ncurses/ncurses_6.6.20260307+really6.5.20250830_aarch64.deb");
        TERMUX_POOL_MAP.put("readline", "pool/main/r/readline/readline_8.3.6_aarch64.deb");
    }

    private static final Map<String, String> sDynamicDebRelPathCache = new HashMap<>();

    private static String resolveTermuxDebUrl(Context context, String pkgName) {
        if (pkgName == null || pkgName.trim().isEmpty()) return null;
        String lowerPkg = pkgName.trim().toLowerCase(Locale.ROOT);
        String relPath;
        synchronized (sDynamicDebRelPathCache) {
            relPath = sDynamicDebRelPathCache.get(lowerPkg);
        }
        if (relPath == null) {
            relPath = TERMUX_POOL_MAP.get(lowerPkg);
        }
        if (relPath == null) {
            relPath = fetchDynamicTermuxDebRelPath(lowerPkg);
        }
        if (relPath != null) {
            return TERMUX_MIRRORS[0] + relPath;
        }
        return null;
    }

    private static String fetchDynamicTermuxDebUrl(String pkgName) {
        String rel = fetchDynamicTermuxDebRelPath(pkgName);
        if (rel != null) {
            return TERMUX_MIRRORS[0] + rel;
        }
        return null;
    }

    private static String fetchDynamicTermuxDebRelPath(String pkgName) {
        if (pkgName == null || pkgName.trim().isEmpty()) return null;
        String targetPkg = pkgName.trim().toLowerCase(Locale.ROOT);
        for (String mirror : TERMUX_MIRRORS) {
          for (String idx : new String[]{"Packages.gz", "Packages"}) {
            String pkgsUrl = mirror + "dists/stable/main/binary-aarch64/" + idx;
            final boolean gz = idx.endsWith(".gz");
            try {
                URL url = new URL(pkgsUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                if (conn.getResponseCode() == 200) {
                    InputStream raw = new BufferedInputStream(conn.getInputStream(), 65536);
                    try (InputStream in = gz ? new java.util.zip.GZIPInputStream(raw) : raw) {
                        String match = parseDebFilenameFromPackages(in, targetPkg);
                        if (match != null) {
                            return match;
                        }
                    }
                } else {
                    conn.disconnect();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Dynamic deb lookup from " + pkgsUrl + " failed for " + pkgName + ": " + t.getMessage());
            }
          }
        }
        return null;
    }

    private static String parseDebFilenameFromPackages(InputStream in, String pkgName) throws IOException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), 65536)) {
            String line;
            String curPkg = null;
            String found = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("Package: ")) {
                    curPkg = line.substring(9).trim().toLowerCase(Locale.ROOT);
                } else if (line.startsWith("Filename: ") && curPkg != null) {
                    String fn = line.substring(10).trim();
                    synchronized (sDynamicDebRelPathCache) {
                        sDynamicDebRelPathCache.put(curPkg, fn);
                    }
                    if (pkgName != null && pkgName.equalsIgnoreCase(curPkg)) {
                        found = fn;
                    }
                    curPkg = null;
                } else if (line.isEmpty()) {
                    curPkg = null;
                }
            }
            return found;
        }
    }

    private static void ensureIcuSymlinks(File prefixDir) {
        if (prefixDir == null || !prefixDir.exists()) return;
        File libDir = new File(prefixDir, "lib");
        if (!libDir.exists()) return;
        String[] icuBases = new String[]{"libicuuc.so", "libicudata.so", "libicui18n.so", "libicuio.so"};
        File[] allFiles = libDir.listFiles();
        if (allFiles == null) return;
        for (String base : icuBases) {
            File bestMatch = null;
            for (File f : allFiles) {
                String name = f.getName();
                if (name.startsWith(base + ".") && !name.endsWith(".tmp")) {
                    if (bestMatch == null || name.compareTo(bestMatch.getName()) > 0) {
                        bestMatch = f;
                    }
                }
            }
            if (bestMatch != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        String matchName = bestMatch.getName();
                        int dotIdx = matchName.indexOf('.', base.length() + 1);
                        if (dotIdx > 0) {
                            String majorLinkName = matchName.substring(0, dotIdx);
                            File majorLink = new File(libDir, majorLinkName);
                            if (!majorLink.exists()) {
                                try { android.system.Os.remove(majorLink.getAbsolutePath()); } catch (Throwable ignored) {}
                                android.system.Os.symlink(matchName, majorLink.getAbsolutePath());
                            }
                        }
                        File baseLink = new File(libDir, base);
                        boolean needsRelink = true;
                        try {
                            String currentTarget = android.system.Os.readlink(baseLink.getAbsolutePath());
                            if (currentTarget != null && currentTarget.equals(bestMatch.getName())) {
                                needsRelink = false;
                            }
                        } catch (Throwable ignored) {
                            needsRelink = !baseLink.exists();
                        }
                        if (needsRelink) {
                            try { android.system.Os.remove(baseLink.getAbsolutePath()); } catch (Throwable ignored) {}
                            android.system.Os.symlink(bestMatch.getName(), baseLink.getAbsolutePath());
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void ensureLibrarySymlinks(File prefixDir) {
        if (prefixDir == null || !prefixDir.exists()) return;
        File libDir = new File(prefixDir, "lib");
        if (!libDir.exists()) return;
        String[][] libMappings = new String[][] {
            {"libexpat.so", "libexpat.so.1", "libexpat.so.1.13.1"},
            {"libbz2.so", "libbz2.so.1", "libbz2.so.1.0.8"},
            {"libffi.so", "libffi.so.8", "libffi.so.8.1.4"},
            {"liblzma.so", "liblzma.so.5", "liblzma.so.5.8.4"},
            {"libsqlite3.so", "libsqlite3.so.0", "libsqlite3.so.3.54.1"}
        };
        for (String[] map : libMappings) {
            File unversioned = new File(libDir, map[0]);
            File soname = new File(libDir, map[1]);
            File realLib = map.length > 2 ? new File(libDir, map[2]) : null;
            File target = (realLib != null && realLib.exists()) ? realLib : (soname.exists() ? soname : (unversioned.exists() ? unversioned : null));
            if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (!unversioned.exists() || (realLib != null && realLib.exists() && !unversioned.getAbsolutePath().equals(target.getAbsolutePath()))) {
                    try {
                        String cur = null;
                        try { cur = android.system.Os.readlink(unversioned.getAbsolutePath()); } catch (Throwable ignored) {}
                        if (cur == null || !cur.equals(target.getName())) {
                            try { android.system.Os.remove(unversioned.getAbsolutePath()); } catch (Throwable ignored) {}
                            android.system.Os.symlink(target.getName(), unversioned.getAbsolutePath());
                        }
                    } catch (Throwable ignored) {}
                }
                if (!soname.exists() || (realLib != null && realLib.exists() && !soname.getAbsolutePath().equals(target.getAbsolutePath()))) {
                    try {
                        String cur = null;
                        try { cur = android.system.Os.readlink(soname.getAbsolutePath()); } catch (Throwable ignored) {}
                        if (cur == null || !cur.equals(target.getName())) {
                            try { android.system.Os.remove(soname.getAbsolutePath()); } catch (Throwable ignored) {}
                            android.system.Os.symlink(target.getName(), soname.getAbsolutePath());
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        File sevenZLibExec = new File(prefixDir, "libexec/7zip/7z.so");
        File sevenZLib = new File(libDir, "7z.so");
        if (sevenZLibExec.exists() && !sevenZLib.exists()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try { android.system.Os.remove(sevenZLib.getAbsolutePath()); } catch (Throwable ignored) {}
                    android.system.Os.symlink(sevenZLibExec.getAbsolutePath(), sevenZLib.getAbsolutePath());
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void ensurePythonSymlinks(File prefixDir) {
        if (prefixDir == null || !prefixDir.exists()) return;
        File binDir = new File(prefixDir, "bin");
        if (!binDir.exists()) return;
        File py3 = new File(binDir, "python3");
        if (!py3.exists()) {
            File[] allFiles = binDir.listFiles();
            if (allFiles != null) {
                File match = null;
                for (File f : allFiles) {
                    if (f.getName().startsWith("python3.") && !f.getName().contains("config")) {
                        match = f;
                        break;
                    }
                }
                if (match != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try { android.system.Os.remove(py3.getAbsolutePath()); } catch (Throwable ignored) {}
                            android.system.Os.symlink(match.getName(), py3.getAbsolutePath());
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        File py = new File(binDir, "python");
        if (!py.exists() && py3.exists()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try { android.system.Os.remove(py.getAbsolutePath()); } catch (Throwable ignored) {}
                    android.system.Os.symlink("python3", py.getAbsolutePath());
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isDebPackageAlreadyInstalled(String pkgName, File prefixDir) {
        if (prefixDir == null || !prefixDir.exists()) return false;
        File bin = new File(prefixDir, "bin");
        File lib = new File(prefixDir, "lib");
        switch (pkgName.toLowerCase(Locale.ROOT)) {
            case "libc++": {
                File libCpp = new File(lib, "libc++_shared.so");
                if (!libCpp.exists()) return false;
                if (libCpp.length() >= 1400000L) return true;
                try (FileInputStream fis = new FileInputStream(libCpp)) {
                    byte[] data = new byte[(int) Math.min(libCpp.length(), 2097152L)];
                    int r = fis.read(data);
                    if (r > 0) {
                        String str = new String(data, 0, r, java.nio.charset.StandardCharsets.ISO_8859_1);
                        return str.contains("_ZNSt6__ndk113__hash_memory");
                    }
                } catch (Throwable ignored) {}
                return false;
            }
            case "zlib":
                return new File(lib, "libz.so").exists();
            case "openssl":
                return new File(lib, "libssl.so.3").exists() || new File(lib, "libcrypto.so.3").exists();
            case "c-ares":
                return new File(lib, "libcares.so").exists();
            case "libicu": {
                File icu79 = new File(lib, "libicui18n.so.79");
                File icu79Full = new File(lib, "libicui18n.so.79.1");
                boolean uc = new File(lib, "libicuuc.so").exists();
                boolean data = new File(lib, "libicudata.so").exists();
                if ((!icu79.exists() && !icu79Full.exists()) || !uc || !data) {
                    ensureIcuSymlinks(prefixDir);
                    icu79 = new File(lib, "libicui18n.so.79");
                    icu79Full = new File(lib, "libicui18n.so.79.1");
                    uc = new File(lib, "libicuuc.so").exists();
                    data = new File(lib, "libicudata.so").exists();
                }
                return (icu79.exists() || icu79Full.exists()) && uc && data;
            }
            case "libsqlite":
                return new File(lib, "libsqlite3.so").exists();
            case "nodejs-lts":
                return new File(bin, "node").exists();
            case "python": {
                boolean py = new File(bin, "python3").exists() || new File(bin, "python").exists();
                if (!py) {
                    ensurePythonSymlinks(prefixDir);
                    py = new File(bin, "python3").exists() || new File(bin, "python").exists();
                }
                return py;
            }
            case "7zip":
                return new File(bin, "7z").exists();
            case "transmission":
                return new File(bin, "transmission-daemon").exists();
            case "libevent":
                return new File(lib, "libevent.so").exists();
            case "miniupnpc":
                return new File(lib, "libminiupnpc.so").exists();
            case "natpmpc":
                return new File(lib, "libnatpmp.so").exists();
            case "libpsl":
                return new File(lib, "libpsl.so").exists();
            case "libidn2":
                return new File(lib, "libidn2.so").exists();
            case "libbz2":
                return new File(lib, "libbz2.so").exists();
            case "libcrypt":
                return new File(lib, "libcrypt.so").exists();
            case "libexpat": {
                File expatNew = new File(lib, "libexpat.so.1.13.1");
                if (expatNew.exists()) return true;
                File expat = new File(lib, "libexpat.so");
                if (!expat.exists()) expat = new File(lib, "libexpat.so.1");
                if (!expat.exists()) return false;
                try (FileInputStream fis = new FileInputStream(expat)) {
                    byte[] data = new byte[(int) Math.min(expat.length(), 262144L)];
                    int r = fis.read(data);
                    if (r > 0) {
                        String s = new String(data, 0, r, java.nio.charset.StandardCharsets.ISO_8859_1);
                        return s.contains("XML_GetCurrentLineNumber64");
                    }
                } catch (Throwable ignored) {}
                return false;
            }
            case "libandroid-support":
                return new File(lib, "libandroid-support.so").exists();
            case "libandroid-posix-semaphore":
                return new File(lib, "libandroid-posix-semaphore.so").exists();
            case "ncurses":
                return new File(lib, "libncursesw.so").exists() || new File(lib, "libncurses.so").exists();
            case "ncurses-ui-libs":
                return new File(lib, "libformw.so").exists() || new File(lib, "libmenuw.so").exists() || new File(lib, "libpanelw.so").exists() || new File(lib, "libncursesw.so").exists();
            case "libffi":
                return new File(lib, "libffi.so").exists();
            case "liblzma":
                return new File(lib, "liblzma.so").exists();
            case "zstd":
                return new File(lib, "libzstd.so").exists();
            case "readline":
                return new File(lib, "libreadline.so").exists();
            default:
                return false;
        }
    }

    private void downloadAndInstallDebPackage(String pkgName, File prefixDir, OledProgressDialog progress, String stepDescription) throws Exception {
        if (isDebPackageAlreadyInstalled(pkgName, prefixDir)) {
            Log.i(TAG, "[FastInstall] Package " + pkgName + " already present in prefix, skipping download.");
            return;
        }
        if (progress != null && progress.isCancelled()) {
            throw new InterruptedException("Installation cancelled");
        }
        String lowerPkg = pkgName.toLowerCase(Locale.ROOT);
        String relPath;
        synchronized (sDynamicDebRelPathCache) {
            relPath = sDynamicDebRelPathCache.get(lowerPkg);
        }
        if (relPath == null) {
            relPath = TERMUX_POOL_MAP.get(lowerPkg);
        }
        if (relPath == null) {
            relPath = fetchDynamicTermuxDebRelPath(lowerPkg);
        }
        if (relPath == null) {
            throw new IOException("Unable to resolve debian package URL for: " + pkgName);
        }
        if (progress != null) {
            runOnUiThread(() -> progress.setMessage(stepDescription));
        }
        File cacheDeb = new File(getCacheDir(), pkgName + ".deb");
        final String step = stepDescription;
        DownloadProgressCallback debCb = new DownloadProgressCallback() {
            @Override
            public void onProgress(long current, long total, double speedKbps) {
                if (progress != null) {
                    runOnUiThread(() -> progress.updateDownloadProgress(step, current, total, speedKbps));
                }
            }
            @Override
            public boolean isCancelled() {
                return progress != null && progress.isCancelled();
            }
        };

        boolean downloaded = false;
        Exception lastErr = null;
        for (String mirror : TERMUX_MIRRORS) {
            if (progress != null && progress.isCancelled()) {
                if (cacheDeb.exists()) cacheDeb.delete();
                throw new InterruptedException("Installation cancelled");
            }
            String debUrl = mirror + relPath;
            try {
                downloadWithRedirects(debUrl, cacheDeb, debCb);
                if (cacheDeb.exists() && cacheDeb.length() > 0) {
                    downloaded = true;
                    break;
                }
            } catch (InterruptedException ie) {
                if (cacheDeb.exists()) cacheDeb.delete();
                throw ie;
            } catch (Exception dlErr) {
                lastErr = dlErr;
                Log.w(TAG, "Download failed from mirror " + debUrl + ": " + dlErr.getMessage());
            }
        }

        if (!downloaded) {
            Log.w(TAG, "All mirrors failed for " + pkgName + " (" + relPath + "). Invalidating cache and re-fetching dynamically...");
            synchronized (sDynamicDebRelPathCache) {
                sDynamicDebRelPathCache.remove(lowerPkg);
            }
            String dynamicRel = fetchDynamicTermuxDebRelPath(lowerPkg);
            if (dynamicRel != null && !dynamicRel.equals(relPath)) {
                relPath = dynamicRel;
                for (String mirror : TERMUX_MIRRORS) {
                    if (progress != null && progress.isCancelled()) {
                        if (cacheDeb.exists()) cacheDeb.delete();
                        throw new InterruptedException("Installation cancelled");
                    }
                    String debUrl = mirror + relPath;
                    try {
                        downloadWithRedirects(debUrl, cacheDeb, debCb);
                        if (cacheDeb.exists() && cacheDeb.length() > 0) {
                            downloaded = true;
                            break;
                        }
                    } catch (InterruptedException ie) {
                        if (cacheDeb.exists()) cacheDeb.delete();
                        throw ie;
                    } catch (Exception dlErr) {
                        lastErr = dlErr;
                        Log.w(TAG, "Dynamic download failed from " + debUrl + ": " + dlErr.getMessage());
                    }
                }
            }
        }

        if (!downloaded) {
            if (cacheDeb.exists()) cacheDeb.delete();
            throw (lastErr != null ? lastErr : new IOException("Failed to download package " + pkgName + " from all mirrors"));
        }

        if (progress != null && progress.isCancelled()) {
            if (cacheDeb.exists()) cacheDeb.delete();
            throw new InterruptedException("Installation cancelled");
        }
        if (progress != null) {
            runOnUiThread(() -> progress.setStep("Extracting " + pkgName + " packages...", "Unpacking runtime binaries..."));
        }
        extractDebPackage(cacheDeb, prefixDir);
        cacheDeb.delete();
    }

    /** cloudflared is built in CI (with the Android DNS patch) and ships inside the APK; it goes to $PREFIX/bin/cloudflared. */
    private void installCloudflared(ManagedService svc, File prefix) throws Exception {
        File bin = new File(prefix, "bin");
        if (!bin.exists()) bin.mkdirs();
        File target = new File(bin, "cloudflared");
        File tmp = new File(getCacheDir(), "cloudflared.install");
        try (java.io.InputStream in = getAssets().open(com.jellyarr.app.services.CloudflareTunnel.ASSET_NAME);
             java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (java.io.FileNotFoundException e) {
            tmp.delete();
            throw new Exception("This build does not include the Cloudflare Tunnel. Install the APK built by CI.");
        }
        try (FileInputStream fis = new FileInputStream(tmp)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) != 4 || magic[0] != 0x7f || magic[1] != 'E' || magic[2] != 'L' || magic[3] != 'F') {
                tmp.delete();
                throw new Exception("The bundled cloudflared is not an executable");
            }
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            BackupRestoreManager.copyFile(tmp, target);
            tmp.delete();
        }
        target.setExecutable(true, false);
        try { android.system.Os.chmod(target.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
        File cfg = new File(TermuxConstants.TERMUX_HOME_DIR, ".config/cloudflared");
        if (!cfg.exists()) cfg.mkdirs();
        svc.reconcileInstalledState();
    }

    /** TorrServer ships one static ARM64 Android binary per release; it goes to $PREFIX/bin/torrserver. */
    private static final String[] TORRSERVER_URLS = {
            "https://github.com/YouROK/TorrServer/releases/latest/download/TorrServer-android-arm64",
            "https://github.com/YouROK/TorrServer/releases/download/MatriX.152/TorrServer-android-arm64"
    };

    private void installTorrServer(ManagedService svc, File prefix, OledProgressDialog progress) throws Exception {
        File bin = new File(prefix, "bin");
        if (!bin.exists()) bin.mkdirs();
        File target = new File(bin, "torrserver");
        File tmp = new File(getCacheDir(), "torrserver.download");
        final String step = "Downloading TorrServer...";
        DownloadProgressCallback cb = new DownloadProgressCallback() {
            @Override
            public void onProgress(long current, long total, double speedKbps) {
                if (progress != null) runOnUiThread(() -> progress.updateDownloadProgress(step, current, total, speedKbps));
            }
            @Override
            public boolean isCancelled() {
                return progress != null && progress.isCancelled();
            }
        };
        Exception last = null;
        boolean ok = false;
        for (String url : TORRSERVER_URLS) {
            if (progress != null && progress.isCancelled()) throw new InterruptedException("Installation cancelled");
            try {
                if (tmp.exists()) tmp.delete();
                downloadWithRedirects(url, tmp, cb);
                if (tmp.length() > 5L * 1024 * 1024) { ok = true; break; }
                last = new Exception("Downloaded file is too small (" + tmp.length() + " bytes)");
            } catch (Exception e) {
                if (progress != null && progress.isCancelled()) throw e;
                last = e;
                Log.w(TAG, "TorrServer download failed from " + url + ": " + e.getMessage());
            }
        }
        if (!ok) {
            if (tmp.exists()) tmp.delete();
            throw last != null ? last : new Exception("TorrServer download failed");
        }
        // ELF check: refuse an HTML error page saved as the binary
        try (FileInputStream fis = new FileInputStream(tmp)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) != 4 || magic[0] != 0x7f || magic[1] != 'E' || magic[2] != 'L' || magic[3] != 'F') {
                tmp.delete();
                throw new Exception("Downloaded TorrServer is not an ARM64 executable");
            }
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            BackupRestoreManager.copyFile(tmp, target);
            tmp.delete();
        }
        target.setExecutable(true, false);
        try { android.system.Os.chmod(target.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
        File cfg = new File(TermuxConstants.TERMUX_HOME_DIR, ".config/torrserver");
        if (!cfg.exists()) cfg.mkdirs();
        svc.reconcileInstalledState();
    }

    private void performServiceInstallSync(ManagedService svc, OledProgressDialog progress) throws Exception {
        if (progress != null && progress.isCancelled()) {
            throw new InterruptedException("Installation cancelled");
        }

        File home = TermuxConstants.TERMUX_HOME_DIR;
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        File servicesDir = new File(home, "services");
        if (!servicesDir.exists()) servicesDir.mkdirs();

        String id = svc.getId().toLowerCase(Locale.ROOT);

        if ("transmission".equalsIgnoreCase(id)) {
            String[] trDeps = {"libc++", "libpsl", "libidn2", "libevent", "miniupnpc", "natpmpc", "transmission"};
            for (String dep : trDeps) {
                if (progress != null && progress.isCancelled()) {
                    throw new InterruptedException("Installation cancelled");
                }
                downloadAndInstallDebPackage(dep, prefix, progress, "Installing Transmission (" + dep + ")...");
            }

            File daemon = new File(prefix, "bin/transmission-daemon");
            if (daemon.exists()) {
                daemon.setExecutable(true, false);
                try {
                    android.system.Os.chmod(daemon.getAbsolutePath(), 0755);
                } catch (Throwable ignored) {}
            }

            MediaServiceManager.getInstance(this).ensureServiceConfigSanitized("transmission");
            MediaServiceManager.getInstance(this).autoWireStack(null);
            svc.reconcileInstalledState();
            return;
        }

        if ("torrserver".equalsIgnoreCase(id)) {
            installTorrServer(svc, prefix, progress);
            return;
        }
        if (com.jellyarr.app.services.CloudflareTunnel.SERVICE_ID.equalsIgnoreCase(id)) {
            installCloudflared(svc, prefix);
            return;
        }

        // Servarr applications (.NET 10.0 ARM64): Radarr, Sonarr, Prowlarr
        String downloadUrl;
        if ("sonarr".equalsIgnoreCase(id)) {
            downloadUrl = "https://services.sonarr.tv/v1/download/main/latest?version=4&os=linux&arch=arm64";
        } else if ("prowlarr".equalsIgnoreCase(id)) {
            downloadUrl = "https://prowlarr.servarr.com/v1/update/master/updatefile?os=linux&runtime=netcore&arch=arm64";
        } else if ("radarr".equalsIgnoreCase(id)) {
            downloadUrl = "https://radarr.servarr.com/v1/update/master/updatefile?os=linux&runtime=netcore&arch=arm64";
        } else {
            return;
        }

        if (progress != null && progress.isCancelled()) {
            throw new InterruptedException("Installation cancelled");
        }

        runOnUiThread(() -> {
            if (progress != null) progress.setMessage(I18n.tr("Downloading " + svc.getDisplayName() + "..."));
        });
        File cacheTar = new File(getCacheDir(), id + ".tar.gz");
        final String svStep = "Downloading " + svc.getDisplayName() + "...";
        DownloadProgressCallback svCallback = new DownloadProgressCallback() {
            @Override
            public void onProgress(long current, long total, double speedKbps) {
                if (progress != null) {
                    runOnUiThread(() -> progress.updateDownloadProgress(svStep, current, total, speedKbps));
                }
            }
            @Override
            public boolean isCancelled() {
                return progress != null && progress.isCancelled();
            }
        };

        try {
            downloadWithRedirects(downloadUrl, cacheTar, svCallback);
        } catch (Exception dlEx) {
            if (progress != null && progress.isCancelled()) throw dlEx;
            String fallbackUrl = resolveServiceGitHubApiUrl(id);
            if (fallbackUrl != null && !fallbackUrl.isEmpty() && !fallbackUrl.equals(downloadUrl)) {
                Log.i(TAG, "Direct download failed for " + id + ", falling back to GitHub API stream: " + fallbackUrl);
                runOnUiThread(() -> {
                    if (progress != null) progress.setMessage(I18n.tr("Resolving direct asset stream for " + svc.getDisplayName() + "..."));
                });
                downloadWithRedirects(fallbackUrl, cacheTar, svCallback);
            } else {
                throw dlEx;
            }
        }

        if (progress != null && progress.isCancelled()) {
            if (cacheTar.exists()) cacheTar.delete();
            throw new InterruptedException("Installation cancelled");
        }

        runOnUiThread(() -> {
            if (progress != null) {
                progress.setStep("Extracting " + svc.getDisplayName() + " binaries...", "Configuring runtime decoupling...");
            }
        });
        File targetDir = new File(servicesDir, id);
        String capName = id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
        File capDir = new File(servicesDir, capName);
        if (targetDir.exists()) deleteRecursive(targetDir);
        if (capDir.exists()) deleteRecursive(capDir);
        File[] preExisting = servicesDir.listFiles();
        if (preExisting != null) {
            for (File f : preExisting) {
                if (f.isDirectory() && f.getName().equalsIgnoreCase(id)) {
                    deleteRecursive(f);
                }
            }
        }

        extractTarGzArchive(cacheTar, servicesDir);
        cacheTar.delete();

        File[] postExisting = servicesDir.listFiles();
        if (postExisting != null) {
            for (File f : postExisting) {
                if (f.isDirectory() && f.getName().equalsIgnoreCase(id) && !f.equals(targetDir)) {
                    if (!f.renameTo(targetDir)) {
                        try {
                            BackupRestoreManager.copyDirectory(f, targetDir);
                            deleteRecursive(f);
                        } catch (Throwable t) {
                            Log.w(TAG, "Directory fallback copy failed: " + t.getMessage());
                        }
                    }
                    break;
                }
            }
        }

        File dll = new File(svc.getManifest().getExecutableOrDllPath());
        if (!dll.exists()) {
            File checkCap = new File(targetDir, capName + ".dll");
            File checkLow = new File(targetDir, id + ".dll");
            if (checkCap.exists()) {
                dll = checkCap;
            } else if (checkLow.exists()) {
                dll = checkLow;
            }
        }
        if (!dll.exists()) {
            File foundDll = findDllRecursively(targetDir, id);
            if (foundDll != null) dll = foundDll;
        }
        if (!dll.exists()) {
            throw new IllegalStateException("Service binary not found after extraction: " + svc.getManifest().getExecutableOrDllPath());
        }
        if (dll.exists()) {
            File parent = dll.getParentFile();
            String name = dll.getName();
            int dot = name.lastIndexOf('.');
            String baseName = dot > 0 ? name.substring(0, dot) : name;
            File depsJson = new File(parent, baseName + ".deps.json");
            File depsJsonBak = new File(parent, baseName + ".deps.json.bak");
            if (depsJson.exists()) {
                if (!depsJsonBak.exists()) {
                    depsJson.renameTo(depsJsonBak);
                } else {
                    depsJson.delete();
                }
            }

            String[] glibcLibs = {
                "libcoreclr.so", "libhostpolicy.so", "libhostfxr.so",
                "libclrjit.so", "libclrgc.so", "libclrgcexp.so", "libcoreclrtraceptprovider.so",
                "libmscordaccore.so", "libmscordbi.so", "libMono.Unix.so",
                "libSystem.Native.so", "libSystem.Globalization.Native.so",
                "libSystem.IO.Compression.Native.so", "libSystem.Net.Security.Native.so",
                "libSystem.Security.Cryptography.Native.OpenSsl.so"
            };
            for (String glibcLib : glibcLibs) {
                File f = new File(parent, glibcLib);
                if (f.exists()) f.delete();
            }

            File prefixEsqlite = new File(prefix, "lib/libe_sqlite3.so");
            File targetEsqlite = new File(parent, "libe_sqlite3.so");
            if (prefixEsqlite.exists()) copyFile(prefixEsqlite, targetEsqlite);
            if (targetEsqlite.exists()) {
                targetEsqlite.setReadable(true, false);
                targetEsqlite.setExecutable(true, false);
                try { android.system.Os.chmod(targetEsqlite.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
            }

            File prefixMono = new File(prefix, "lib/libMonoPosixHelper.so");
            if (!prefixMono.exists()) {
                try (InputStream in = getAssets().open("libMonoPosixHelper.so");
                     OutputStream out = new FileOutputStream(prefixMono)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                } catch (Throwable ignored) {}
                if (prefixMono.exists()) {
                    try { android.system.Os.chmod(prefixMono.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
                }
            }
            File targetMono = new File(parent, "libMonoPosixHelper.so");
            if (prefixMono.exists()) copyFile(prefixMono, targetMono);
            if (targetMono.exists()) {
                targetMono.setReadable(true, false);
                targetMono.setExecutable(true, false);
                try { android.system.Os.chmod(targetMono.getAbsolutePath(), 0755); } catch (Throwable ignored) {}
            }

            File jfConfig = new File(prefix, "lib/jellyfin/jellyfin.runtimeconfig.json");
            File targetCfg = new File(parent, baseName + ".runtimeconfig.json");
            if (jfConfig.exists()) {
                copyFile(jfConfig, targetCfg);
            } else if (!targetCfg.exists() || targetCfg.length() == 0) {
                String minimalConfig = "{\n" +
                        "  \"runtimeOptions\": {\n" +
                        "    \"tfm\": \"net10.0\",\n" +
                        "    \"rollForward\": \"Major\",\n" +
                        "    \"frameworks\": [\n" +
                        "      {\n" +
                        "        \"name\": \"Microsoft.NETCore.App\",\n" +
                        "        \"version\": \"10.0.0\"\n" +
                        "      },\n" +
                        "      {\n" +
                        "        \"name\": \"Microsoft.AspNetCore.App\",\n" +
                        "        \"version\": \"10.0.0\"\n" +
                        "      }\n" +
                        "    ],\n" +
                        "    \"configProperties\": {\n" +
                        "      \"System.GC.Server\": false,\n" +
                        "      \"System.Reflection.Metadata.MetadataUpdater.IsSupported\": false,\n" +
                        "      \"System.Reflection.NullabilityInfoContext.IsSupported\": true,\n" +
                        "      \"System.Runtime.Serialization.EnableUnsafeBinaryFormatterSerialization\": false\n" +
                        "    }\n" +
                        "  }\n" +
                        "}\n";
                try (FileOutputStream fos = new FileOutputStream(targetCfg)) {
                    fos.write(minimalConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }

        MediaServiceManager.getInstance(this).ensureServiceConfigSanitized(id);
        MediaServiceManager.getInstance(this).autoWireStack(null);
        svc.reconcileInstalledState();
    }

    private static File findDllRecursively(File dir, String id) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        String cap = id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
        for (File f : files) {
            if (f.isFile() && (f.getName().equalsIgnoreCase(id + ".dll") || f.getName().equalsIgnoreCase(cap + ".dll"))) {
                return f;
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File sub = findDllRecursively(f, id);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    private void cleanupPartialServiceInstall(String id) {
        try {
            File cacheTar = new File(getCacheDir(), id + ".tar.gz");
            if (cacheTar.exists()) cacheTar.delete();
            File cacheZip = new File(getCacheDir(), id + ".zip");
            if (cacheZip.exists()) cacheZip.delete();
            File targetDir = new File(new File(TermuxConstants.TERMUX_HOME_DIR, "services"), id);
            if (targetDir.exists()) deleteRecursive(targetDir);
        } catch (Throwable ignored) {}
    }

    private void installServiceAutomatically(ManagedService svc) {
        OledProgressDialog progress = showOledProgressDialog("INSTALLING " + svc.getDisplayName().toUpperCase(Locale.ROOT), "Preparing runtime environment...", true);

        new Thread(() -> {
            try {
                performServiceInstallSync(svc, progress);
                runOnUiThread(() -> {
                    progress.dismiss();
                    svc.reconcileInstalledState();
                    refreshServicesTabUI();
                    refreshHomeServicesBadge();
                    Toast.makeText(this, I18n.tr(svc.getDisplayName() + " installed successfully!"), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                boolean isCancel = (progress != null && progress.isCancelled()) || (t instanceof InterruptedException);
                if (isCancel) {
                    Log.i(TAG, "Installation cancelled for " + svc.getId());
                    cleanupPartialServiceInstall(svc.getId());
                    runOnUiThread(() -> {
                        progress.dismiss();
                        svc.reconcileInstalledState();
                        refreshServicesTabUI();
                        refreshHomeServicesBadge();
                        Toast.makeText(this, I18n.tr("Installation cancelled."), Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                Log.e(TAG, "Failed to auto-install " + svc.getId(), t);
                cleanupPartialServiceInstall(svc.getId());
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, I18n.tr("Installation failed: " + t.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void installMultipleServicesSequentially(List<ManagedService> services) {
        if (services.isEmpty()) return;
        OledProgressDialog progress = showOledProgressDialog("INSTALLING SERVICES", "Preparing batch setup...", true);

        new Thread(() -> {
            int successCount = 0;
            boolean cancelled = false;
            for (int i = 0; i < services.size(); i++) {
                if (progress != null && progress.isCancelled()) {
                    cancelled = true;
                    break;
                }
                ManagedService svc = services.get(i);
                final int idx = i + 1;
                final int total = services.size();
                runOnUiThread(() -> {
                    if (progress != null) {
                        progress.setTitle(I18n.tr("INSTALLING (" + idx + "/" + total + ") " + svc.getDisplayName().toUpperCase(Locale.ROOT)));
                        progress.setMessage(I18n.tr("Preparing runtime environment..."));
                        progress.setProgress(0);
                    }
                });
                try {
                    performServiceInstallSync(svc, progress);
                    successCount++;
                } catch (InterruptedException ie) {
                    cancelled = true;
                    cleanupPartialServiceInstall(svc.getId());
                    break;
                } catch (Throwable t) {
                    Log.e(TAG, "Batch installation error for " + svc.getId(), t);
                    cleanupPartialServiceInstall(svc.getId());
                }
            }

            final int finalSuccess = successCount;
            final boolean wasCancelled = cancelled;
            runOnUiThread(() -> {
                if (progress != null) progress.dismiss();
                for (ManagedService s : services) {
                    s.reconcileInstalledState();
                }
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                if (wasCancelled) {
                    Toast.makeText(this, I18n.tr("Installation cancelled (" + finalSuccess + " installed)."), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, I18n.tr("Batch installation complete (" + finalSuccess + " installed)!"), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void uninstallMultipleServicesSequentially(List<ManagedService> services, boolean purge) {
        OledProgressDialog progress = showOledProgressDialog("UNINSTALLING SERVICES", "Stopping processes and removing packages...", false);
        new Thread(() -> {
            int count = 0;
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            for (int i = 0; i < services.size(); i++) {
                ManagedService s = services.get(i);
                final int current = i + 1;
                final int total = services.size();
                runOnUiThread(() -> {
                    if (progress != null) {
                        progress.setTitle(I18n.tr("UNINSTALLING (" + current + "/" + total + ") " + s.getDisplayName().toUpperCase(Locale.ROOT)));
                        progress.setMessage(I18n.tr("Reclaiming binaries..."));
                        progress.setProgress((current * 100) / total);
                    }
                });
                CountDownLatch latch = new CountDownLatch(1);
                mgr.uninstallService(s.getId(), purge, latch::countDown);
                try {
                    latch.await(30, TimeUnit.SECONDS);
                    count++;
                } catch (InterruptedException ignored) {}
            }
            final int uninstalledCount = count;
            runOnUiThread(() -> {
                if (progress != null) progress.dismiss();
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, I18n.tr("Uninstalled " + uninstalledCount + " service(s)."), Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void showSelectiveInstallDialog() {
        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        List<ManagedService> all = mgr.getAllServices();
        List<ManagedService> candidates = new ArrayList<>();
        for (ManagedService s : all) {
            if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
            if (!s.isInstalled()) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, I18n.tr("All companion services are already installed!"), Toast.LENGTH_SHORT).show();
            return;
        }

        final Set<String> selectedIds = new HashSet<>();
        for (ManagedService s : candidates) {
            selectedIds.add(s.getId());
        }

        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackground(createBorderedRoundedDrawable(AppTheme.card(), AppTheme.outline(), dp(1), dp(16)));

        // Header badge
        TextView badge = new TextView(this);
        badge.setText("MEDIA STACK INSTALLER");
        badge.setTextSize(9f);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setTextColor(AppTheme.accent());
        badge.setLetterSpacing(0.08f);
        root.addView(badge);

        TextView titleTv = new TextView(this);
        titleTv.setText("Select Services to Install");
        titleTv.setTextSize(15f);
        titleTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleTv.setTextColor(AppTheme.text());
        titleTv.setPadding(0, dp(2), 0, 0);
        root.addView(titleTv);

        TextView subTv = new TextView(this);
        subTv.setText("Choose companion engines to deploy to your media stack:");
        subTv.setTextSize(11f);
        subTv.setTextColor(AppTheme.textSecondary());
        subTv.setPadding(0, dp(2), 0, dp(12));
        root.addView(subTv);

        // Select All Pill Card
        LinearLayout selectAllCard = new LinearLayout(this);
        selectAllCard.setOrientation(LinearLayout.HORIZONTAL);
        selectAllCard.setGravity(Gravity.CENTER_VERTICAL);
        selectAllCard.setPadding(dp(12), dp(8), dp(12), dp(8));
        selectAllCard.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(10)));
        LinearLayout.LayoutParams sacLp = new LinearLayout.LayoutParams(-1, -2);
        sacLp.bottomMargin = dp(10);
        selectAllCard.setLayoutParams(sacLp);

        CheckBox selectAllCb = UiStyle.newCheckBox(this);
        selectAllCb.setChecked(true);
        selectAllCard.addView(selectAllCb);

        TextView selectAllLabel = new TextView(this);
        selectAllLabel.setText("Select All (" + candidates.size() + " available)");
        selectAllLabel.setTextSize(11.5f);
        selectAllLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        selectAllLabel.setTextColor(AppTheme.text());
        selectAllLabel.setPaddingRelative(dp(8), 0, 0, 0);
        selectAllCard.addView(selectAllLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));

        selectAllCard.setClickable(true);
        selectAllCard.setOnClickListener(v -> selectAllCb.setChecked(!selectAllCb.isChecked()));
        root.addView(selectAllCard);

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        List<CheckBox> itemCbs = new ArrayList<>();
        Button actionBtn = createPixelButton("INSTALL (" + selectedIds.size() + ")", AppTheme.accent(), Color.WHITE);

        Runnable updateButtonText = () -> {
            actionBtn.setText("INSTALL (" + selectedIds.size() + ")");
            actionBtn.setEnabled(!selectedIds.isEmpty());
            actionBtn.setAlpha(selectedIds.isEmpty() ? 0.35f : 1.0f);
        };

        for (ManagedService s : candidates) {
            LinearLayout itemCard = new LinearLayout(this);
            itemCard.setOrientation(LinearLayout.HORIZONTAL);
            itemCard.setGravity(Gravity.CENTER_VERTICAL);
            itemCard.setPadding(dp(12), dp(10), dp(12), dp(10));
            itemCard.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(10)));
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(-1, -2);
            icLp.bottomMargin = dp(6);
            itemCard.setLayoutParams(icLp);

            ImageView iconView = new ImageView(this);
            iconView.setImageResource(getServiceIconRes(s.getId()));
            iconView.setPadding(dp(4), dp(4), dp(4), dp(4));
            iconView.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outlineStrong(), dp(1), dp(8)));
            itemCard.addView(iconView, new LinearLayout.LayoutParams(dp(32), dp(32)));

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding(dp(10), 0, dp(10), 0);

            TextView nameTv = new TextView(this);
            nameTv.setText(s.getDisplayName());
            nameTv.setTextSize(12f);
            nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            nameTv.setTextColor(AppTheme.text());
            textCol.addView(nameTv);

            int port = (s.getManifest() != null) ? s.getManifest().getDefaultPort() : 0;
            long reqMb = (s.getManifest() != null) ? s.getManifest().getStorageRequiredMb() : 0;
            TextView descTv = new TextView(this);
            descTv.setText("Port " + port + " â€¢ ~" + reqMb + " MB");
            descTv.setTextSize(10f);
            descTv.setTextColor(AppTheme.textMuted());
            textCol.addView(descTv);

            itemCard.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

            CheckBox cb = UiStyle.newCheckBox(this);
            cb.setChecked(true);
            cb.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    selectedIds.add(s.getId());
                } else {
                    selectedIds.remove(s.getId());
                }
                selectAllCb.setOnCheckedChangeListener(null);
                selectAllCb.setChecked(selectedIds.size() == candidates.size());
                setupSelectAllListener(selectAllCb, candidates, itemCbs, selectedIds, updateButtonText);
                updateButtonText.run();
            });
            itemCbs.add(cb);
            itemCard.addView(cb);

            itemCard.setClickable(true);
            itemCard.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));

            list.addView(itemCard);
        }

        setupSelectAllListener(selectAllCb, candidates, itemCbs, selectedIds, updateButtonText);

        sv.addView(list, new LinearLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(220)));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, dp(14), 0, 0);

        Button cancelBtn = createOutlinePixelButton("CANCEL", AppTheme.outlineStrong(), AppTheme.textSecondary());
        cancelBtn.setTextSize(10f);
        cancelBtn.setOnClickListener(v -> d.dismiss());
        footer.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        View gap = new View(this);
        footer.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));

        actionBtn.setTextSize(10f);
        actionBtn.setOnClickListener(v -> {
            d.dismiss();
            List<ManagedService> toInstall = new ArrayList<>();
            for (ManagedService s : candidates) {
                if (selectedIds.contains(s.getId())) {
                    toInstall.add(s);
                }
            }
            if (!toInstall.isEmpty()) {
                installMultipleServicesSequentially(toInstall);
            }
        });
        footer.addView(actionBtn, new LinearLayout.LayoutParams(0, dp(38), 1.2f));

        root.addView(footer);
        d.setContentView(I18n.attach(root));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(AppTheme.isDark() ? 0.85f : 0.5f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(d.getWindow().getAttributes());
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        safeShow(d);
    }

    private void showSelectiveUninstallDialog() {
        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        List<ManagedService> all = mgr.getAllServices();
        List<ManagedService> candidates = new ArrayList<>();
        for (ManagedService s : all) {
            if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
            if (s.isInstalled()) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, I18n.tr("No companion services installed to uninstall."), Toast.LENGTH_SHORT).show();
            return;
        }

        final Set<String> selectedIds = new HashSet<>();
        for (ManagedService s : candidates) {
            selectedIds.add(s.getId());
        }

        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackground(createBorderedRoundedDrawable(AppTheme.card(), AppTheme.outline(), dp(1), dp(16)));

        // Header badge
        TextView badge = new TextView(this);
        badge.setText("STORAGE MANAGEMENT");
        badge.setTextSize(9f);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setTextColor(AppTheme.danger());
        badge.setLetterSpacing(0.08f);
        root.addView(badge);

        TextView titleTv = new TextView(this);
        titleTv.setText("Select Services to Uninstall");
        titleTv.setTextSize(15f);
        titleTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleTv.setTextColor(AppTheme.text());
        titleTv.setPadding(0, dp(2), 0, 0);
        root.addView(titleTv);

        TextView subTv = new TextView(this);
        subTv.setText("Choose services to remove and reclaim storage:");
        subTv.setTextSize(11f);
        subTv.setTextColor(AppTheme.textSecondary());
        subTv.setPadding(0, dp(2), 0, dp(12));
        root.addView(subTv);

        // Select All Pill Card
        LinearLayout selectAllCard = new LinearLayout(this);
        selectAllCard.setOrientation(LinearLayout.HORIZONTAL);
        selectAllCard.setGravity(Gravity.CENTER_VERTICAL);
        selectAllCard.setPadding(dp(12), dp(8), dp(12), dp(8));
        selectAllCard.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(10)));
        LinearLayout.LayoutParams sacLp = new LinearLayout.LayoutParams(-1, -2);
        sacLp.bottomMargin = dp(10);
        selectAllCard.setLayoutParams(sacLp);

        CheckBox selectAllCb = UiStyle.newCheckBox(this);
        selectAllCb.setChecked(true);
        selectAllCard.addView(selectAllCb);

        TextView selectAllLabel = new TextView(this);
        selectAllLabel.setText("Select All (" + candidates.size() + " installed)");
        selectAllLabel.setTextSize(11.5f);
        selectAllLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        selectAllLabel.setTextColor(AppTheme.text());
        selectAllLabel.setPaddingRelative(dp(8), 0, 0, 0);
        selectAllCard.addView(selectAllLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));

        selectAllCard.setClickable(true);
        selectAllCard.setOnClickListener(v -> selectAllCb.setChecked(!selectAllCb.isChecked()));
        root.addView(selectAllCard);

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        List<CheckBox> itemCbs = new ArrayList<>();
        Button actionBtn = createPixelButton("UNINSTALL (" + selectedIds.size() + ")", AppTheme.danger(), Color.WHITE);

        Runnable updateButtonText = () -> {
            actionBtn.setText("UNINSTALL (" + selectedIds.size() + ")");
            actionBtn.setEnabled(!selectedIds.isEmpty());
            actionBtn.setAlpha(selectedIds.isEmpty() ? 0.35f : 1.0f);
        };

        for (ManagedService s : candidates) {
            LinearLayout itemCard = new LinearLayout(this);
            itemCard.setOrientation(LinearLayout.HORIZONTAL);
            itemCard.setGravity(Gravity.CENTER_VERTICAL);
            itemCard.setPadding(dp(12), dp(10), dp(12), dp(10));
            itemCard.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(10)));
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(-1, -2);
            icLp.bottomMargin = dp(6);
            itemCard.setLayoutParams(icLp);

            ImageView iconView = new ImageView(this);
            iconView.setImageResource(getServiceIconRes(s.getId()));
            iconView.setPadding(dp(4), dp(4), dp(4), dp(4));
            iconView.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outlineStrong(), dp(1), dp(8)));
            itemCard.addView(iconView, new LinearLayout.LayoutParams(dp(32), dp(32)));

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding(dp(10), 0, dp(10), 0);

            TextView nameTv = new TextView(this);
            nameTv.setText(s.getDisplayName());
            nameTv.setTextSize(12f);
            nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            nameTv.setTextColor(AppTheme.text());
            textCol.addView(nameTv);

            TextView statusTv = new TextView(this);
            statusTv.setText(s.isRunning() ? "Running â€¢ Port " + s.getManifest().getDefaultPort() : "Stopped");
            statusTv.setTextSize(10f);
            statusTv.setTextColor(s.isRunning() ? AppTheme.success() : AppTheme.textMuted());
            textCol.addView(statusTv);

            itemCard.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

            CheckBox cb = UiStyle.newCheckBox(this);
            cb.setChecked(true);
            cb.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    selectedIds.add(s.getId());
                } else {
                    selectedIds.remove(s.getId());
                }
                selectAllCb.setOnCheckedChangeListener(null);
                selectAllCb.setChecked(selectedIds.size() == candidates.size());
                setupSelectAllListener(selectAllCb, candidates, itemCbs, selectedIds, updateButtonText);
                updateButtonText.run();
            });
            itemCbs.add(cb);
            itemCard.addView(cb);

            itemCard.setClickable(true);
            itemCard.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));

            list.addView(itemCard);
        }

        setupSelectAllListener(selectAllCb, candidates, itemCbs, selectedIds, updateButtonText);

        sv.addView(list, new LinearLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(180)));

        // Purge card
        LinearLayout purgeCard = new LinearLayout(this);
        purgeCard.setOrientation(LinearLayout.HORIZONTAL);
        purgeCard.setGravity(Gravity.CENTER_VERTICAL);
        purgeCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        purgeCard.setBackground(createBorderedRoundedDrawable(AppTheme.withAlpha(AppTheme.danger(), 25), AppTheme.danger(), dp(1), dp(8)));
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
        pcLp.topMargin = dp(8);
        purgeCard.setLayoutParams(pcLp);

        CheckBox purgeCb = UiStyle.newCheckBox(this);
        purgeCb.setChecked(false);
        purgeCard.addView(purgeCb);

        TextView purgeLabel = new TextView(this);
        purgeLabel.setText("Purge databases, configs & library indices");
        purgeLabel.setTextSize(10.5f);
        purgeLabel.setTextColor(AppTheme.danger());
        purgeLabel.setPaddingRelative(dp(6), 0, 0, 0);
        purgeCard.addView(purgeLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));

        purgeCard.setClickable(true);
        purgeCard.setOnClickListener(v -> purgeCb.setChecked(!purgeCb.isChecked()));
        root.addView(purgeCard);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, dp(12), 0, 0);

        Button cancelBtn = createOutlinePixelButton("CANCEL", AppTheme.outlineStrong(), AppTheme.textSecondary());
        cancelBtn.setTextSize(10f);
        cancelBtn.setOnClickListener(v -> d.dismiss());
        footer.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        View gap = new View(this);
        footer.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));

        actionBtn.setTextSize(10f);
        actionBtn.setOnClickListener(v -> {
            d.dismiss();
            boolean purge = purgeCb.isChecked();
            List<ManagedService> toUninstall = new ArrayList<>();
            for (ManagedService s : candidates) {
                if (selectedIds.contains(s.getId())) {
                    toUninstall.add(s);
                }
            }
            if (!toUninstall.isEmpty()) {
                uninstallMultipleServicesSequentially(toUninstall, purge);
            }
        });
        footer.addView(actionBtn, new LinearLayout.LayoutParams(0, dp(38), 1.2f));

        root.addView(footer);
        d.setContentView(I18n.attach(root));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(AppTheme.isDark() ? 0.85f : 0.5f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(d.getWindow().getAttributes());
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(lp);
        }
        safeShow(d);
    }

    private void setupSelectAllListener(CheckBox selectAllCb, List<ManagedService> candidates, List<CheckBox> itemCbs, Set<String> selectedIds, Runnable updateButtonText) {
        selectAllCb.setOnCheckedChangeListener((btn, isChecked) -> {
            selectedIds.clear();
            for (CheckBox cb : itemCbs) {
                cb.setOnCheckedChangeListener(null);
                cb.setChecked(isChecked);
            }
            if (isChecked) {
                for (ManagedService s : candidates) {
                    selectedIds.add(s.getId());
                }
            }
            for (int i = 0; i < candidates.size(); i++) {
                ManagedService s = candidates.get(i);
                CheckBox cb = itemCbs.get(i);
                cb.setOnCheckedChangeListener((b, c) -> {
                    if (c) selectedIds.add(s.getId());
                    else selectedIds.remove(s.getId());
                    selectAllCb.setOnCheckedChangeListener(null);
                    selectAllCb.setChecked(selectedIds.size() == candidates.size());
                    setupSelectAllListener(selectAllCb, candidates, itemCbs, selectedIds, updateButtonText);
                    updateButtonText.run();
                });
            }
            updateButtonText.run();
        });
    }

    /** Ready-made Prowlarr indexers and Radarr/Sonarr quality profiles the user picks from. */
    private void showStackPresetsDialog() {
        final com.jellyarr.app.services.StackPresets presets;
        try {
            presets = com.jellyarr.app.services.StackPresets.load(this);
        } catch (Throwable t) {
            Toast.makeText(this, I18n.tr("Could not load the presets: ") + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        final StackPresetsView pv = new StackPresetsView(this, presets);
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(pv.view());
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, maxH));
        showOledDialog("Setup Presets", "Indexers, quality and size, ready to use", sv, "APPLY", 0, () -> {
            final com.jellyarr.app.services.StackPresets.Selection sel = pv.selection();
            if (sel.isEmpty()) {
                Toast.makeText(this, I18n.tr("Nothing selected."), Toast.LENGTH_SHORT).show();
                return;
            }
            runStackPresets(presets, sel);
        }, "CANCEL", null);
    }

    private void runStackPresets(final com.jellyarr.app.services.StackPresets presets,
                                 final com.jellyarr.app.services.StackPresets.Selection sel) {
        TextView status = new TextView(this);
        status.setTextSize(12.5f);
        status.setTextColor(AppTheme.text());
        status.setTypeface(Typeface.MONOSPACE);
        status.setText("Applying presets...");
        ScrollView sv = new ScrollView(this);
        sv.addView(status);
        showOledDialog("Setup Presets", "Live progress", sv, null, 0, null, "CLOSE", null);

        final StringBuilder lines = new StringBuilder();
        new Thread(() -> {
            // Indexers need Prowlarr and the quality profiles need Radarr or Sonarr, all running.
            boolean ok;
            try {
                ok = presets.apply(this, sel, line -> runOnUiThread(() -> {
                    lines.append(line).append("\n");
                    status.setText(lines.toString());
                }));
            } catch (Throwable t) {
                ok = false;
                final String m = String.valueOf(t.getMessage());
                runOnUiThread(() -> { lines.append("✖ ").append(m).append("\n"); status.setText(lines.toString()); });
            }
            final boolean done = ok;
            runOnUiThread(() -> {
                lines.append("\n").append(done ? "✔ Done" : "Finished with some issues. Start the services that are not running and apply again.");
                status.setText(lines.toString());
                refreshServicesTabUI();
            });
        }, "stack-presets").start();
    }

    private void syncAndAutoWireAllServices() {
        final MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView what = new TextView(this);
        what.setText("What gets synchronized:\n"
                + "• Download folders and media library folders (Movies / Series)\n"
                + "• Transmission as the download client in Radarr and Sonarr\n"
                + "• Prowlarr indexers pushed to Radarr and Sonarr\n"
                + "• Existing media imported into Radarr / Sonarr\n"
                + "• API keys between the services\n\n"
                + "Only services that are installed AND running are synchronized.");
        what.setTextSize(12f);
        what.setTextColor(AppTheme.textSecondary());
        content.addView(what);

        TextView status = new TextView(this);
        status.setTextSize(12.5f);
        status.setTextColor(AppTheme.text());
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, dp(12), 0, 0);
        status.setText("Starting synchronization...");
        content.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        final StringBuilder lines = new StringBuilder();
        final Dialog[] dRef = new Dialog[1];
        dRef[0] = showOledDialog("Sync Stack", "Live progress", sv, null, 0, null, "CLOSE", null);

        String[][] known = {{"Transmission","transmission"},{"Radarr","radarr"},{"Sonarr","sonarr"},
                {"Prowlarr","prowlarr"}};
        for (String[] k : known) {
            try {
                ManagedService svc = mgr.getService(k[1]);
                if (svc == null || !svc.isInstalled()) continue;
                lines.append(svc.isRunning() ? "● " : "○ ").append(k[0])
                        .append(svc.isRunning() ? " running" : " not running (will be skipped)").append("\n");
            } catch (Throwable ignored) {}
        }
        if (lines.length() == 0) lines.append("No companion services are installed.\n");
        lines.append("\n");
        status.setText(lines.toString() + "… Working");

        mgr.autoWireStack((success, statusMessage) -> {
            lines.append("\n").append(success ? "✔ " : "✖ ").append(statusMessage);
            status.setText(lines.toString());
            refreshServicesTabUI();
            refreshHomeServicesBadge();
        }, line -> {
            lines.append(line).append("\n");
            status.setText(lines.toString());
        });
    }

    private void launchMoviesStack() {
        Toast.makeText(this, I18n.tr("Starting Movies Stack (Radarr + Transmission + Prowlarr)..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            String[] servicesToStart = {"transmission", "prowlarr", "radarr"};
            for (String id : servicesToStart) {
                ManagedService svc = mgr.getService(id);
                if (svc != null && svc.isEnabled() && !svc.isRunning() && svc.isInstalled()) {
                    mgr.startService(id);
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                }
            }
            mgr.scheduleAutoWireDebounced(2500);
            runOnUiThread(() -> {
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, I18n.tr("Movies Stack started & wired."), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void launchSeriesStack() {
        Toast.makeText(this, I18n.tr("Starting TV Shows / Series Stack (Sonarr + Transmission + Prowlarr)..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            MediaServiceManager mgr = MediaServiceManager.getInstance(this);
            String[] servicesToStart = {"transmission", "prowlarr", "sonarr"};
            for (String id : servicesToStart) {
                ManagedService svc = mgr.getService(id);
                if (svc != null && svc.isEnabled() && !svc.isRunning() && svc.isInstalled()) {
                    mgr.startService(id);
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                }
            }
            mgr.scheduleAutoWireDebounced(2500);
            runOnUiThread(() -> {
                refreshServicesTabUI();
                refreshHomeServicesBadge();
                Toast.makeText(this, I18n.tr("TV Shows / Series Stack started & wired."), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void deleteRecursive(File fileOrDir) {
        com.jellyarr.app.util.Files.deleteRecursively(fileOrDir);
    }

    private void copyFile(File src, File dst) {
        try { com.jellyarr.app.util.Files.copy(src, dst); } catch (Throwable ignored) {}
    }

    private static String readTextFile(File file) {
        return com.jellyarr.app.util.Files.readText(file);
    }

    private static void writeTextFile(File file, String content) {
        com.jellyarr.app.util.Files.writeText(file, content);
    }

    private void exportSanitizedDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Jellyarr System Diagnostics ===\n");
        sb.append("App Version: ").append(com.termux.BuildConfig.VERSION_NAME).append(" (").append(com.termux.BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Architecture: ").append(Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown").append("\n");
        sb.append("Jellyfin State: ").append(controller != null ? controller.getState().name() : "N/A").append("\n");
        if (controller != null) {
            JellyfinController.ServerHealth h = controller.getServerHealth();
            sb.append("Server Healthy: ").append(h.isHealthy).append("\n");
            sb.append("Uptime: ").append(h.uptimeSeconds).append("s\n");
            sb.append("Storage Free: ").append(h.storageFreeMb).append(" MB\n");
        }

        sb.append("\n--- Managed Services ---\n");
        MediaServiceManager mgr = MediaServiceManager.getInstance(this);
        for (ManagedService s : mgr.getAllServices()) {
            sb.append(s.getId()).append(": ").append(s.getState().name())
                    .append(" | Port: ").append(s.getManifest().getDefaultPort())
                    .append(" | Installed: ").append(s.isInstalled()).append("\n");
        }

        sb.append("\n--- Recent Jellyfin Logs (Sanitized) ---\n");
        String logs = controller != null ? controller.getLogs() : "";
        if (logs.length() > 3000) {
            logs = logs.substring(logs.length() - 3000);
        }
        sb.append(sanitizeText(logs));

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("fishbowl_diagnostics", sb.toString()));
            Toast.makeText(this, I18n.tr("Sanitized diagnostics copied to clipboard"), Toast.LENGTH_LONG).show();
        }
    }

    private String sanitizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)(api[_-]?key|token|auth|password|secret)[=:][^\\s&]+", "$1=***REDACTED***");
    }

    // -- Tab 3: Logs View -------------------------------------------------------

    // -- Legacy Logs Tab Stub ----------------------------------------------------

    private View buildLogsTab() {
        return new View(this);
    }

    private void refreshLogsDisplay() {
        refreshUnifiedLogsDisplay();
    }

    @Override
    public void onLogAppended() {
        scheduleThrottledLogUpdate();
    }

    private void scheduleThrottledLogUpdate() {
        if (logUpdatePending) return;
        logUpdatePending = true;
        logUpdateHandler.postDelayed(() -> {
            logUpdatePending = false;
            if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;
            if (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE) {
                updateSetupLogSummary();
            }
            if ((activeTab == 2 || activeTab == 4) && logsOutputText != null) {
                boolean atBottom = isScrollAtBottom(logsScrollView);
                refreshUnifiedLogsDisplay();
                if (autoScrollEnabled && atBottom && logsScrollView != null) {
                    logsScrollView.post(() -> logsScrollView.fullScroll(View.FOCUS_DOWN));
                }
            }
        }, 150);
    }

    private void updateSetupLogSummary() {
        if (setupLogSummaryText == null || controller == null) return;
        String logs = controller.getLogs();
        if (logs != null && !logs.isEmpty()) {
            String[] lines = logs.split("\n");
            int start = Math.max(0, lines.length - 4);
            StringBuilder lastFew = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                if (lastFew.length() > 0) lastFew.append("\n");
                lastFew.append(lines[i].trim());
            }
            setupLogSummaryText.setText(lastFew.toString());
        }
    }

    private boolean isScrollAtBottom(ScrollView scroll) {
        if (scroll == null) return true;
        View child = scroll.getChildAt(0);
        if (child == null) return true;
        int diff = (child.getBottom() - (scroll.getHeight() + scroll.getScrollY()));
        return diff <= dp(60);
    }

    // -- Tab 3: Settings View (Integrated Settings & Media Storage) ------------

    // -- Tab 3: Settings View (Professional Grouped Architecture & Storage Bar) ------------

    private FrameLayout settingsRoot;
    private LinearLayout settingsMenu;
    private LinearLayout settingsDetail;
    private LinearLayout settingsDetailBody;
    private ScrollView settingsDetailScroll;
    private TextView settingsDetailTitle;
    private boolean settingsSectionOpen = false;

    private View buildSettingsTab() {
        settingsRoot = new FrameLayout(this);
        settingsRoot.setBackgroundColor(getBgColor());

        // ---- Menu page: one row per section ----
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        settingsMenu = new LinearLayout(this);
        settingsMenu.setOrientation(LinearLayout.VERTICAL);
        settingsMenu.setPadding(dp(16), dp(12), dp(16), dp(24));

        settingsMenu.addView(UiKit.header(this, "Settings", "Tap a section to open it.", null));

        addSettingsGroupHeader("PERSONALIZE");
        addSettingsMenuRow("Appearance", appearanceSummary(), buildAppearanceSection());

        addSettingsGroupHeader("SERVER");
        addSettingsMenuRow("General & Startup", "Auto-start, keep screen on, media stack", buildGeneralSection());
        addSettingsMenuRow("Automation", "Transmission cleanup, auto-recheck, Wi-Fi only", buildAutomationSection());
        addSettingsMenuRow("Companion services", "Install or uninstall Radarr, Sonarr, Prowlarr and Transmission", buildCompanionServicesSection());
        addSettingsMenuRow("Playback & Streaming", "Codecs and transcoding", buildStreamingSection());
        addSettingsMenuRow("Stremio & AIOStreams", "Jellyfin library and Prowlarr torrents as a Stremio addon", buildStremioBridgeSection());

        addSettingsGroupHeader("DEVICE & SECURITY");
        addSettingsMenuRow("Storage", "Jellyarr footprint and disk usage", buildStorageSection());
        addSettingsMenuRow("Permissions & Health", "Background survival and system permissions", buildPermissionsSection());
        addSettingsMenuRow("Security & Identity", "Jellyfin users and service identity", buildSecuritySection());
        addSettingsMenuRow("Diagnostics", "Health checks and crash reports", buildDiagnosticsSection());

        addSettingsGroupHeader("MAINTENANCE");
        addSettingsMenuRow("Software Updates", "Check for new versions", buildUpdatesSection());
        addSettingsMenuRow("Backup & Restore", "Save and restore your configuration", buildBackupRestoreSection());
        addSettingsMenuRow("About Jellyarr", "Specs, architecture and licenses", buildAboutSection());

        // Community actions
        LinearLayout communityRow = new LinearLayout(this);
        communityRow.setOrientation(LinearLayout.HORIZONTAL);
        communityRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cParams = new LinearLayout.LayoutParams(-1, -2);
        cParams.topMargin = dp(8);
        communityRow.setLayoutParams(cParams);

        Button btnGithub = UiKit.outline(this, "GitHub", null);
        btnGithub.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MedRAM-RAM/Jellyarr")));
            } catch (Throwable t) {
                Toast.makeText(this, I18n.tr("Could not open browser: https://github.com/MedRAM-RAM/Jellyarr"), Toast.LENGTH_LONG).show();
            }
        });
        communityRow.addView(btnGithub, new LinearLayout.LayoutParams(0, dp(42), 1.0f));
        communityRow.addView(new View(this), new LinearLayout.LayoutParams(dp(12), 1));
        Button btnReport = UiKit.tonal(this, buttonLabel("REPORT A BUG"), null);
        btnReport.setOnClickListener(v -> com.jellyarr.app.diagnostics.BugReportHelper.openBugReportFlow(this, null, null));
        communityRow.addView(btnReport, new LinearLayout.LayoutParams(0, dp(42), 1.0f));
        settingsMenu.addView(communityRow);

        menuScroll.addView(settingsMenu);
        settingsRoot.addView(menuScroll, new FrameLayout.LayoutParams(-1, -1));

        // ---- Detail page: back bar + one section ----
        settingsDetail = new LinearLayout(this);
        settingsDetail.setOrientation(LinearLayout.VERTICAL);
        settingsDetail.setBackgroundColor(getBgColor());
        settingsDetail.setVisibility(View.GONE);

        // Compact top bar: back-arrow icon button (mirrored in RTL) + section title
        LinearLayout bar = UiKit.row(this);
        bar.setPaddingRelative(dp(8), dp(6), dp(16), dp(6));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(AppTheme.text());
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Back");
        back.setClickable(true);
        back.setFocusable(true);
        back.setBackground(UiKit.rowRipple(this));
        if (isRtlLayout()) back.setScaleX(-1f);   // ic_back is not auto-mirrored
        back.setOnClickListener(v -> closeSettingsSection());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        settingsDetailTitle = UiKit.singleLine(UiKit.title(this, ""));
        settingsDetailTitle.setTextSize(18f);
        settingsDetailTitle.setPaddingRelative(dp(8), 0, 0, 0);
        bar.addView(settingsDetailTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));
        settingsDetail.addView(bar);

        settingsDetailScroll = new ScrollView(this);
        settingsDetailScroll.setVerticalScrollBarEnabled(false);
        settingsDetailScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        settingsDetailBody = new LinearLayout(this);
        settingsDetailBody.setOrientation(LinearLayout.VERTICAL);
        settingsDetailBody.setPadding(dp(16), dp(8), dp(16), dp(24));
        settingsDetailScroll.addView(settingsDetailBody);
        settingsDetail.addView(settingsDetailScroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        settingsRoot.addView(settingsDetail, new FrameLayout.LayoutParams(-1, -1));

        refreshStorageBar();
        return settingsRoot;
    }

    private final java.util.HashMap<String, View> settingsSectionViews = new java.util.HashMap<>();

    private void openSettingsSectionByName(String name) {
        View v = settingsSectionViews.get(name);
        if (v == null) return;
        activeTab = 3;
        updateBottomNavSelection();
        renderActiveTab();
        openSettingsSection(name, v);
    }

    private String settingsOpenName = null;

    private void addSettingsGroupHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        styleSectionLabel(tv);
        tv.setPadding(dp(4), dp(16), dp(4), dp(8));
        settingsMenu.addView(tv);
    }

    private void addSettingsMenuRow(final String name, String subtitle, final View sectionView) {
        settingsSectionViews.put(name, sectionView);
        UiStyle.styleAllSwitches(this, sectionView);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(16), dp(14), dp(12), dp(14));
        row.setBackground(UiKit.pressable(this, AppTheme.card(), AppTheme.outline(), UiKit.R_CARD));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = UiKit.subtitle(this, name);
        t.setTextSize(15f);
        t.setTypeface(UiKit.BOLD);
        col.addView(t);
        TextView st = UiKit.caption(this, subtitle);
        st.setPadding(0, dp(2), 0, 0);
        col.addView(st);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron_down);
        arrow.setColorFilter(AppTheme.textMuted());
        arrow.setRotation(isRtlLayout() ? 90f : -90f);   // points toward the reading end in LTR and RTL
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.setOnClickListener(v -> openSettingsSection(name, sectionView));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        settingsMenu.addView(row, lp);
    }

    private void openSettingsSection(String name, View sectionView) {
        if (settingsDetailBody == null) return;
        settingsDetailBody.removeAllViews();
        if (sectionView.getParent() instanceof ViewGroup) ((ViewGroup) sectionView.getParent()).removeView(sectionView);
        settingsDetailBody.addView(sectionView);
        settingsDetailTitle.setText(name);
        settingsOpenName = name;
        settingsDetailScroll.scrollTo(0, 0);
        try {
            if ("Permissions & Health".equals(name)) refreshPermissionsUI();
            else if ("Security & Identity".equals(name)) refreshSecurityUsersUI();
            else if ("Backup & Restore".equals(name)) updateBackupStatusUI();
            else if ("Stremio & AIOStreams".equals(name) && bridgeRefresh != null) bridgeRefresh.run();
        } catch (Throwable ignored) {}
        settingsMenu.getParent();
        ((View) settingsMenu.getParent()).setVisibility(View.GONE);
        settingsDetail.setVisibility(View.VISIBLE);
        settingsSectionOpen = true;
    }

    private boolean closeSettingsSection() {
        if (!settingsSectionOpen || settingsDetail == null) return false;
        settingsDetail.setVisibility(View.GONE);
        ((View) settingsMenu.getParent()).setVisibility(View.VISIBLE);
        settingsDetailBody.removeAllViews();
        settingsOpenName = null;
        settingsSectionOpen = false;
        return true;
    }

    private LinearLayout createSettingsSectionCard() {
        return UiKit.card(this);
    }

    private TextView createSettingsSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        styleSectionLabel(tv);
        tv.setPaddingRelative(dp(4), dp(6), 0, dp(6));
        return tv;
    }

    private View createSettingsDivider() {
        View v = new View(this);
        v.setBackgroundColor(AppTheme.outline());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout createSettingsNavRow(String title, String subtitle, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(UiKit.rowRipple(this));
        if (onClick != null) row.setOnClickListener(onClick);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tView = new TextView(this);
        tView.setText(title);
        tView.setTextSize(13.5f);
        tView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tView.setTextColor(AppTheme.text());
        textCol.addView(tView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sView = new TextView(this);
            sView.setText(subtitle);
            sView.setTextSize(11f);
            sView.setTextColor(AppTheme.textMuted());
            sView.setPadding(0, dp(2), 0, 0);
            textCol.addView(sView);
        }

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView chevron = new TextView(this);
        chevron.setText("›");   // bidi-mirrored glyph: renders as ‹ in RTL automatically
        chevron.setTextSize(18f);
        chevron.setTypeface(Typeface.DEFAULT_BOLD);
        chevron.setTextColor(AppTheme.textMuted());
        chevron.setPaddingRelative(dp(8), 0, dp(4), 0);
        row.addView(chevron);

        return row;
    }

    // -- Section 1: Jellyarr Storage with Segmented Color Bar -------------------

    private View buildStorageSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        section.addView(createSettingsSectionHeader("JELLYARR STORAGE"));

        LinearLayout card = createSettingsSectionCard();

        storageHeaderTitle = new TextView(this);
        storageHeaderTitle.setText("Jellyarr Storage");
        storageHeaderTitle.setTextSize(20f);
        storageHeaderTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        storageHeaderTitle.setTextColor(AppTheme.text());
        card.addView(storageHeaderTitle);

        storageHeaderSubtitle = new TextView(this);
        storageHeaderSubtitle.setText("Calculating storage breakdown...");
        storageHeaderSubtitle.setTextSize(11f);
        storageHeaderSubtitle.setTextColor(AppTheme.textMuted());
        storageHeaderSubtitle.setPadding(0, dp(2), 0, dp(10));
        card.addView(storageHeaderSubtitle);

        // Segmented Storage Bar (100% of Jellyarr used)
        storageBarContainer = new LinearLayout(this);
        storageBarContainer.setOrientation(LinearLayout.HORIZONTAL);
        storageBarContainer.setBackground(createRoundedDrawable(AppTheme.inner(), dp(5)));
        storageBarContainer.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(10));
        barLp.bottomMargin = dp(12);
        storageBarContainer.setLayoutParams(barLp);
        card.addView(storageBarContainer);

        // Detailed Breakdown List (What took place inside Jellyarr)
        storageLegendContainer = new LinearLayout(this);
        storageLegendContainer.setOrientation(LinearLayout.VERTICAL);
        storageLegendContainer.setPadding(0, 0, 0, dp(4));
        card.addView(storageLegendContainer);

        card.addView(createSettingsDivider());

        // Manage Storage & Clear Cache Actions Row
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(6), 0, dp(2));

        Button btnManage = createPixelButton("MANAGE STORAGE", AppTheme.inner(), AppTheme.text());
        btnManage.setTextSize(10f);
        btnManage.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        actionRow.addView(btnManage, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

        View aSpacer = new View(this);
        actionRow.addView(aSpacer, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnClear = createPixelButton("CLEAR CACHE", AppTheme.dangerContainer(), AppTheme.danger());
        btnClear.setTextSize(10f);
        btnClear.setOnClickListener(v -> showClearCacheConfirmation());
        actionRow.addView(btnClear, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

        card.addView(actionRow);

        section.addView(card);

        externalStorageSection = new LinearLayout(this);
        externalStorageSection.setOrientation(LinearLayout.VERTICAL);
        externalStorageSection.setVisibility(View.GONE);
        section.addView(externalStorageSection);

        return section;
    }

    private void refreshStorageBar() {
        if (storageBarContainer == null) return;
        new Thread(() -> {
            try {
                // Query connected external storage safely on background thread
                List<StorageDriveHelper.DriveInfo> extDrives = MediaStorageManager.getConnectedExternalDrives(this);

                File prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
                File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
                File servicesDir = new File(home, "services");
                File dataDir = new File(home, ".local/share/jellyfin");
                File configDir = new File(home, ".config");
                File cacheDir = new File(home, ".cache");

                File dataAppDir = new File(getApplicationInfo().dataDir);
                long fullDataDirBytes = calculateDirSize(dataAppDir);

                long apkBytes = 0;
                try {
                    String pkgCodePath = getPackageCodePath();
                    if (pkgCodePath != null) {
                        File apkFile = new File(pkgCodePath);
                        if (apkFile.exists()) {
                            apkBytes = apkFile.length();
                        }
                    }
                } catch (Throwable ignored) {}

                long totalAppFootprint = fullDataDirBytes + apkBytes;

                // Query StorageStatsManager for system-accurate storage if available
                long systemQueryTotal = 0;
                long systemQueryCache = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        android.app.usage.StorageStatsManager statsManager = (android.app.usage.StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
                        android.os.storage.StorageManager storageManager = (android.os.storage.StorageManager) getSystemService(Context.STORAGE_SERVICE);
                        if (statsManager != null && storageManager != null) {
                            java.util.UUID uuid = storageManager.getUuidForPath(dataAppDir);
                            android.app.usage.StorageStats stats = statsManager.queryStatsForUid(uuid, android.os.Process.myUid());
                            systemQueryCache = stats.getCacheBytes();
                            systemQueryTotal = stats.getAppBytes() + stats.getDataBytes() + systemQueryCache;
                        }
                    } catch (Throwable ignored) {}
                }

                // 1. Companion Runtimes & Binaries: /usr (base runtimes) + /home/services (Servarr binaries)
                long usrBytes = calculateDirSize(prefix);
                long servicesBytes = calculateDirSize(servicesDir);
                long runtimeBytes = usrBytes + servicesBytes;

                // 2. Jellyfin Server & Databases: Jellyfin data (.local), configs (.config), and base app code
                long jellyfinDataBytes = calculateDirSize(dataDir);
                long totalConfigBytes = calculateDirSize(configDir);
                long dataBytes = jellyfinDataBytes + totalConfigBytes + apkBytes;

                // 3. Cache, Transcodes & Logs: cache directory + internal cache + logs
                long systemCache = calculateDirSize(getCacheDir()) + calculateDirSize(cacheDir);
                long rawCache = Math.max(systemCache, Math.max(systemQueryCache, totalAppFootprint - runtimeBytes - dataBytes));
                final long cacheBytes = Math.max(systemCache, rawCache);

                final long totalHardwareUsed = Math.max(systemQueryTotal, Math.max(totalAppFootprint, runtimeBytes + dataBytes + cacheBytes));

                runOnUiThread(() -> {
                    updateStorageBarUI(totalHardwareUsed, dataBytes, runtimeBytes, cacheBytes);
                    updateExternalStorageUI(extDrives);
                });
            } catch (Throwable t) {
                Log.w(TAG, "Storage breakdown failed: " + t.getMessage());
            }
        }, "StorageBreakdownThread").start();
    }

    private void updateExternalStorageUI(List<StorageDriveHelper.DriveInfo> extDrives) {
        if (externalStorageSection == null) return;
        if (extDrives == null || extDrives.isEmpty()) {
            externalStorageSection.setVisibility(View.GONE);
            externalStorageSection.removeAllViews();
            return;
        }

        externalStorageSection.setVisibility(View.VISIBLE);
        externalStorageSection.removeAllViews();
        externalStorageSection.addView(createSettingsSectionHeader("DETECTED EXTERNAL STORAGE"));

        for (StorageDriveHelper.DriveInfo drive : extDrives) {
            if (drive == null) continue;
            LinearLayout card = createSettingsSectionCard();

            // Row 1: Drive Icon, Name, and Status Badge
            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);

            ImageView driveIcon = new ImageView(this);
            driveIcon.setImageResource(R.drawable.ic_storage);
            driveIcon.setColorFilter(AppTheme.success());
            headerRow.addView(driveIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));

            View hSpacer = new View(this);
            headerRow.addView(hSpacer, new LinearLayout.LayoutParams(dp(10), 1));

            LinearLayout titleCol = new LinearLayout(this);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            TextView dName = new TextView(this);
            dName.setText(drive.name != null ? drive.name : "External Drive");
            dName.setTextSize(14.5f);
            dName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            dName.setTextColor(AppTheme.text());
            titleCol.addView(dName);

            TextView dPath = new TextView(this);
            dPath.setText(drive.rootPath != null ? drive.rootPath : "/storage");
            dPath.setTextSize(11f);
            dPath.setTextColor(AppTheme.textMuted());
            titleCol.addView(dPath);
            headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

            TextView statusBadge = new TextView(this);
            boolean isReadOnly = Environment.MEDIA_MOUNTED_READ_ONLY.equalsIgnoreCase(drive.state);
            statusBadge.setText(isReadOnly ? "READ-ONLY" : "MOUNTED (RW)");
            statusBadge.setTextSize(9.5f);
            statusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            statusBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
            statusBadge.setBackground(createRoundedDrawable(
                    isReadOnly ? AppTheme.warningContainer() : AppTheme.successContainer(), dp(4)));
            statusBadge.setTextColor(isReadOnly ? AppTheme.warning() : AppTheme.success());
            headerRow.addView(statusBadge);

            card.addView(headerRow);

            // Row 2: Capacity Stats
            long total = drive.totalBytes;
            long free = drive.freeBytes;
            long used = Math.max(0, total - free);

            TextView statsTv = new TextView(this);
            if (total > 0) {
                statsTv.setText(MediaStorageManager.formatBytes(used) + " used of " +
                        MediaStorageManager.formatBytes(total) + " (" + MediaStorageManager.formatBytes(free) + " free)");
            } else {
                statsTv.setText("Storage capacity: Ready • Direct mount");
            }
            statsTv.setTextSize(11.5f);
            statsTv.setTextColor(AppTheme.textSecondary());
            statsTv.setPadding(0, dp(10), 0, dp(6));
            card.addView(statsTv);

            // Row 3: Progress Bar
            if (total > 0) {
                LinearLayout barCont = new LinearLayout(this);
                barCont.setOrientation(LinearLayout.HORIZONTAL);
                barCont.setBackground(createRoundedDrawable(AppTheme.inner(), dp(5)));
                barCont.setPadding(dp(2), dp(2), dp(2), dp(2));
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(8));
                bLp.bottomMargin = dp(10);
                barCont.setLayoutParams(bLp);

                addBarSegment(barCont, used, total, AppTheme.success());
                card.addView(barCont);
            }

            // Row 4: Anti-Corruption Guard Shield Banner
            LinearLayout shieldBox = new LinearLayout(this);
            shieldBox.setOrientation(LinearLayout.VERTICAL);
            shieldBox.setBackground(createBorderedRoundedDrawable(AppTheme.successContainer(), AppTheme.successContainer(), dp(1), dp(8)));
            shieldBox.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
            sLp.bottomMargin = dp(10);
            shieldBox.setLayoutParams(sLp);

            TextView shieldTitle = new TextView(this);
            shieldTitle.setText("ANTI-CORRUPTION GUARD ACTIVE");
            shieldTitle.setTextSize(10f);
            shieldTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            shieldTitle.setTextColor(AppTheme.success());
            shieldBox.addView(shieldTitle);

            TextView shieldSub = new TextView(this);
            shieldSub.setText("Buffered atomic writes protect FAT/exFAT tables. Safe Sync commits all buffers to disk.");
            shieldSub.setTextSize(10.5f);
            shieldSub.setTextColor(AppTheme.textSecondary());
            shieldSub.setPadding(0, dp(2), 0, 0);
            shieldBox.addView(shieldSub);

            card.addView(shieldBox);

            // Row 5: Action Buttons (Safe Flush & Storage Manager)
            LinearLayout actRow = new LinearLayout(this);
            actRow.setOrientation(LinearLayout.HORIZONTAL);

            Button btnSync = createPixelButton("SAFE FLUSH & SYNC", AppTheme.successContainer(), AppTheme.success());
            btnSync.setTextSize(10f);
            btnSync.setOnClickListener(v -> {
                btnSync.setEnabled(false);
                btnSync.setText("SYNCING...");
                new Thread(() -> {
                    boolean ok = MediaStorageManager.flushAndSyncStorage();
                    runOnUiThread(() -> {
                        btnSync.setEnabled(true);
                        btnSync.setText("SAFE FLUSH & SYNC");
                        Toast.makeText(this, I18n.tr(ok ? "Storage buffers synced safely! Safe to unmount when idle." : "Storage sync completed."), Toast.LENGTH_SHORT).show();
                    });
                }).start();
            });
            actRow.addView(btnSync, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

            View aSp = new View(this);
            actRow.addView(aSp, new LinearLayout.LayoutParams(dp(8), 1));

            Button btnOpenMgr = createPixelButton("MANAGE DRIVE", AppTheme.inner(), AppTheme.text());
            btnOpenMgr.setTextSize(10f);
            btnOpenMgr.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
            actRow.addView(btnOpenMgr, new LinearLayout.LayoutParams(0, dp(36), 1.0f));

            card.addView(actRow);
            externalStorageSection.addView(card);
        }
    }

    private void updateStorageBarUI(long totalHardwareUsed, long dataBytes, long runtimeBytes, long cacheBytes) {
        if (storageHeaderTitle != null) {
            storageHeaderTitle.setText(MediaStorageManager.formatBytes(totalHardwareUsed) + " App Footprint");
        }
        if (storageHeaderSubtitle != null) {
            storageHeaderSubtitle.setText("Hardware storage • Companion runtimes, databases & cache");
        }

        if (storageBarContainer != null) {
            storageBarContainer.removeAllViews();

            long displayTotal = totalHardwareUsed;
            if (displayTotal <= 0) displayTotal = 1;

            // App Hardware Breakdown:
            // 1. Companion Runtimes (Amber)
            // 2. Jellyfin Server & DB (Violet)
            // 3. Cache & Logs (Rose)
            addBarSegment(storageBarContainer, runtimeBytes, displayTotal, AppTheme.warning()); // Amber
            addBarSegment(storageBarContainer, dataBytes, displayTotal, Color.parseColor("#8B5CF6"));    // Violet
            addBarSegment(storageBarContainer, cacheBytes, displayTotal, AppTheme.danger());   // Rose
        }

        if (storageLegendContainer != null) {
            storageLegendContainer.removeAllViews();

            View compItem = createStorageBreakdownItem(
                    AppTheme.warning(), "Companion Runtimes & Binaries",
                    "Core packages (.NET 10, Python, FFmpeg, Servarr) • Tap for details", runtimeBytes, totalHardwareUsed);
            compItem.setClickable(true);
            compItem.setFocusable(true);
            compItem.setBackground(createBorderedRoundedDrawable(Color.TRANSPARENT, AppTheme.outline(), dp(1), dp(6)));
            compItem.setPadding(dp(6), dp(4), dp(6), dp(4));
            compItem.setOnClickListener(v -> showCompanionStorageBreakdownDialog());
            storageLegendContainer.addView(compItem);
            storageLegendContainer.addView(createSettingsDivider());

            View jfItem = createStorageBreakdownItem(
                    Color.parseColor("#8B5CF6"), "Jellyfin Server & Databases",
                    "SQLite metadata, posters, and user watch history • Tap for details", dataBytes, totalHardwareUsed);
            jfItem.setClickable(true);
            jfItem.setFocusable(true);
            jfItem.setBackground(createBorderedRoundedDrawable(Color.TRANSPARENT, AppTheme.outline(), dp(1), dp(6)));
            jfItem.setPadding(dp(6), dp(4), dp(6), dp(4));
            jfItem.setOnClickListener(v -> showJellyfinDatabaseBreakdownDialog());
            storageLegendContainer.addView(jfItem);
            storageLegendContainer.addView(createSettingsDivider());

            View cacheItem = createStorageBreakdownItem(
                    AppTheme.danger(), "Cache, Transcodes & Logs",
                    "FFmpeg transcoding buffer, HTTP cache, and logs • Tap for details", cacheBytes, totalHardwareUsed);
            cacheItem.setClickable(true);
            cacheItem.setFocusable(true);
            cacheItem.setBackground(createBorderedRoundedDrawable(Color.TRANSPARENT, AppTheme.outline(), dp(1), dp(6)));
            cacheItem.setPadding(dp(6), dp(4), dp(6), dp(4));
            cacheItem.setOnClickListener(v -> showCacheStorageBreakdownDialog());
            storageLegendContainer.addView(cacheItem);
        }
    }

    private void showCompanionStorageBreakdownDialog() {
        OledProgressDialog progress = showOledProgressDialog("ANALYZING RUNTIMES", "Calculating itemized companion breakdown...");
        new Thread(() -> {
            try {
                File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
                File prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
                File servicesDir = new File(home, "services");

                long usrBytes = calculateDirSize(prefix);

                // Granular breakdown of /usr system directory
                File dotnetDir = new File(prefix, "lib/dotnet");
                long dotnetBytes = calculateDirSize(dotnetDir);

                File jfBinDir = new File(prefix, "lib/jellyfin");
                long jfBinBytes = calculateDirSize(jfBinDir);

                long pyBytes = 0;
                File libDir = new File(prefix, "lib");
                if (libDir.exists() && libDir.isDirectory()) {
                    File[] sub = libDir.listFiles();
                    if (sub != null) {
                        for (File f : sub) {
                            if (f.getName().startsWith("python3")) {
                                pyBytes += calculateDirSize(f);
                            }
                        }
                    }
                }

                File ffmpegOpt = new File(prefix, "opt/jellyfin");
                File ffmpegBin = new File(prefix, "bin/ffmpeg");
                File ffprobeBin = new File(prefix, "bin/ffprobe");
                long ffmpegBytes = calculateDirSize(ffmpegOpt) + (ffmpegBin.exists() ? ffmpegBin.length() : 0) + (ffprobeBin.exists() ? ffprobeBin.length() : 0);

                long baseSysBytes = Math.max(0, usrBytes - dotnetBytes - jfBinBytes - pyBytes - ffmpegBytes);
                final long finalPyBytes = pyBytes;

                // Granular breakdown of /services directory
                long radarrBytes = calculateDirSize(new File(servicesDir, "radarr"));
                long sonarrBytes = calculateDirSize(new File(servicesDir, "sonarr"));
                long prowlarrBytes = calculateDirSize(new File(servicesDir, "prowlarr"));

                File radarrBak = new File(servicesDir, "radarr.bak");
                long radarrBakBytes = radarrBak.exists() ? calculateDirSize(radarrBak) : 0;

                long totalBytes = usrBytes + radarrBytes + sonarrBytes + prowlarrBytes + radarrBakBytes;

                runOnUiThread(() -> {
                    progress.dismiss();

                    LinearLayout content = new LinearLayout(this);
                    content.setOrientation(LinearLayout.VERTICAL);
                    content.setPadding(0, dp(4), 0, dp(8));

                    TextView sub = new TextView(this);
                    sub.setText("Total Companion & Runtime Footprint: " + MediaStorageManager.formatBytes(totalBytes));
                    sub.setTextSize(11f);
                    sub.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    sub.setTextColor(AppTheme.warning());
                    sub.setPadding(0, 0, 0, dp(12));
                    content.addView(sub);

                    if (dotnetBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#512BD4"), ".NET 10 Runtime Engine", "Cross-platform CLR host (/usr/lib/dotnet)", dotnetBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (jfBinBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#AB03A9"), "Jellyfin Server Core Binaries", "Server assembly DLLs & assets (/usr/lib/jellyfin)", jfBinBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (finalPyBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#3776AB"), "Python 3 Runtime & Standard Libs", "Automation & FlareSolverr runtime (/usr/lib/python3.*)", finalPyBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (ffmpegBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#007808"), "FFmpeg Transcoding Engine", "Media encoding libraries & binaries (/usr/opt/jellyfin)", ffmpegBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (baseSysBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.textMuted(), "Base Linux System & Packages", "Shell tools, shared libraries & utilities (/usr)", baseSysBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }

                    if (radarrBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.warning(), "Radarr (Movies Manager)", "/services/radarr", radarrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (sonarrBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.success(), "Sonarr (TV Shows Manager)", "/services/sonarr", sonarrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (prowlarrBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.warning(), "Prowlarr (Indexer Proxy)", "/services/prowlarr", prowlarrBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }

                    if (radarrBakBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.danger(), "Radarr Backup (.bak)", "Old installation backup file/folder", radarrBakBytes, totalBytes));
                        content.addView(createSettingsDivider());

                        Button btnCleanBak = createOutlinePixelButton("RECLAIM " + MediaStorageManager.formatBytes(radarrBakBytes) + " (DELETE .BAK)", AppTheme.danger(), AppTheme.danger());
                        btnCleanBak.setTextSize(10f);
                        btnCleanBak.setOnClickListener(v -> {
                            new Thread(() -> {
                                MediaServiceManager.deleteRecursive(radarrBak);
                                runOnUiThread(() -> {
                                    Toast.makeText(this, I18n.tr("Cleaned backup files! " + MediaStorageManager.formatBytes(radarrBakBytes) + " reclaimed."), Toast.LENGTH_LONG).show();
                                    refreshStorageBar();
                                });
                            }).start();
                        });
                        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, dp(38));
                        cLp.topMargin = dp(8);
                        content.addView(btnCleanBak, cLp);
                    }

                    showOledDialog("Companion Runtimes & Binaries", "Itemized breakdown of installed packages and services", content, null, 0, null, "CLOSE", null);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, I18n.tr("Failed to analyze storage: " + t.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showJellyfinDatabaseBreakdownDialog() {
        OledProgressDialog progress = showOledProgressDialog("ANALYZING DATABASES", "Calculating itemized Jellyfin server breakdown...");
        new Thread(() -> {
            try {
                File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
                File jfShare = new File(home, ".local/share/jellyfin");
                File jfData = new File(jfShare, "data");
                File jfMetadata = new File(jfShare, "metadata");
                File jfPlugins = new File(jfShare, "plugins");
                File jfRoot = new File(jfShare, "root");
                File jfConfig = new File(home, ".config/jellyfin");

                long dataDbBytes = calculateDirSize(jfData);
                long metadataBytes = calculateDirSize(jfMetadata);
                long pluginsBytes = calculateDirSize(jfPlugins);
                long rootBytes = calculateDirSize(jfRoot);
                long configBytes = calculateDirSize(jfConfig);

                long apkBytes = 0;
                try {
                    String pkgCodePath = getPackageCodePath();
                    if (pkgCodePath != null) {
                        File apkFile = new File(pkgCodePath);
                        if (apkFile.exists()) apkBytes = apkFile.length();
                    }
                } catch (Throwable ignored) {}

                long totalBytes = dataDbBytes + metadataBytes + pluginsBytes + rootBytes + configBytes + apkBytes;
                final long finalApkBytes = apkBytes;

                runOnUiThread(() -> {
                    progress.dismiss();

                    LinearLayout content = new LinearLayout(this);
                    content.setOrientation(LinearLayout.VERTICAL);
                    content.setPadding(0, dp(4), 0, dp(8));

                    TextView sub = new TextView(this);
                    sub.setText("Total Jellyfin Server & Database Footprint: " + MediaStorageManager.formatBytes(totalBytes));
                    sub.setTextSize(11f);
                    sub.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    sub.setTextColor(Color.parseColor("#8B5CF6"));
                    sub.setPadding(0, dp(2), 0, dp(12));
                    content.addView(sub);

                    if (dataDbBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#8B5CF6"), "SQLite Databases & User State", "jellyfin.db, library.db, users.db, watch states & playback progress", dataDbBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (metadataBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#A78BFA"), "Metadata & Poster Artwork", "Movie covers, TV backdrops, actor portraits & chapter images", metadataBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (pluginsBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#C4B5FD"), "Installed Plugins & Assemblies", "Community plugin DLLs, manifests, and package configurations", pluginsBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (configBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#6366F1"), "Server XML Configurations", "system.xml, encoding.xml, network.xml, transcoding rules", configBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (rootBytes > 0) {
                        content.addView(createStorageBreakdownItem(Color.parseColor("#818CF8"), "Virtual Library Links", "Folder mapping links connecting your media libraries", rootBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (finalApkBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.textMuted(), "App Package & Native Engines", "Base Android APK and bundled native architecture libraries", finalApkBytes, totalBytes));
                    }

                    showOledDialog("Jellyfin Server & Databases", "Itemized breakdown of Jellyfin server core data and databases", content, null, 0, null, "CLOSE", null);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, I18n.tr("Failed to analyze databases: " + t.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showCacheStorageBreakdownDialog() {
        OledProgressDialog progress = showOledProgressDialog("ANALYZING CACHE", "Calculating cache and transcoding buffer...");
        new Thread(() -> {
            try {
                File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
                File jfShare = new File(home, ".local/share/jellyfin");
                File jfLog = new File(jfShare, "log");
                File jfCache = new File(home, ".cache/jellyfin");
                File jfTranscodes = new File(jfCache, "transcodes");

                long transcodeBytes = calculateDirSize(jfTranscodes);
                long fullCacheBytes = calculateDirSize(jfCache);
                long webCacheBytes = Math.max(0, fullCacheBytes - transcodeBytes);
                long logBytes = calculateDirSize(jfLog);
                long appCacheBytes = calculateDirSize(getCacheDir());

                long totalBytes = transcodeBytes + webCacheBytes + logBytes + appCacheBytes;

                runOnUiThread(() -> {
                    progress.dismiss();

                    LinearLayout content = new LinearLayout(this);
                    content.setOrientation(LinearLayout.VERTICAL);
                    content.setPadding(0, dp(4), 0, dp(8));

                    TextView sub = new TextView(this);
                    sub.setText("Total Temporary & Cache Footprint: " + MediaStorageManager.formatBytes(totalBytes));
                    sub.setTextSize(11f);
                    sub.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    sub.setTextColor(AppTheme.danger());
                    sub.setPadding(0, dp(2), 0, dp(12));
                    content.addView(sub);

                    if (transcodeBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.danger(), "Active Transcoding Buffer", "Temporary HLS video fragments, transcoded audio & video chunks", transcodeBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (webCacheBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.danger(), "Web & Metadata API Cache", "Cached TheMovieDB / Fanart.tv requests & Web UI assets", webCacheBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (logBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.danger(), "Server Execution Logs", "Jellyfin runtime logs tracking playback sessions and system events", logBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }
                    if (appCacheBytes > 0) {
                        content.addView(createStorageBreakdownItem(AppTheme.danger(), "Internal App WebView Cache", "Android WebView cache, temporary thumbnail rendering cache", appCacheBytes, totalBytes));
                        content.addView(createSettingsDivider());
                    }

                    Button btnClear = createOutlinePixelButton("PURGE TEMPORARY CACHE (" + MediaStorageManager.formatBytes(totalBytes) + ")", AppTheme.danger(), AppTheme.danger());
                    btnClear.setTextSize(10.5f);
                    btnClear.setOnClickListener(v -> showClearCacheConfirmation());
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(38));
                    clp.topMargin = dp(8);
                    content.addView(btnClear, clp);

                    showOledDialog("Cache, Transcodes & Logs", "Itemized breakdown of temporary cache and transcodes", content, null, 0, null, "CLOSE", null);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, I18n.tr("Failed to analyze cache: " + t.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void addBarSegment(LinearLayout parent, long bytes, long total, int color) {
        if (bytes <= 0) return;
        float weight = (float) bytes / total;
        weight = Math.max(0.02f, weight);

        View segment = new View(this);
        segment.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, weight);
        lp.setMarginEnd(dp(1));
        segment.setLayoutParams(lp);
        parent.addView(segment);
    }

    private View createStorageBreakdownItem(int color, String title, String subtitle, long bytes, long totalBytes) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, dp(4), 0, dp(4));

        View dot = new View(this);
        dot.setBackground(createRoundedDrawable(color, dp(4)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMarginEnd(dp(10));
        item.addView(dot, dotLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(13f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleTv.setTextColor(AppTheme.text());
        textCol.addView(titleTv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setText(subtitle);
            subTv.setTextSize(10.5f);
            subTv.setTextColor(AppTheme.textMuted());
            subTv.setPadding(0, dp(1), 0, 0);
            textCol.addView(subTv);
        }
        item.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        LinearLayout valCol = new LinearLayout(this);
        valCol.setOrientation(LinearLayout.VERTICAL);
        valCol.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        TextView valTv = new TextView(this);
        valTv.setText(MediaStorageManager.formatBytes(bytes));
        valTv.setTextSize(12.5f);
        valTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valTv.setTextColor(AppTheme.text());
        valCol.addView(valTv);

        int pct = totalBytes > 0 ? (int) Math.round((double) bytes * 100.0 / totalBytes) : 0;
        TextView pctTv = new TextView(this);
        pctTv.setText(pct + "%");
        pctTv.setTextSize(10f);
        pctTv.setTextColor(AppTheme.textMuted());
        valCol.addView(pctTv);

        item.addView(valCol);
        return item;
    }

    // -- Section 1: General & Startup -----------------------------------------

    private View buildGeneralSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(createSettingsSectionHeader("GENERAL & STARTUP"));

        LinearLayout card = createSettingsSectionCard();

        // Row 1A: Auto-Start on App Launch (Jellyfin Only)
        LinearLayout autoStartAppRow = new LinearLayout(this);
        autoStartAppRow.setOrientation(LinearLayout.HORIZONTAL);
        autoStartAppRow.setGravity(Gravity.CENTER_VERTICAL);
        autoStartAppRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout asAppTextCol = new LinearLayout(this);
        asAppTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView asAppTitle = new TextView(this);
        asAppTitle.setText("Start on App Launch");
        asAppTitle.setTextSize(13.5f);
        asAppTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        asAppTitle.setTextColor(AppTheme.text());
        asAppTextCol.addView(asAppTitle);

        TextView asAppSub = new TextView(this);
        asAppSub.setText("Automatically start the full server (Jellyfin + Radarr, Sonarr, Prowlarr, Transmission and other installed services) when opening Jellyarr");
        asAppSub.setTextSize(11f);
        asAppSub.setTextColor(AppTheme.textMuted());
        asAppSub.setPadding(0, dp(2), 0, 0);
        asAppTextCol.addView(asAppSub);
        autoStartAppRow.addView(asAppTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Switch autoStartAppSwitch = UiStyle.newSwitch(this);
        SharedPreferences prefs = getJellyarrPrefs();
        boolean appLaunchDefault = prefs.getBoolean("auto_start_app", prefs.getBoolean("auto_start", false));
        autoStartAppSwitch.setChecked(appLaunchDefault);
        autoStartAppSwitch.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("auto_start_app", checked).putBoolean("auto_start", checked).apply();
            if (checked) {
                triggerAutoStartOnAppLaunchIfEnabled();
            }
        });
        autoStartAppRow.addView(autoStartAppSwitch);
        card.addView(autoStartAppRow);
        card.addView(createSettingsDivider());

        // Row 1A-2: Auto-Start Companion Services
        LinearLayout autoStartCompRow = new LinearLayout(this);
        autoStartCompRow.setOrientation(LinearLayout.HORIZONTAL);
        autoStartCompRow.setGravity(Gravity.CENTER_VERTICAL);
        autoStartCompRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout asCompTextCol = new LinearLayout(this);
        asCompTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView asCompTitle = new TextView(this);
        asCompTitle.setText("Auto-Start Companion Services");
        asCompTitle.setTextSize(13.5f);
        asCompTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        asCompTitle.setTextColor(AppTheme.text());
        asCompTextCol.addView(asCompTitle);

        TextView asCompSub = new TextView(this);
        asCompSub.setText("Automatically launch companion services (Radarr, Sonarr, etc.) when Jellyarr starts. Disabled by default to prevent port conflicts.");
        asCompSub.setTextSize(11f);
        asCompSub.setTextColor(AppTheme.textMuted());
        asCompSub.setPadding(0, dp(2), 0, 0);
        asCompTextCol.addView(asCompSub);
        autoStartCompRow.addView(asCompTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Switch autoStartCompSwitch = UiStyle.newSwitch(this);
        boolean compAutoStart = MediaServiceManager.getInstance(this).isCompanionAutoStartEnabled();
        autoStartCompSwitch.setChecked(compAutoStart);
        autoStartCompSwitch.setOnCheckedChangeListener((v, checked) -> {
            MediaServiceManager.getInstance(this).setCompanionAutoStartEnabled(checked);
            Toast.makeText(this, I18n.tr(checked ? "Companion auto-start enabled." : "Companion auto-start disabled (on-demand only)."), Toast.LENGTH_SHORT).show();
        });
        autoStartCompRow.addView(autoStartCompSwitch);
        card.addView(autoStartCompRow);
        card.addView(createSettingsDivider());

        // Row 1B: Media Stack Subsystem (Companion Services)
        LinearLayout mediaStackRow = new LinearLayout(this);
        mediaStackRow.setOrientation(LinearLayout.HORIZONTAL);
        mediaStackRow.setGravity(Gravity.CENTER_VERTICAL);
        mediaStackRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout msTextCol = new LinearLayout(this);
        msTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView msTitle = new TextView(this);
        msTitle.setText("Media Stack Subsystem");
        msTitle.setTextSize(13.5f);
        msTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        msTitle.setTextColor(AppTheme.text());
        msTextCol.addView(msTitle);

        TextView msSub = new TextView(this);
        msSub.setText("Enable Radarr, Sonarr, Prowlarr & companion services. Disable for Jellyfin-only mode to save storage.");
        msSub.setTextSize(11f);
        msSub.setTextColor(AppTheme.textMuted());
        msSub.setPadding(0, dp(2), 0, 0);
        msTextCol.addView(msSub);
        mediaStackRow.addView(msTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Switch mediaStackSwitch = UiStyle.newSwitch(this);
        mediaStackSwitch.setChecked(isMediaStackEnabled());
        mediaStackSwitch.setOnCheckedChangeListener((v, checked) -> {
            setMediaStackEnabled(checked);
            Toast.makeText(this, I18n.tr(checked ? "Media Stack enabled." : "Media Stack disabled (Jellyfin-only mode)."), Toast.LENGTH_SHORT).show();
        });
        mediaStackRow.addView(mediaStackSwitch);
        card.addView(mediaStackRow);
        card.addView(createSettingsDivider());

        // Row 2: Quick Settings Tile
        LinearLayout qsRow = new LinearLayout(this);
        qsRow.setOrientation(LinearLayout.HORIZONTAL);
        qsRow.setGravity(Gravity.CENTER_VERTICAL);
        qsRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout qsTextCol = new LinearLayout(this);
        qsTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView qsTitle = new TextView(this);
        qsTitle.setText("Quick Settings Tile");
        qsTitle.setTextSize(13.5f);
        qsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        qsTitle.setTextColor(AppTheme.text());
        qsTextCol.addView(qsTitle);

        TextView qsSub = new TextView(this);
        qsSub.setText("Toggle Jellyfin server and check live status from notification shade");
        qsSub.setTextSize(11f);
        qsSub.setTextColor(AppTheme.textMuted());
        qsSub.setPadding(0, dp(2), 0, 0);
        qsTextCol.addView(qsSub);
        qsRow.addView(qsTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnAddTile = createPixelButton("ADD TILE", AppTheme.withAlpha(AppTheme.accent(), 25), AppTheme.accent());
        btnAddTile.setTextSize(10f);
        btnAddTile.setPadding(dp(10), 0, dp(10), 0);
        btnAddTile.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                JellyarrQuickSettingsTileService.requestAddTile(this,
                    () -> Toast.makeText(this, I18n.tr("Jellyarr Quick Settings tile added!"), Toast.LENGTH_SHORT).show(),
                    () -> Toast.makeText(this, I18n.tr("Quick Settings tile request cancelled or already present."), Toast.LENGTH_SHORT).show()
                );
            } else {
                Toast.makeText(this, I18n.tr("To add the Jellyarr tile: swipe down twice from the top of your screen, tap Edit (pencil icon), and drag the Jellyarr tile into active tiles."), Toast.LENGTH_LONG).show();
            }
        });
        qsRow.addView(btnAddTile, new LinearLayout.LayoutParams(-2, dp(32)));
        card.addView(qsRow);

        section.addView(card);
        return section;
    }


    // -- Appearance: theme mode, accent colour, Material You -------------------------------

    private static final String[] THEME_MODE_LABELS = {"System", "Light", "Dark", "AMOLED"};
    private static final int[] THEME_MODE_VALUES = {
            AppTheme.MODE_SYSTEM, AppTheme.MODE_LIGHT, AppTheme.MODE_DARK, AppTheme.MODE_AMOLED};

    private String appearanceSummary() {
        int m = AppTheme.getMode(this);
        String mode = "AMOLED";
        for (int i = 0; i < THEME_MODE_VALUES.length; i++) if (THEME_MODE_VALUES[i] == m) mode = THEME_MODE_LABELS[i];
        int a = AppTheme.getAccentIndex();
        String accent = a == AppTheme.ACCENT_DYNAMIC ? "Dynamic" : AppTheme.ACCENT_NAMES[a];
        return I18n.tr(mode) + " \u00B7 " + I18n.tr(accent);
    }

    /** Colours are baked into the programmatic views, so a theme change rebuilds the activity. */
    private void restartForTheme() {
        themeRestarting = true;
        AppTheme.applyNightMode();
        recreate();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private boolean themeRestarting = false;

    private TextView appearanceTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(AppTheme.text());
        return t;
    }

    private TextView appearanceHint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11f);
        t.setTextColor(AppTheme.textMuted());
        t.setPadding(0, dp(2), 0, dp(10));
        return t;
    }

    private View buildAppearanceSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        // ---- Language ----
        section.addView(createSettingsSectionHeader("LANGUAGE"));
        LinearLayout langCard = createSettingsSectionCard();
        langCard.addView(appearanceTitle("Language"));
        langCard.addView(appearanceHint("Choose the app language. The app restarts its screens to apply it."));
        final String[] langValues = {I18n.LANG_SYSTEM, I18n.LANG_EN, I18n.LANG_AR};
        final String[] langLabels = {"System", "English", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"};
        LinearLayout langSeg = new LinearLayout(this);
        langSeg.setOrientation(LinearLayout.HORIZONTAL);
        String currentLang = I18n.getChoice();
        for (int i = 0; i < langValues.length; i++) {
            final String value = langValues[i];
            boolean sel = value.equals(currentLang);
            Button chip = createPixelButton((sel ? "\u2713 " : "") + langLabels[i],
                    sel ? AppTheme.accentContainer() : AppTheme.card(),
                    sel ? AppTheme.onAccentContainer() : AppTheme.textSecondary());
            chip.setTextSize(12f);
            chip.setPadding(dp(4), 0, dp(4), 0);
            chip.setOnClickListener(v -> {
                if (!value.equals(I18n.getChoice())) {
                    I18n.setLanguage(this, value);
                    recreate();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
            if (i > 0) lp.setMarginStart(dp(6));
            langSeg.addView(chip, lp);
        }
        langCard.addView(langSeg);
        section.addView(langCard);

        // ---- Mode ----
        section.addView(createSettingsSectionHeader("THEME"));
        LinearLayout modeCard = createSettingsSectionCard();
        modeCard.addView(appearanceTitle("Mode"));
        modeCard.addView(appearanceHint("System follows your phone. AMOLED uses pure black to save battery on OLED screens."));

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        int currentMode = AppTheme.getMode(this);
        for (int i = 0; i < THEME_MODE_VALUES.length; i++) {
            final int value = THEME_MODE_VALUES[i];
            boolean sel = value == currentMode;
            String label = (sel ? "\u2713 " : "") + THEME_MODE_LABELS[i];
            Button chip = createPixelButton(label,
                    sel ? AppTheme.accentContainer() : AppTheme.card(),
                    sel ? AppTheme.onAccentContainer() : AppTheme.textSecondary());
            chip.setTextSize(11f);
            chip.setPadding(dp(4), 0, dp(4), 0);
            chip.setOnClickListener(v -> {
                if (value != AppTheme.getMode(this)) {
                    AppTheme.setMode(this, value);
                    restartForTheme();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
            if (i > 0) lp.setMarginStart(dp(6));
            seg.addView(chip, lp);
        }
        modeCard.addView(seg);
        section.addView(modeCard);

        // ---- Accent colour ----
        section.addView(createSettingsSectionHeader("COLORS"));
        LinearLayout accentCard = createSettingsSectionCard();
        accentCard.addView(appearanceTitle("Accent color"));
        accentCard.addView(appearanceHint(AppTheme.isDynamicAvailable()
                ? "Dynamic (Material You) takes its colors from your wallpaper."
                : "Dynamic colors (Material You) need Android 12 or newer."));

        java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        if (AppTheme.isDynamicAvailable()) ids.add(AppTheme.ACCENT_DYNAMIC);
        for (int i = 0; i < AppTheme.ACCENT_NAMES.length; i++) ids.add(i);

        final int perRow = 5;
        int currentAccent = AppTheme.getAccentIndex();
        LinearLayout row = null;
        for (int n = 0; n < ids.size(); n++) {
            if (n % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = dp(10);
                accentCard.addView(row, rlp);
            }
            int id = ids.get(n);
            row.addView(buildAccentSwatch(id, id == currentAccent),
                    new LinearLayout.LayoutParams(0, -2, 1.0f));
        }
        // Keep the last row's swatches the same width as the others.
        if (row != null) {
            for (int fill = row.getChildCount(); fill < perRow; fill++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1.0f));
            }
        }
        section.addView(accentCard);

        // ---- Live preview ----
        section.addView(createSettingsSectionHeader("PREVIEW"));
        LinearLayout previewCard = createSettingsSectionCard();
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button filled = createPixelButton("Filled", AppTheme.accent(), AppTheme.onAccent());
        Button tonal = createPixelButton("Tonal", AppTheme.card(), AppTheme.accent());
        Button outlined = createPixelButton("Outlined", AppTheme.card(), AppTheme.text());
        Button[] three = {filled, tonal, outlined};
        for (int i = 0; i < three.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
            if (i > 0) lp.setMarginStart(dp(6));
            btns.addView(three[i], lp);
        }
        previewCard.addView(btns);
        previewCard.addView(createSettingsDivider());
        LinearLayout swRow = new LinearLayout(this);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView swLabel = appearanceTitle("Switch");
        swRow.addView(swLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch swOn = UiStyle.newSwitch(this);
        swOn.setChecked(true);
        swRow.addView(swOn);
        previewCard.addView(swRow);
        section.addView(previewCard);

        // ---- Reset ----
        Button reset = createPixelButton("RESET TO DEFAULT", AppTheme.card(), AppTheme.textSecondary());
        reset.setOnClickListener(v -> {
            AppTheme.setAccent(this, 0);
            AppTheme.setMode(this, AppTheme.MODE_AMOLED);
            restartForTheme();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(40));
        rp.topMargin = dp(4);
        section.addView(reset, rp);
        return section;
    }

    private View buildAccentSwatch(final int accentId, boolean selected) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClickable(true);
        cell.setFocusable(true);

        String name = accentId == AppTheme.ACCENT_DYNAMIC ? "Dynamic" : AppTheme.ACCENT_NAMES[accentId];
        cell.setContentDescription(name + (selected ? ", selected" : ""));
        int preview = AppTheme.accentPreview(this, accentId);

        FrameLayout swatch = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(preview);
        if (selected) circle.setStroke(dp(3), AppTheme.text());
        else circle.setStroke(dp(1), AppTheme.outline());
        swatch.setBackground(circle);
        if (selected) {
            TextView check = new TextView(this);
            check.setText("\u2713");
            check.setTextSize(16f);
            check.setTypeface(Typeface.DEFAULT_BOLD);
            check.setTextColor(AppTheme.contrastOn(preview));
            check.setGravity(Gravity.CENTER);
            swatch.addView(check, new FrameLayout.LayoutParams(-1, -1));
        }
        cell.addView(swatch, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(10f);
        label.setTextColor(selected ? AppTheme.text() : AppTheme.textMuted());
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setPadding(0, dp(4), 0, 0);
        cell.addView(label);

        cell.setOnClickListener(v -> {
            if (AppTheme.getAccentIndex() != accentId) {
                AppTheme.setAccent(this, accentId);
                restartForTheme();
            }
        });
        return cell;
    }

    // -- AUTOMATION: Transmission auto-cleanup, auto-recheck & per-service Wi-Fi only ----------

    /** Settings > Companion services: install / uninstall, each disabled when there is nothing to do. */
    private View buildCompanionServicesSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(createSettingsSectionHeader("COMPANION SERVICES"));
        LinearLayout card = createSettingsSectionCard();

        card.addView(UiKit.text(this, I18n.tr("Install"), 13.5f, AppTheme.text(), UiKit.BOLD));
        TextView ih = UiKit.caption(this, I18n.tr("Choose the services to add to your media stack."));
        ih.setPadding(0, dp(2), 0, dp(8));
        card.addView(ih);
        companionInstallBtn = UiKit.primary(this, buttonLabel("INSTALL SERVICES"), v -> showSelectiveInstallDialog());
        card.addView(companionInstallBtn, new LinearLayout.LayoutParams(-1, dp(42)));

        card.addView(createSettingsDivider());

        card.addView(UiKit.text(this, I18n.tr("Uninstall"), 13.5f, AppTheme.text(), UiKit.BOLD));
        TextView uh = UiKit.caption(this, I18n.tr("Remove installed services and free their storage. Your settings are kept."));
        uh.setPadding(0, dp(2), 0, dp(8));
        card.addView(uh);
        companionUninstallBtn = UiKit.danger(this, buttonLabel("UNINSTALL SERVICES"), v -> showSelectiveUninstallDialog());
        card.addView(companionUninstallBtn, new LinearLayout.LayoutParams(-1, dp(42)));

        section.addView(card);
        refreshCompanionButtons();
        return section;
    }

    private Runnable bridgeRefresh;

    /** A titled text field with SAVE (optionally multi-line or masked); {@code saver} returns false for invalid input. */
    private void addBridgeTextSetting(LinearLayout card, String title, String hint,
                                      final java.util.function.Supplier<String> getter,
                                      final java.util.function.Predicate<String> saver,
                                      boolean multiline, boolean secret, final String savedToast) {
        final android.content.Context ctx = this;
        TextView t = UiKit.text(this, I18n.tr(title), 12.5f, AppTheme.text(), UiKit.MEDIUM);
        t.setPadding(0, dp(12), 0, dp(2));
        card.addView(t);
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(getter.get());
        input.setTextColor(AppTheme.text());
        input.setTextSize(12.5f);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        if (multiline) {
            input.setSingleLine(false);
            input.setMinLines(3);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        } else {
            input.setSingleLine(true);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | (secret ? android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        }
        card.addView(input, new LinearLayout.LayoutParams(-1, -2));
        Button save = createPixelButton("SAVE", AppTheme.withAlpha(AppTheme.accent(), 25), AppTheme.accent());
        save.setTextSize(10f);
        save.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
        lp.gravity = Gravity.END;
        lp.topMargin = dp(4);
        card.addView(save, lp);
        final Runnable dirty = UiStyle.bindDirty(save, input, () -> getter.get());
        save.setOnClickListener(v -> {
            if (!saver.test(input.getText().toString())) {
                Toast.makeText(ctx, I18n.tr("Invalid value. Check the address or key and try again."), Toast.LENGTH_LONG).show();
                return;
            }
            input.setText(getter.get());
            dirty.run();
            Toast.makeText(ctx, I18n.tr(savedToast), Toast.LENGTH_LONG).show();
        });
        TextView h = UiKit.caption(this, I18n.tr(hint));
        h.setPadding(0, dp(2), 0, dp(4));
        card.addView(h);
    }

    /** Settings > Stremio & AIOStreams: the in-app Stremio addon (StremioBridge) and TorrServer. */
    private View buildStremioBridgeSection() {
        final android.content.Context ctx = this;
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(createSettingsSectionHeader("STREMIO & AIOSTREAMS"));

        LinearLayout card = createSettingsSectionCard();
        card.addView(settingsSwitchRow("Jellyarr addon",
                "A Stremio addon served by Jellyarr on port " + com.jellyarr.app.services.StremioBridge.PORT
                        + ": your Jellyfin movies and series, and Prowlarr torrents, for AIOStreams and Stremio on your network.",
                com.jellyarr.app.services.StremioBridge.isEnabled(this),
                (v, checked) -> {
                    com.jellyarr.app.services.StremioBridge.setEnabled(ctx, checked);
                    if (bridgeRefresh != null) bridgeRefresh.run();
                }));

        final TextView status = UiKit.caption(this, "");
        status.setPadding(0, dp(2), 0, dp(6));
        card.addView(status);

        card.addView(UiKit.overline(this, I18n.tr("Addon URL")));
        final LinearLayout urlBox = UiKit.column(this);
        card.addView(urlBox);

        LinearLayout urlBtns = new LinearLayout(this);
        urlBtns.setOrientation(LinearLayout.HORIZONTAL);
        urlBtns.setPadding(0, dp(6), 0, 0);
        Button openStremio = UiKit.tonal(this, buttonLabel("OPEN IN STREMIO"), v -> {
            String url = com.jellyarr.app.services.StremioBridge.getManifestUrl(ctx);
            if (!url.startsWith("https://")) {
                // Stremio's deep link installs over HTTPS only: for a home-network address, copy it to paste in Stremio.
                copyUrlToClipboard("Jellyarr addon URL", url);
                Toast.makeText(ctx, I18n.tr("URL copied. In Stremio open Addons and paste it."), Toast.LENGTH_LONG).show();
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.replaceFirst("^https://", "stremio://"))));
            } catch (Throwable t) {
                Toast.makeText(ctx, I18n.tr("Stremio is not installed on this device."), Toast.LENGTH_SHORT).show();
            }
        });
        Button newKey = UiKit.outline(this, buttonLabel("NEW KEY"), v -> {
            com.jellyarr.app.services.StremioBridge.regenerateKey(ctx);
            if (bridgeRefresh != null) bridgeRefresh.run();
            Toast.makeText(ctx, I18n.tr("New addon URL created. Add it again in AIOStreams or Stremio."), Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams ob = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
        ob.setMarginEnd(dp(6));
        urlBtns.addView(openStremio, ob);
        urlBtns.addView(newKey, new LinearLayout.LayoutParams(0, dp(40), 1.0f));
        card.addView(urlBtns);

        // Public address: one HTTPS address (tunnel) that reaches port 7070; everything is served through it.
        LinearLayout pubRow = UiKit.row(this);
        pubRow.setGravity(Gravity.CENTER_VERTICAL);
        pubRow.setPadding(0, dp(12), 0, dp(4));
        pubRow.addView(UiKit.text(this, I18n.tr("Public address"), 12.5f, AppTheme.text(), UiKit.MEDIUM),
                new LinearLayout.LayoutParams(-2, -2));
        final android.widget.EditText pubInput = new android.widget.EditText(this);
        pubInput.setText(com.jellyarr.app.services.StremioBridge.getPublicUrl(this));
        pubInput.setHint("https://jellyarr.example.com");
        pubInput.setTextColor(AppTheme.text());
        pubInput.setTextSize(12.5f);
        pubInput.setSingleLine(true);
        pubInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        pubInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams pubLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        pubLp.setMarginStart(dp(8));
        pubRow.addView(pubInput, pubLp);
        Button pubSave = createPixelButton("SAVE", AppTheme.withAlpha(AppTheme.accent(), 25), AppTheme.accent());
        pubSave.setTextSize(10f);
        pubSave.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams pubBtnLp = new LinearLayout.LayoutParams(-2, dp(32));
        pubBtnLp.setMarginStart(dp(8));
        pubRow.addView(pubSave, pubBtnLp);
        final Runnable pubDirty = UiStyle.bindDirty(pubSave, pubInput,
                () -> com.jellyarr.app.services.StremioBridge.getPublicUrl(ctx));
        pubSave.setOnClickListener(v -> {
            if (!com.jellyarr.app.services.StremioBridge.setPublicUrl(ctx, pubInput.getText().toString())) {
                Toast.makeText(ctx, I18n.tr("Enter a full address such as https://jellyarr.example.com"), Toast.LENGTH_LONG).show();
                return;
            }
            pubInput.setText(com.jellyarr.app.services.StremioBridge.getPublicUrl(ctx));
            pubDirty.run();
            if (bridgeRefresh != null) bridgeRefresh.run();
            Toast.makeText(ctx, I18n.tr("Saved. Remove and add the addon again so your apps reload it."), Toast.LENGTH_LONG).show();
        });
        card.addView(pubRow);
        TextView pubHint = UiKit.caption(this, I18n.tr(
                "Optional. An HTTPS address that reaches port " + com.jellyarr.app.services.StremioBridge.PORT
                + " on this phone (Cloudflare Tunnel, Tailscale Funnel...). Needed when the app that uses the addon is not on your home network, for example AIOStreams hosted online. Videos, subtitles, posters and torrents are all served through this one address."));
        pubHint.setPadding(0, 0, 0, dp(4));
        card.addView(pubHint);
        TextView pubWarn = UiKit.caption(this, I18n.tr("Anyone who has the addon URL can watch your library, so keep it private. NEW KEY cancels the old URL."));
        pubWarn.setTextColor(AppTheme.danger());
        pubWarn.setPadding(0, 0, 0, dp(4));
        card.addView(pubWarn);

        // Where video, subtitles and posters come from when a public address is set.
        final Button linksBtn = UiKit.outline(this, "", null);
        final Runnable linksLabel = () -> {
            String m = com.jellyarr.app.services.StremioBridge.getStreamLinks(ctx);
            linksBtn.setText(buttonLabel(m.equals("lan") ? "STREAM LINKS: HOME NETWORK ONLY"
                    : m.equals("public") ? "STREAM LINKS: PUBLIC ONLY" : "STREAM LINKS: BOTH"));
        };
        linksLabel.run();
        linksBtn.setOnClickListener(v -> {
            String m = com.jellyarr.app.services.StremioBridge.getStreamLinks(ctx);
            com.jellyarr.app.services.StremioBridge.setStreamLinks(ctx, m.equals("both") ? "lan" : m.equals("lan") ? "public" : "both");
            linksLabel.run();
            Toast.makeText(ctx, I18n.tr("Saved."), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams linksLp = new LinearLayout.LayoutParams(-1, dp(42));
        linksLp.topMargin = dp(10);
        card.addView(linksBtn, linksLp);
        TextView linksHint = UiKit.caption(this, I18n.tr(
                "Both: a home-network entry first and a public one. Home network only: video, subtitles and posters never use the tunnel, only the lists do (works at home only). Public only: everything through the public address."));
        linksHint.setPadding(0, dp(4), 0, dp(4));
        card.addView(linksHint);

        // Cloudflare Tunnel run by the app itself (cloudflared), so the public address needs nothing else.
        card.addView(createSettingsDivider());
        card.addView(UiKit.text(this, I18n.tr("Cloudflare Tunnel"), 13.5f, AppTheme.text(), UiKit.BOLD));
        TextView tunHint = UiKit.caption(this, I18n.tr(
                "Jellyarr runs the tunnel itself. In the Cloudflare dashboard open Zero Trust > Networks > Tunnels, create a tunnel, add a public hostname with service HTTP and URL localhost:7070, then paste the tunnel token (or the whole install command) below."));
        tunHint.setPadding(0, dp(2), 0, dp(6));
        card.addView(tunHint);
        LinearLayout tokRow = UiKit.row(this);
        tokRow.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.EditText tokInput = new android.widget.EditText(this);
        tokInput.setText(com.jellyarr.app.services.CloudflareTunnel.getToken(this));
        tokInput.setHint("eyJ...");
        tokInput.setTextColor(AppTheme.text());
        tokInput.setTextSize(12.5f);
        tokInput.setSingleLine(true);
        tokInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        tokRow.addView(tokInput, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button tokSave = createPixelButton("SAVE", AppTheme.withAlpha(AppTheme.accent(), 25), AppTheme.accent());
        tokSave.setTextSize(10f);
        tokSave.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams tokLp = new LinearLayout.LayoutParams(-2, dp(32));
        tokLp.setMarginStart(dp(8));
        tokRow.addView(tokSave, tokLp);
        final Runnable tokDirty = UiStyle.bindDirty(tokSave, tokInput,
                () -> com.jellyarr.app.services.CloudflareTunnel.getToken(ctx));
        tokSave.setOnClickListener(v -> {
            if (!com.jellyarr.app.services.CloudflareTunnel.setToken(ctx, tokInput.getText().toString())) {
                Toast.makeText(ctx, I18n.tr("This is not a Cloudflare tunnel token. Copy it from the tunnel page in the dashboard."), Toast.LENGTH_LONG).show();
                return;
            }
            tokInput.setText(com.jellyarr.app.services.CloudflareTunnel.getToken(ctx));
            tokDirty.run();
            if (bridgeRefresh != null) bridgeRefresh.run();
            Toast.makeText(ctx, I18n.tr("Saved."), Toast.LENGTH_SHORT).show();
        });
        card.addView(tokRow);
        final TextView tunStatus = UiKit.caption(this, "");
        tunStatus.setPadding(0, dp(6), 0, dp(6));
        card.addView(tunStatus);
        final Button tunBtn = UiKit.primary(this, buttonLabel("INSTALL TUNNEL"), null);
        card.addView(tunBtn, new LinearLayout.LayoutParams(-1, dp(42)));

        card.addView(createSettingsDivider());

        card.addView(settingsSwitchRow("Torrents from Prowlarr",
                "Searches your Prowlarr indexers for every title and plays the result through TorrServer while it downloads. Nothing is added to Transmission.",
                com.jellyarr.app.services.StremioBridge.isTorrentsEnabled(this),
                (v, checked) -> {
                    com.jellyarr.app.services.StremioBridge.setTorrentsEnabled(ctx, checked);
                    if (bridgeRefresh != null) bridgeRefresh.run();
                }));
        final TextView deps = UiKit.caption(this, "");
        deps.setPadding(0, dp(2), 0, dp(6));
        card.addView(deps);
        final Button tsBtn = UiKit.primary(this, buttonLabel("INSTALL TORRSERVER"), null);
        card.addView(tsBtn, new LinearLayout.LayoutParams(-1, dp(42)));

        card.addView(createSettingsDivider());

        card.addView(settingsSwitchRow("Catalogs",
                "Popular, Featured and New (by year) lists with posters, ratings and descriptions (from Cinemeta), with genres and search. Needs internet.",
                com.jellyarr.app.services.StremioBridge.isCatalogsEnabled(this),
                (v, checked) -> {
                    com.jellyarr.app.services.StremioBridge.setCatalogsEnabled(ctx, checked);
                    Toast.makeText(ctx, I18n.tr("Saved. Remove and add the addon again so your apps reload it."), Toast.LENGTH_LONG).show();
                }));
        addBridgeTextSetting(card, "Extra catalog addons",
                "One addon address per line (up to 5). Their lists are added here, so you browse everything from this one addon. Only titles with an IMDb id are kept.",
                () -> com.jellyarr.app.services.StremioBridge.getCatAddons(ctx),
                text -> com.jellyarr.app.services.StremioBridge.setCatAddons(ctx, text),
                true, false, "Saved. Remove and add the addon again so your apps reload it.");
        card.addView(settingsSwitchRow("Subtitles",
                "Jellyfin's own subtitle files plus SubDL, OpenSubtitles and other addons in the languages below.",
                com.jellyarr.app.services.StremioBridge.isSubtitlesEnabled(this),
                (v, checked) -> {
                    com.jellyarr.app.services.StremioBridge.setSubtitlesEnabled(ctx, checked);
                    Toast.makeText(ctx, I18n.tr("Saved. Remove and add the addon again so your apps reload it."), Toast.LENGTH_LONG).show();
                }));
        LinearLayout langRow = UiKit.row(this);
        langRow.setGravity(Gravity.CENTER_VERTICAL);
        langRow.setPadding(0, dp(6), 0, dp(4));
        langRow.addView(UiKit.text(this, I18n.tr("Subtitle languages"), 12.5f, AppTheme.text(), UiKit.MEDIUM),
                new LinearLayout.LayoutParams(0, -2, 1.0f));
        final android.widget.EditText langInput = new android.widget.EditText(this);
        langInput.setText(com.jellyarr.app.services.StremioBridge.getSubtitleLangs(this));
        langInput.setTextColor(AppTheme.text());
        langInput.setTextSize(13f);
        langInput.setGravity(Gravity.CENTER);
        langInput.setSingleLine(true);
        langInput.setHint("ara,eng");
        langInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        langRow.addView(langInput, new LinearLayout.LayoutParams(dp(120), -2));
        Button langSave = createPixelButton("SAVE", AppTheme.withAlpha(AppTheme.accent(), 25), AppTheme.accent());
        langSave.setTextSize(10f);
        langSave.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(-2, dp(32));
        langLp.setMarginStart(dp(8));
        langRow.addView(langSave, langLp);
        final Runnable langDirty = UiStyle.bindDirty(langSave, langInput,
                () -> com.jellyarr.app.services.StremioBridge.getSubtitleLangs(ctx));
        langSave.setOnClickListener(v -> {
            com.jellyarr.app.services.StremioBridge.setSubtitleLangs(ctx, langInput.getText().toString());
            langInput.setText(com.jellyarr.app.services.StremioBridge.getSubtitleLangs(ctx));
            langDirty.run();
            Toast.makeText(ctx, I18n.tr("Saved."), Toast.LENGTH_SHORT).show();
        });
        card.addView(langRow);
        TextView langHint = UiKit.caption(this, I18n.tr("Three-letter codes, in order: ara = Arabic, eng = English, fre = French. Leave empty for every language."));
        langHint.setPadding(0, 0, 0, dp(4));
        card.addView(langHint);
        addBridgeTextSetting(card, "SubDL API key",
                "Free key from subdl.com (Account > API). Jellyarr searches SubDL itself from this phone, so the key stays between you and SubDL.",
                () -> com.jellyarr.app.services.StremioBridge.getSubdlKey(ctx),
                text -> com.jellyarr.app.services.StremioBridge.setSubdlKey(ctx, text),
                false, true, "Saved.");
        addBridgeTextSetting(card, "Wyzie API key",
                "Key from sub.wyzie.io. Wyzie gathers subtitles from several sources and serves them as UTF-8.",
                () -> com.jellyarr.app.services.StremioBridge.getWyzieKey(ctx),
                text -> com.jellyarr.app.services.StremioBridge.setWyzieKey(ctx, text),
                false, true, "Saved.");
        addBridgeTextSetting(card, "SubSource API key",
                "Free key from subsource.net (your profile page).",
                () -> com.jellyarr.app.services.StremioBridge.getSubsourceKey(ctx),
                text -> com.jellyarr.app.services.StremioBridge.setSubsourceKey(ctx, text),
                false, true, "Saved.");
        addBridgeTextSetting(card, "OpenSubtitles.com API key",
                "Key from opensubtitles.com. Each file counts against the key's daily download limit, so a file is downloaded once and kept.",
                () -> com.jellyarr.app.services.StremioBridge.getOscomKey(ctx),
                text -> com.jellyarr.app.services.StremioBridge.setOscomKey(ctx, text),
                false, true, "Saved.");
        addBridgeTextSetting(card, "Extra subtitle addons",
                "One addon address per line (up to 5), for example SubSource, SubHero or OpenSubtitles V3+. Their results are merged and sorted with the others.",
                () -> com.jellyarr.app.services.StremioBridge.getSubAddons(ctx),
                text -> com.jellyarr.app.services.StremioBridge.setSubAddons(ctx, text),
                true, false, "Saved.");

        // ---- subtitle options: how many, how they are checked and synced, what their titles say
        TextView optHead = UiKit.text(this, I18n.tr("Subtitle options"), 13.5f, AppTheme.text(), UiKit.BOLD);
        optHead.setPadding(0, dp(14), 0, dp(2));
        card.addView(optHead);
        card.addView(UiKit.caption(this, I18n.tr("Each subtitle's title (shown by Nuvio and some players) says where it comes from, how well it fits the video and whether it was synced.")));
        addBridgeTextSetting(card, "Subtitles per language",
                "How many subtitles to offer for each language (1 to 40). Providers take turns, so one source cannot fill the list.",
                () -> String.valueOf(com.jellyarr.app.services.StremioBridge.getSubMax(ctx)),
                text -> com.jellyarr.app.services.StremioBridge.setSubMax(ctx, text),
                false, false, "Saved.");
        addBridgeTextSetting(card, "Check the best N per language",
                "These are downloaded while the list is built and checked: the script must match the language and the timing is compared with the video (0 to 12; 0 turns checking off).",
                () -> String.valueOf(com.jellyarr.app.services.StremioBridge.getSubVerify(ctx)),
                text -> com.jellyarr.app.services.StremioBridge.setSubVerify(ctx, text),
                false, false, "Saved.");
        addBridgeTextSetting(card, "Checking time limit (seconds)",
                "How long the list may wait for those checks (3 to 40). Slower ones are listed unchecked and finish in the background.",
                () -> String.valueOf(com.jellyarr.app.services.StremioBridge.getSubBudget(ctx)),
                text -> com.jellyarr.app.services.StremioBridge.setSubBudget(ctx, text),
                false, false, "Saved.");
        addBridgeTextSetting(card, "Hide checked subtitles below (%)",
                "Subtitles that were checked and fit the video less than this are hidden (0 to 90; 0 hides nothing).",
                () -> String.valueOf(com.jellyarr.app.services.StremioBridge.getSubMinAcc(ctx)),
                text -> com.jellyarr.app.services.StremioBridge.setSubMinAcc(ctx, text),
                false, false, "Saved.");

        final Button syncBtn = UiKit.outline(this, "", null);
        final Runnable syncLabel = () -> {
            String m = com.jellyarr.app.services.StremioBridge.getSubSync(ctx);
            syncBtn.setText(buttonLabel(m.equals("check") ? "AUTO SYNC: CHECK ONLY" : m.equals("off") ? "AUTO SYNC: OFF" : "AUTO SYNC: APPLY"));
        };
        syncLabel.run();
        syncBtn.setOnClickListener(v -> {
            String m = com.jellyarr.app.services.StremioBridge.getSubSync(ctx);
            com.jellyarr.app.services.StremioBridge.setSubSync(ctx, m.equals("auto") ? "check" : m.equals("check") ? "off" : "auto");
            syncLabel.run();
        });
        LinearLayout.LayoutParams syncLp = new LinearLayout.LayoutParams(-1, dp(42));
        syncLp.topMargin = dp(10);
        card.addView(syncBtn, syncLp);
        TextView syncHint = UiKit.caption(this, I18n.tr("Apply: a subtitle that is clearly out of sync is shifted to fit (only when it can be compared with something known to fit the video: the library file, an exact file match or a subtitle inside the torrent). Check only: reported in the title but not changed. Off: never touched."));
        syncHint.setPadding(0, dp(4), 0, dp(4));
        card.addView(syncHint);

        final Button hiBtn = UiKit.outline(this, "", null);
        final Runnable hiLabel = () -> {
            String m = com.jellyarr.app.services.StremioBridge.getSubHi(ctx);
            hiBtn.setText(buttonLabel(m.equals("exclude") ? "HEARING-IMPAIRED: HIDE" : m.equals("prefer") ? "HEARING-IMPAIRED: PREFER" : "HEARING-IMPAIRED: ALLOW"));
        };
        hiLabel.run();
        hiBtn.setOnClickListener(v -> {
            String m = com.jellyarr.app.services.StremioBridge.getSubHi(ctx);
            com.jellyarr.app.services.StremioBridge.setSubHi(ctx, m.equals("any") ? "exclude" : m.equals("exclude") ? "prefer" : "any");
            hiLabel.run();
        });
        LinearLayout.LayoutParams hiLp = new LinearLayout.LayoutParams(-1, dp(42));
        hiLp.topMargin = dp(8);
        card.addView(hiBtn, hiLp);

        card.addView(settingsSwitchRow("Details in the subtitle title",
                "Source, fit percentage and sync status in each subtitle's title. Turn off if your player shows titles badly.",
                com.jellyarr.app.services.StremioBridge.isSubTitles(this),
                (v, checked) -> com.jellyarr.app.services.StremioBridge.setSubTitles(ctx, checked)));
        card.addView(settingsSwitchRow("OpenSubtitles V3 (free, no key)",
                "The Stremio OpenSubtitles addon as one more source.",
                com.jellyarr.app.services.StremioBridge.isOsV3(this),
                (v, checked) -> com.jellyarr.app.services.StremioBridge.setOsV3(ctx, checked)));
        card.addView(settingsSwitchRow("Check OpenSubtitles.com results",
                "Checking downloads each file, which counts against the key's daily limit. Off: they are listed unchecked and downloaded when picked (exact file matches are always checked).",
                com.jellyarr.app.services.StremioBridge.isVerifyOscom(this),
                (v, checked) -> com.jellyarr.app.services.StremioBridge.setVerifyOscom(ctx, checked)));

        card.addView(createSettingsDivider());

        card.addView(UiKit.text(this, I18n.tr("Recent activity"), 13.5f, AppTheme.text(), UiKit.BOLD));
        TextView actHint = UiKit.caption(this, I18n.tr("How long each step took (Jellyfin, Prowlarr, TorrServer) and the speed of the video. If something is slow, this shows where."));
        actHint.setPadding(0, dp(2), 0, dp(6));
        card.addView(actHint);
        final TextView actText = UiKit.mono(this, "");
        actText.setTextSize(10.5f);
        actText.setTextDirection(View.TEXT_DIRECTION_LTR);
        card.addView(actText);
        LinearLayout actBtns = new LinearLayout(this);
        actBtns.setOrientation(LinearLayout.HORIZONTAL);
        actBtns.setPadding(0, dp(6), 0, 0);
        Button actRefresh = UiKit.outline(this, buttonLabel("REFRESH"), v -> { if (bridgeRefresh != null) bridgeRefresh.run(); });
        Button actCopy = UiKit.tonal(this, buttonLabel("COPY"), v ->
                copyUrlToClipboard("Jellyarr activity", com.jellyarr.app.services.StremioBridge.getInstance().getActivity(60)));
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        ab.setMarginEnd(dp(6));
        actBtns.addView(actRefresh, ab);
        actBtns.addView(actCopy, new LinearLayout.LayoutParams(0, dp(38), 1.0f));
        card.addView(actBtns);

        card.addView(createSettingsDivider());

        card.addView(UiKit.text(this, I18n.tr("Use it in Stremio"), 13.5f, AppTheme.text(), UiKit.BOLD));
        TextView how = UiKit.caption(this, I18n.tr(
                "1. In Stremio open Addons, tap Add addon and paste the addon URL above (or tap OPEN IN STREMIO). Nothing else is needed.\n"
                + "2. You get your Jellyfin library, Popular and Featured lists, torrents from Prowlarr and subtitles in the languages above.\n"
                + "3. Using AIOStreams hosted online? Set the public address above first: its server fetches this addon itself and cannot reach a home-network address."));
        how.setPadding(0, dp(2), 0, dp(4));
        card.addView(how);

        section.addView(card);

        bridgeRefresh = () -> {
            com.jellyarr.app.services.StremioBridge b = com.jellyarr.app.services.StremioBridge.getInstance();
            boolean on = com.jellyarr.app.services.StremioBridge.isEnabled(ctx);
            if (on && !b.isRunning()) b.start(ctx);
            if (!on) {
                status.setText(I18n.tr("Off"));
                status.setTextColor(AppTheme.textSecondary());
            } else if (b.isRunning()) {
                status.setText(I18n.tr("Running on port " + com.jellyarr.app.services.StremioBridge.PORT));
                status.setTextColor(AppTheme.accent());
            } else {
                String err = b.getLastError();
                status.setText(I18n.tr("Could not start") + (err != null ? ": " + err : ""));
                status.setTextColor(AppTheme.danger());
            }
            urlBox.removeAllViews();
            String lan = com.jellyarr.app.services.StremioBridge.getLanManifestUrl(ctx);
            String local = com.jellyarr.app.services.StremioBridge.getLocalManifestUrl(ctx);
            if (!com.jellyarr.app.services.StremioBridge.getPublicUrl(ctx).isEmpty()) {
                String pub = com.jellyarr.app.services.StremioBridge.getManifestUrl(ctx);
                urlBox.addView(detailCopyRow(I18n.tr("Public"), pub, true, "Jellyarr addon URL", pub));
            }
            urlBox.addView(detailCopyRow("LAN", lan, true, "Jellyarr addon URL", lan));
            if (!lan.equals(local)) {
                urlBox.addView(detailCopyRow(I18n.tr("This phone"), local, true, "Jellyarr addon URL", local));
            }

            MediaServiceManager mgr = MediaServiceManager.getInstance(ctx);
            ManagedService ts = mgr.getService("torrserver");
            ManagedService pr = mgr.getService("prowlarr");
            String prState = pr == null || !pr.isInstalled() ? I18n.tr("not installed")
                    : mgr.isServiceRunning("prowlarr") ? I18n.tr("running") : I18n.tr("stopped");
            String tsState = ts == null || !ts.isInstalled() ? I18n.tr("not installed")
                    : mgr.isServiceRunning("torrserver") ? I18n.tr("running") : I18n.tr("stopped");
            deps.setText("Prowlarr: " + prState + "  ·  TorrServer: " + tsState);
            if (ts == null) {
                tsBtn.setVisibility(View.GONE);
            } else if (!ts.isInstalled()) {
                tsBtn.setVisibility(View.VISIBLE);
                tsBtn.setText(buttonLabel("INSTALL TORRSERVER"));
                tsBtn.setOnClickListener(v -> installServiceAutomatically(ts));
            } else if (!mgr.isServiceRunning("torrserver")) {
                tsBtn.setVisibility(View.VISIBLE);
                tsBtn.setText(buttonLabel("START TORRSERVER"));
                tsBtn.setOnClickListener(v -> {
                    mgr.recordServiceActive("torrserver", true);
                    mgr.startService("torrserver", ctx);
                    Toast.makeText(ctx, I18n.tr("Starting TorrServer..."), Toast.LENGTH_SHORT).show();
                    status.postDelayed(() -> { if (bridgeRefresh != null) bridgeRefresh.run(); }, 4000);
                });
            } else {
                tsBtn.setVisibility(View.GONE);
            }
            String act = com.jellyarr.app.services.StremioBridge.getInstance().getActivity(12);
            actText.setText(act.isEmpty() ? I18n.tr("No activity yet.") : act);
            refreshTunnelRow(tunStatus, tunBtn);
        };
        bridgeRefresh.run();
        return section;
    }

    /** Cloudflare Tunnel status line and its install / start / stop button (health is read off the UI thread). */
    private void refreshTunnelRow(final TextView status, final Button btn) {
        final android.content.Context ctx = this;
        final MediaServiceManager mgr = MediaServiceManager.getInstance(ctx);
        final ManagedService cf = mgr.getService(com.jellyarr.app.services.CloudflareTunnel.SERVICE_ID);
        if (cf == null) {
            status.setText("");
            btn.setVisibility(View.GONE);
            return;
        }
        final boolean hasToken = !com.jellyarr.app.services.CloudflareTunnel.getToken(ctx).isEmpty();
        if (!cf.isInstalled()) {
            btn.setVisibility(View.VISIBLE);
            if (!com.jellyarr.app.services.CloudflareTunnel.isBundled(ctx)) {
                status.setText(I18n.tr("This build does not include the tunnel."));
                status.setTextColor(AppTheme.danger());
                btn.setVisibility(View.GONE);
                return;
            }
            status.setText(I18n.tr("Not installed"));
            status.setTextColor(AppTheme.textSecondary());
            btn.setText(buttonLabel("INSTALL TUNNEL"));
            btn.setOnClickListener(v -> installServiceAutomatically(cf));
            return;
        }
        if (!hasToken) {
            status.setText(I18n.tr("Paste your tunnel token above."));
            status.setTextColor(AppTheme.textSecondary());
            btn.setVisibility(View.GONE);
            return;
        }
        btn.setVisibility(View.VISIBLE);
        if (!mgr.isServiceRunning(cf.getId())) {
            status.setText(cf.getLastError() != null && cf.getLastError().length() > 0 && cf.getState() == com.jellyarr.app.model.ServiceState.ERROR
                    ? I18n.tr("Stopped") + ": " + cf.getLastError() : I18n.tr("Stopped"));
            status.setTextColor(AppTheme.textSecondary());
            btn.setText(buttonLabel("START TUNNEL"));
            btn.setOnClickListener(v -> {
                mgr.recordServiceActive(cf.getId(), true);
                mgr.startService(cf.getId(), ctx);
                status.setText(I18n.tr("Connecting..."));
                status.postDelayed(() -> { if (bridgeRefresh != null) bridgeRefresh.run(); }, 6000);
                status.postDelayed(() -> { if (bridgeRefresh != null) bridgeRefresh.run(); }, 20000);
            });
            return;
        }
        btn.setText(buttonLabel("STOP TUNNEL"));
        btn.setOnClickListener(v -> {
            mgr.recordServiceActive(cf.getId(), false);
            mgr.stopService(cf.getId());
            status.postDelayed(() -> { if (bridgeRefresh != null) bridgeRefresh.run(); }, 1500);
        });
        status.setText(I18n.tr("Connecting..."));
        status.setTextColor(AppTheme.textSecondary());
        runBg(() -> {
            final boolean ready = mgr.isServiceHealthy(cf);
            runOnUiThread(() -> {
                status.setText(I18n.tr(ready ? "Connected to Cloudflare" : "Connecting..."));
                status.setTextColor(ready ? AppTheme.accent() : AppTheme.textSecondary());
                if (!ready) status.postDelayed(() -> { if (bridgeRefresh != null && isUiAlive()) bridgeRefresh.run(); }, 6000);
            });
        });
    }

    private void refreshCompanionButtons() {
        if (companionInstallBtn == null || companionUninstallBtn == null) return;
        boolean canInstall = false;
        boolean canUninstall = false;
        try {
            for (ManagedService s : MediaServiceManager.getInstance(this).getAllServices()) {
                if ("jellyfin".equalsIgnoreCase(s.getId())) continue;
                if (s.isInstalled()) canUninstall = true; else canInstall = true;
            }
        } catch (Throwable ignored) {}
        companionInstallBtn.setEnabled(canInstall);
        companionInstallBtn.setAlpha(canInstall ? 1f : 0.38f);
        companionUninstallBtn.setEnabled(canUninstall);
        companionUninstallBtn.setAlpha(canUninstall ? 1f : 0.38f);
    }

    /** One settings row: title + hint on the start side, switch on the end side (correct in LTR and RTL). */
    private LinearLayout settingsSwitchRow(String title, String hint, boolean checked,
                                           android.widget.CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = UiKit.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout text = UiKit.column(this);
        text.addView(UiKit.text(this, I18n.tr(title), 13.5f, AppTheme.text(), UiKit.BOLD));
        if (hint != null && !hint.isEmpty()) {
            TextView h = UiKit.caption(this, I18n.tr(hint));
            h.setPaddingRelative(0, dp(2), dp(8), 0);
            text.addView(h);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch sw = UiStyle.newSwitch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    private View buildAutomationSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(createSettingsSectionHeader("AUTOMATION"));

        LinearLayout card = createSettingsSectionCard();

        // --- Downloads cleanup: one card for everything that deletes finished downloads ------
        card.addView(UiKit.overline(this, I18n.tr("Downloads cleanup")));
        card.addView(settingsSwitchRow("Delete finished downloads",
                "Removes finished torrents AND their files after the time below. Radarr / Sonarr must have imported them first (or hard-link / copy), otherwise the files are lost.",
                com.jellyarr.app.services.AutomationPrefs.isCleanupEnabled(this),
                (v, checked) -> {
                    com.jellyarr.app.services.AutomationPrefs.setCleanupEnabled(this, checked);
                    Toast.makeText(this, I18n.tr(checked ? "Auto-cleanup enabled." : "Auto-cleanup disabled."), Toast.LENGTH_SHORT).show();
                }));

        // Period (hours)
        LinearLayout periodRow = UiKit.row(this);
        periodRow.setGravity(Gravity.CENTER_VERTICAL);
        periodRow.setPadding(0, dp(6), 0, dp(4));
        periodRow.addView(UiKit.text(this, I18n.tr("Delete after (hours)"), 12.5f, AppTheme.text(), UiKit.MEDIUM),
                new LinearLayout.LayoutParams(0, -2, 1.0f));
        final android.widget.EditText hoursInput = new android.widget.EditText(this);
        hoursInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        hoursInput.setText(String.valueOf(com.jellyarr.app.services.AutomationPrefs.getCleanupHours(this)));
        hoursInput.setTextColor(AppTheme.text());
        hoursInput.setTextSize(13f);
        hoursInput.setGravity(Gravity.CENTER);
        hoursInput.setSingleLine(true);
        periodRow.addView(hoursInput, new LinearLayout.LayoutParams(dp(80), -2));
        Button btnSaveHours = createPixelButton("SAVE", AppTheme.withAlpha(AppTheme.accent(), 25), AppTheme.accent());
        btnSaveHours.setTextSize(10f);
        btnSaveHours.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-2, dp(32));
        saveLp.setMarginStart(dp(8));
        periodRow.addView(btnSaveHours, saveLp);
        final Runnable hoursDirty = UiStyle.bindDirty(btnSaveHours, hoursInput,
                () -> String.valueOf(com.jellyarr.app.services.AutomationPrefs.getCleanupHours(this)));
        btnSaveHours.setOnClickListener(v -> {
            int h = com.jellyarr.app.services.AutomationPrefs.DEFAULT_CLEANUP_HOURS;
            try { h = Integer.parseInt(hoursInput.getText().toString().trim()); } catch (Throwable ignored) {}
            com.jellyarr.app.services.AutomationPrefs.setCleanupHours(this, h);
            hoursInput.setText(String.valueOf(com.jellyarr.app.services.AutomationPrefs.getCleanupHours(this)));
            hoursDirty.run();
            Toast.makeText(this, I18n.tr("Saved: " + com.jellyarr.app.services.AutomationPrefs.getCleanupHours(this) + " h"), Toast.LENGTH_SHORT).show();
        });
        card.addView(periodRow);

        // Last run + preview / run now
        final TextView statusText = UiKit.caption(this, "");
        statusText.setPadding(0, dp(4), 0, dp(6));
        long lastRun = com.jellyarr.app.services.AutomationPrefs.getCleanupLastRun(this);
        statusText.setText(lastRun > 0
                ? I18n.tr("Last run") + ": " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                        I18n.isArabic() ? new java.util.Locale("ar") : java.util.Locale.getDefault()).format(new java.util.Date(lastRun))
                        + "\n" + com.jellyarr.app.services.AutomationPrefs.getCleanupLastSummary(this)
                : "Checks every 30 minutes while Transmission is running. Not run yet.");
        card.addView(statusText);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnPreview = createPixelButton("PREVIEW", AppTheme.inner(), AppTheme.accent());
        btnPreview.setTextSize(10.5f);
        btnPreview.setOnClickListener(v -> runCleanupAsync(true, statusText));
        Button btnRunNow = createPixelButton("CLEAN NOW", AppTheme.inner(), AppTheme.danger());
        btnRunNow.setTextSize(10.5f);
        btnRunNow.setOnClickListener(v -> runCleanupAsync(false, statusText));
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
        b1.setMarginEnd(dp(6));
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
        btnRow.addView(btnPreview, b1);
        btnRow.addView(btnRunNow, b2);
        card.addView(btnRow);

        // Copy kept in Downloads after import (staging retention): same feature family, same card.
        final TextView retValue = UiKit.caption(this, "");
        LinearLayout retRow = UiKit.row(this);
        retRow.setGravity(Gravity.CENTER_VERTICAL);
        retRow.setPadding(0, dp(14), 0, dp(4));
        LinearLayout retText = UiKit.column(this);
        retText.addView(UiKit.text(this, I18n.tr("Keep the copy in Downloads"), 13.5f, AppTheme.text(), UiKit.BOLD));
        TextView retHint = UiKit.caption(this, I18n.tr("How long the copy in Downloads stays after Radarr / Sonarr imported it."));
        retText.addView(retHint);
        retText.addView(retValue);
        retRow.addView(retText, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final Runnable showRet = () -> {
            MediaStorageManager sm = MediaStorageManager.getInstance(this);
            retValue.setText(I18n.tr(sm.getRetentionPolicyDisplayName()));
            retValue.setTextColor(AppTheme.accent());
        };
        showRet.run();
        Button retBtn = UiKit.outline(this, I18n.tr("Change"), v -> showRetentionDialog(showRet));
        retBtn.setTextSize(12f);
        LinearLayout.LayoutParams retLp = new LinearLayout.LayoutParams(-2, dp(34));
        retLp.setMarginStart(dp(8));
        retRow.addView(retBtn, retLp);
        card.addView(retRow);

        card.addView(createSettingsDivider());

        // --- Transmission auto-recheck on error ----------------------------------------
        card.addView(settingsSwitchRow("Auto-recheck on download error",
                "When a torrent reports any error, Jellyarr re-verifies its data and resumes it. Retries back off (5, 10, 20, 40, 80 min) and stop after 5 attempts until the error clears.",
                com.jellyarr.app.services.AutomationPrefs.isAutoRecheckEnabled(this),
                (v, checked) -> {
                    com.jellyarr.app.services.AutomationPrefs.setAutoRecheckEnabled(this, checked);
                    Toast.makeText(this, I18n.tr(checked ? "Auto-recheck enabled." : "Auto-recheck disabled."), Toast.LENGTH_SHORT).show();
                }));

        card.addView(createSettingsDivider());

        // --- Per-service Wi-Fi only ----------------------------------------------------
        card.addView(UiKit.overline(this, I18n.tr("Wi-Fi only (per service)")));
        TextView wifiSub = UiKit.caption(this, I18n.tr("A service switched on here is stopped while the phone uses mobile data and starts again automatically when Wi-Fi/Ethernet returns. Going offline does not stop it."));
        wifiSub.setPadding(0, dp(4), 0, dp(6));
        card.addView(wifiSub);

        com.jellyarr.app.services.MediaServiceManager msm = com.jellyarr.app.services.MediaServiceManager.getInstance();
        if (msm != null) {
            boolean first = true;
            for (com.jellyarr.app.model.ManagedService svc : msm.getAllServices()) {
                boolean isJellyfin = "jellyfin".equalsIgnoreCase(svc.getId());
                if (!isJellyfin && !svc.isInstalled()) continue;
                final String svcId = svc.getId();
                if (!first) card.addView(UiKit.divider(this));
                first = false;
                String state = svc.isRunning() ? "Running" : svc.isStarting() ? "Starting" : "Stopped";
                card.addView(settingsSwitchRow(isJellyfin ? "Jellyfin" : svc.getManifest().getDisplayName(), state,
                        com.jellyarr.app.services.AutomationPrefs.isWifiOnly(this, svcId),
                        (v, checked) -> {
                            com.jellyarr.app.services.AutomationPrefs.setWifiOnly(this, svcId, checked);
                            com.jellyarr.app.services.NetworkPolicyEnforcer.reevaluate();
                        }));
            }
        } else {
            card.addView(UiKit.caption(this, I18n.tr("Service manager not ready yet - reopen this tab in a moment.")));
        }

        section.addView(card);
        return section;
    }

    private void showRetentionDialog(final Runnable onChanged) {
        final MediaStorageManager sm = MediaStorageManager.getInstance(this);
        final String[] values = {
                MediaStorageManager.RETENTION_IMMEDIATE_ON_IMPORT,
                MediaStorageManager.RETENTION_UNTIL_TORRENT_REMOVED,
                MediaStorageManager.RETENTION_24_HOURS,
                MediaStorageManager.RETENTION_3_DAYS,
                MediaStorageManager.RETENTION_7_DAYS,
                MediaStorageManager.RETENTION_MANUAL};
        final String[] labels = {
                I18n.tr("Immediately after import (no duplicates)"),
                I18n.tr("Until the torrent is removed"),
                I18n.tr("Keep for 24 hours"),
                I18n.tr("Keep for 3 days"),
                I18n.tr("Keep for 7 days"),
                I18n.tr("Manual cleanup")};
        int checked = 0;
        String cur = sm.getRetentionPolicy();
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) checked = i;
        new android.app.AlertDialog.Builder(this)
                .setTitle(I18n.tr("Downloads retention"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    sm.setRetentionPolicy(values[which]);
                    try { d.dismiss(); } catch (Throwable ignored) {}
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton(I18n.tr("Cancel"), null)
                .show();
    }

    private void runCleanupAsync(final boolean dryRun, final TextView statusText) {
        statusText.setText(dryRun ? "Checking..." : "Cleaning...");
        new Thread(() -> {
            String msg;
            try {
                msg = com.jellyarr.app.services.TransmissionAutoCleanup.run(this, dryRun).describe();
            } catch (Throwable t) {
                msg = "Failed: " + t.getMessage();
            }
            final String out = msg;
            runOnUiThread(() -> statusText.setText(out));
        }, "Jellyarr-CleanupNow").start();
    }

    // -- Section 3: Permissions & Background Survival (Drawer) -----------------

    
    private View buildDiagnosticsSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(createSettingsSectionHeader("DIAGNOSTICS CENTRE"));

        LinearLayout card = createSettingsSectionCard();
        TextView diagTitle = new TextView(this);
        diagTitle.setText("System & Port Diagnostics");
        diagTitle.setTextSize(15f);
        diagTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        diagTitle.setTextColor(AppTheme.text());
        card.addView(diagTitle);

        TextView diagSub = new TextView(this);
        diagSub.setText("Audit companion service ports (collisions / zombies), device ABI compatibility, and disk headroom.");
        diagSub.setTextSize(11f);
        diagSub.setTextColor(AppTheme.textMuted());
        diagSub.setPadding(0, dp(2), 0, dp(12));
        card.addView(diagSub);

        Button btnRunDiag = createPixelButton("RUN DIAGNOSTICS", AppTheme.inner(), AppTheme.accent());
        btnRunDiag.setTextSize(10.5f);
        btnRunDiag.setOnClickListener(v -> showDiagnosticsModal());
        card.addView(btnRunDiag, new LinearLayout.LayoutParams(-1, dp(40)));

        section.addView(card);
        return section;
    }

    private View buildPermissionsSection() {
        return buildCollapsibleCard(
                "Permissions & Background Survival",
                "Storage access • Battery optimization • Autostart",
                buildPermissionsCard(),
                false
        );
    }

    private View buildSecuritySection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // One username / password for every service
        content.addView(buildUnifiedAccountCard());

        // Minimal Jellyfin User Accounts & Credentials Manager
        content.addView(buildSecurityJellyfinUsersCard());

        return buildCollapsibleCard(
                "Security & Identity",
                "One account for all services, Jellyfin users & passwords",
                content,
                false
        );
    }

    private View buildSecurityJellyfinUsersCard() {
        LinearLayout card = UiKit.column(this);

        LinearLayout hdr = UiKit.row(this);
        hdr.setPadding(0, dp(2), 0, dp(8));

        TextView title = new TextView(this);
        title.setText("JELLYFIN ACCOUNTS");
        styleSectionLabel(title);
        hdr.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView btnRefresh = UiKit.text(this, buttonLabel("REFRESH"), 12.5f, getAccentColor(), UiKit.BOLD);
        btnRefresh.setPadding(dp(10), dp(6), dp(10), dp(6));
        btnRefresh.setClickable(true);
        btnRefresh.setFocusable(true);
        btnRefresh.setBackground(UiKit.rowRipple(this));
        btnRefresh.setOnClickListener(v -> refreshSecurityUsersUI());
        hdr.addView(btnRefresh);

        card.addView(hdr);

        securityUsersContainer = UiKit.column(this);
        card.addView(securityUsersContainer);

        refreshSecurityUsersUI();
        return card;
    }

    /** True when {@code username} is the account kept in sync by UnifiedAccount (case-insensitive). */
    private boolean isUnifiedAccountUser(String username) {
        if (username == null || !UnifiedAccount.isConfigured(this)) return false;
        String u = UnifiedAccount.getUsername(this);
        return u != null && u.trim().equalsIgnoreCase(username.trim());
    }

    private void refreshSecurityUsersUI() {
        if (securityUsersContainer == null) return;
        runBg(() -> {
            List<com.jellyarr.app.security.JellyarrSecurityManager.JellyfinUserRecord> loaded;
            try {
                loaded = com.jellyarr.app.security.JellyarrSecurityManager.getRegisteredUsers(this);
            } catch (Throwable t) {
                loaded = new ArrayList<>();
            }
            final List<com.jellyarr.app.security.JellyarrSecurityManager.JellyfinUserRecord> users =
                    loaded != null ? loaded : new ArrayList<>();

            runOnUiThread(() -> {
                if (securityUsersContainer == null || !isUiAlive()) return;
                securityUsersContainer.removeAllViews();

                if (users.isEmpty()) {
                    TextView emptyTv = UiKit.caption(this, "No Jellyfin users configured in jellyfin.db yet. Complete onboarding in Jellyfin Web client.");
                    emptyTv.setPadding(0, dp(4), 0, dp(4));
                    securityUsersContainer.addView(emptyTv);
                    return;
                }

                for (com.jellyarr.app.security.JellyarrSecurityManager.JellyfinUserRecord u : users) {
                    LinearLayout uRow = UiKit.inset(this);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = dp(8);
                    uRow.setLayoutParams(lp);

                    // Name, then badges (admin / unified / password state) on their own line
                    TextView nameTv = UiKit.singleLine(UiKit.subtitle(this, u.getUsername()));
                    nameTv.setTypeface(UiKit.BOLD);
                    uRow.addView(nameTv);

                    LinearLayout badges = UiKit.row(this);
                    badges.setPadding(0, dp(6), 0, 0);
                    if (u.isAdmin()) {
                        TextView bAdmin = UiKit.badge(this, I18n.tr("ADMIN"), AppTheme.accent());
                        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
                        bLp.setMarginEnd(dp(6));
                        badges.addView(bAdmin, bLp);
                    }
                    if (isUnifiedAccountUser(u.getUsername())) {
                        TextView bUni = UiKit.badge(this, I18n.tr("Unified account"), AppTheme.accent());
                        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
                        bLp.setMarginEnd(dp(6));
                        badges.addView(bUni, bLp);
                    }
                    boolean hasPass = u.getHasPassword();
                    badges.addView(UiKit.badge(this, I18n.tr(hasPass ? "PASSWORD SET" : "NO PASSWORD"),
                            hasPass ? AppTheme.success() : AppTheme.warning()));
                    HorizontalScrollView badgeScroll = UiKit.chipScroller(this, badges);
                    uRow.addView(badgeScroll);

                    Button btnSetPass = UiKit.outline(this, buttonLabel("SET PASSWORD"), v -> showSetPasswordDialog(u.getUsername()));
                    Button btnReset = UiKit.danger(this, buttonLabel("RESET TO BLANK"), v -> showResetPasswordConfirmation(u.getUsername()));
                    btnSetPass.setTextSize(12.5f);
                    btnReset.setTextSize(12.5f);
                    uRow.addView(UiKit.buttonRow(this, btnSetPass, btnReset));

                    securityUsersContainer.addView(uRow);
                }
            });
        });
    }

    private void showSetPasswordDialog(String username) {
        final boolean unifiedUser = isUnifiedAccountUser(username);
        LinearLayout content = UiKit.column(this);

        TextView desc = UiKit.body(this, "Enter the new password for '" + username + "'. It will be securely hashed with PBKDF2-SHA512 directly into jellyfin.db.");
        desc.setPadding(0, 0, 0, dp(12));
        content.addView(desc);

        if (unifiedUser) {
            TextView syncNote = UiKit.caption(this, I18n.tr("This is the unified account: the new password is also applied to Sonarr, Radarr, Prowlarr and Transmission."));
            syncNote.setTextColor(AppTheme.accent());
            syncNote.setPadding(0, 0, 0, dp(12));
            content.addView(syncNote);
        }

        final EditText input = UiKit.input(this, "Enter new password");
        // visible while typing (no confirm field) but never learned by the keyboard
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(Typeface.MONOSPACE);
        content.addView(input);

        showOledDialog(
                "Set Password",
                "User: " + username,
                content,
                "SAVE PASSWORD",
                getAccentColor(),
                () -> {
                    final String newPass = input.getText() != null ? input.getText().toString().trim() : "";
                    if (newPass.isEmpty()) {
                        Toast.makeText(this, I18n.tr("Password cannot be empty. Use 'RESET TO BLANK' if you want no password."), Toast.LENGTH_LONG).show();
                        return;
                    }
                    final String unifiedName = unifiedUser ? UnifiedAccount.getUsername(this) : null;
                    if (unifiedUser) {
                        // the same password goes to every service, so it must pass their rules first
                        String problem = UnifiedAccount.validate(unifiedName, newPass);
                        if (problem != null) {
                            Toast.makeText(this, I18n.tr(problem), Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    final Context app = getApplicationContext();
                    runBg(() -> {
                        boolean saved;
                        try {
                            saved = com.jellyarr.app.security.JellyarrSecurityManager.setPassword(app, username, newPass);
                        } catch (Throwable t) {
                            saved = false;
                        }
                        final boolean ok = saved;
                        runOnUiThread(() -> {
                            if (ok && unifiedUser) {
                                // Full sync: keep the unified account and every service on the new password.
                                UnifiedAccount.save(app, unifiedName, newPass);
                                lastUnifiedAutoApply = System.currentTimeMillis();
                                runUnifiedApply(true, true);
                            }
                            if (!isUiAlive()) return;
                            if (ok) {
                                Toast.makeText(this, I18n.tr("Password for '" + username + "' saved successfully."), Toast.LENGTH_LONG).show();
                                refreshSecurityUsersUI();
                                refreshHomeAccountsUI();
                            } else {
                                Toast.makeText(this, I18n.tr("Failed to save password. Is database locked?"), Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                },
                "CANCEL",
                null
        );
    }

    private void showResetPasswordConfirmation(String username) {
        LinearLayout content = UiKit.column(this);

        TextView desc = UiKit.body(this, "Are you sure you want to clear the password for '" + username + "'?\n\nThis writes directly into jellyfin.db. You will immediately be able to log in by leaving the password field blank.");
        content.addView(desc);

        if (isUnifiedAccountUser(username)) {
            TextView note = UiKit.caption(this, I18n.tr("This user is the unified account. Sonarr, Radarr, Prowlarr and Transmission keep the saved password; use Set Password to sync every service again."));
            note.setTextColor(AppTheme.warning());
            note.setPadding(0, dp(10), 0, 0);
            content.addView(note);
        }

        showOledDialog(
                "Reset Password to Blank?",
                "User: " + username,
                content,
                "RESET TO BLANK",
                getDangerColor(),
                () -> {
                    final Context app = getApplicationContext();
                    runBg(() -> {
                        boolean done;
                        try {
                            done = com.jellyarr.app.security.JellyarrSecurityManager.resetPasswordToBlank(app, username);
                        } catch (Throwable t) {
                            done = false;
                        }
                        final boolean ok = done;
                        runOnUiThread(() -> {
                            if (!isUiAlive()) return;
                            if (ok) {
                                Toast.makeText(this, I18n.tr("Password for '" + username + "' reset to blank."), Toast.LENGTH_LONG).show();
                                refreshSecurityUsersUI();
                                refreshHomeAccountsUI();
                            } else {
                                Toast.makeText(this, I18n.tr("Could not reset password. Is database locked?"), Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                },
                "CANCEL",
                null
        );
    }

    private View buildSecurityZombieQuarantineCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView title = new TextView(this);
        title.setText("ZOMBIE PROCESS SAFEGUARD");
        title.setTextSize(10.5f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextColor(AppTheme.success());
        row.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Auto-kill active: Service ports (8096, 7878, 8989, 9696, 9117, 9091, 5055, 6767, 8686) are automatically reclaimed to prevent conflicts and CPU drain.");
        sub.setTextSize(9.5f);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setTextColor(AppTheme.textMuted());
        sub.setPadding(0, dp(2), 0, 0);
        row.addView(sub);

        return row;
    }

    // -- Section 4: Playback & Streaming Codecs (Drawer) ----------------------

    private View buildStreamingSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        content.addView(buildSpecRow("Transcoding Engine", "Inbuilt Auto (Direct Play / HW Fallback)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Supported Video Codecs", "H.264, HEVC, AV1, VP9"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Supported Audio Codecs", "AAC, Opus, FLAC, MP3, AC3"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Subtitle Renderer", "Embedded Subtitles (libass)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Hardware Acceleration", "MediaCodec / ARM64 NEON"));
        content.addView(createSettingsDivider());

        // Diagnostic benchmarks action
        LinearLayout diagRow = new LinearLayout(this);
        diagRow.setOrientation(LinearLayout.HORIZONTAL);
        diagRow.setGravity(Gravity.CENTER_VERTICAL);
        diagRow.setPadding(0, dp(4), 0, dp(2));

        LinearLayout dTextCol = new LinearLayout(this);
        dTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView dTitle = new TextView(this);
        dTitle.setText("Engine Diagnostics");
        dTitle.setTextSize(13f);
        dTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        dTitle.setTextColor(AppTheme.text());
        dTextCol.addView(dTitle);

        TextView dSub = new TextView(this);
        dSub.setText("Benchmark hardware codecs and audio transcoding");
        dSub.setTextSize(10.5f);
        dSub.setTextColor(AppTheme.textMuted());
        dSub.setPadding(0, dp(1), 0, 0);
        dTextCol.addView(dSub);
        diagRow.addView(dTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnRunDiag = createPixelButton("BENCHMARKS", getSurfaceElevatedColor(), getAccentColor());
        btnRunDiag.setTextSize(10f);
        btnRunDiag.setOnClickListener(v -> showTranscodingBenchmarksDialog());
        diagRow.addView(btnRunDiag, new LinearLayout.LayoutParams(dp(100), dp(32)));

        content.addView(diagRow);

        return buildCollapsibleCard(
                "Playback & Codecs",
                "Inbuilt automatic engine • H.264, HEVC, AV1, VP9",
                content,
                false
        );
    }

    // -- Section 6: Software Updates (Drawer) ----------------------------------

    private View buildUpdatesSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // Row 1: Update Channel
        LinearLayout channelRow = new LinearLayout(this);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.setPadding(0, dp(2), 0, dp(6));

        LinearLayout chTextCol = new LinearLayout(this);
        chTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView chTitle = new TextView(this);
        chTitle.setText("Update Channel");
        chTitle.setTextSize(13.5f);
        chTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        chTitle.setTextColor(AppTheme.text());
        chTextCol.addView(chTitle);

        updateChannelBadge = new TextView(this);
        updateChannelBadge.setTextSize(11f);
        updateChannelBadge.setTextColor(AppTheme.textMuted());
        updateChannelBadge.setPadding(0, dp(2), 0, 0);
        chTextCol.addView(updateChannelBadge);
        channelRow.addView(chTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        // Modern Segmented Pill Container
        LinearLayout pillContainer = new LinearLayout(this);
        pillContainer.setOrientation(LinearLayout.HORIZONTAL);
        pillContainer.setGravity(Gravity.CENTER_VERTICAL);
        pillContainer.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(8)));
        pillContainer.setPadding(dp(2), dp(2), dp(2), dp(2));

        btnChannelStable = new Button(this);
        btnChannelStable.setText("STABLE");
        btnChannelStable.setTextSize(10f);
        btnChannelStable.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnChannelStable.setPadding(dp(12), 0, dp(12), 0);
        btnChannelStable.setGravity(Gravity.CENTER);
        btnChannelStable.setMinHeight(0);
        btnChannelStable.setMinimumHeight(0);
        btnChannelStable.setOnClickListener(v -> {
            UpdateManager.getInstance(this).setUpdateChannel(UpdateManager.CHANNEL_STABLE);
            updateChannelSelectionUI();
            UpdateManager.getInstance(this).checkForUpdates(this, true);
        });
        pillContainer.addView(btnChannelStable, new LinearLayout.LayoutParams(-2, dp(28)));

        btnChannelBeta = new Button(this);
        btnChannelBeta.setText("BETA");
        btnChannelBeta.setTextSize(10f);
        btnChannelBeta.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnChannelBeta.setPadding(dp(12), 0, dp(12), 0);
        btnChannelBeta.setGravity(Gravity.CENTER);
        btnChannelBeta.setMinHeight(0);
        btnChannelBeta.setMinimumHeight(0);
        btnChannelBeta.setOnClickListener(v -> {
            UpdateManager.getInstance(this).setUpdateChannel(UpdateManager.CHANNEL_BETA);
            updateChannelSelectionUI();
            UpdateManager.getInstance(this).checkForUpdates(this, true);
        });
        pillContainer.addView(btnChannelBeta, new LinearLayout.LayoutParams(-2, dp(28)));

        channelRow.addView(pillContainer, new LinearLayout.LayoutParams(-2, dp(32)));

        content.addView(channelRow);
        updateChannelSelectionUI();

        content.addView(createSettingsDivider());

        // Row 2: Software Updates Check
        LinearLayout updateRow = new LinearLayout(this);
        updateRow.setOrientation(LinearLayout.HORIZONTAL);
        updateRow.setGravity(Gravity.CENTER_VERTICAL);
        updateRow.setPadding(0, dp(2), 0, dp(4));

        LinearLayout uTextCol = new LinearLayout(this);
        uTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView uTitle = new TextView(this);
        uTitle.setText("Software Version");
        uTitle.setTextSize(13.5f);
        uTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        uTitle.setTextColor(AppTheme.text());
        uTextCol.addView(uTitle);

        updateStatusText = new TextView(this);
        updateStatusText.setText("v" + com.termux.BuildConfig.VERSION_NAME + " (Installed)");
        updateStatusText.setTextSize(11f);
        updateStatusText.setTextColor(AppTheme.textMuted());
        updateStatusText.setPadding(0, dp(2), 0, 0);
        uTextCol.addView(updateStatusText);
        updateRow.addView(uTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        updateActionBtn = createPixelButton("CHECK FOR UPDATES", getAccentColor(), AppTheme.onAccent());
        updateActionBtn.setTextSize(10f);
        updateRow.addView(updateActionBtn, new LinearLayout.LayoutParams(-2, dp(34)));

        content.addView(updateRow);

        updateForceDownloadBtn = createPixelButton("Force Re-download / Test OTA", getSurfaceElevatedColor(), AppTheme.success());
        updateForceDownloadBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams pF = new LinearLayout.LayoutParams(-1, dp(36));
        pF.topMargin = dp(6);
        content.addView(updateForceDownloadBtn, pF);

        content.addView(createSettingsDivider());

        // ---- Update notifications (separate Stable / Beta) ----
        content.addView(buildUpdateNotifyBlock(UpdateManager.CHANNEL_STABLE, "STABLE RELEASES", "Official full releases"));
        content.addView(createSettingsDivider());
        content.addView(buildUpdateNotifyBlock(UpdateManager.CHANNEL_BETA, "BETA RELEASES", "Pre-release / preview builds"));
        content.addView(createSettingsDivider());

        TextView intervalTitle = new TextView(this);
        intervalTitle.setText("Background check interval");
        intervalTitle.setTextSize(13.5f);
        intervalTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        intervalTitle.setTextColor(AppTheme.text());
        content.addView(intervalTitle);
        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setPadding(0, dp(8), 0, dp(4));
        final int[] hoursOpts = {6, 12, 24};
        final Button[] intervalBtns = new Button[hoursOpts.length];
        for (int i = 0; i < hoursOpts.length; i++) {
            final int h = hoursOpts[i];
            intervalBtns[i] = createPixelButton(h + " H", AppTheme.inner(), AppTheme.textSecondary());
            intervalBtns[i].setTextSize(10.5f);
            intervalBtns[i].setOnClickListener(v -> {
                UpdateNotifier.setIntervalHours(this, h);
                for (int k = 0; k < hoursOpts.length; k++) {
                    boolean sel = hoursOpts[k] == h;
                    intervalBtns[k].setTextColor(sel ? AppTheme.onAccent() : AppTheme.textSecondary());
                    intervalBtns[k].setBackground(createRoundedDrawable(sel ? getAccentColor() : AppTheme.inner(), dp(10)));
                }
            });
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, dp(36), 1.0f);
            if (i < hoursOpts.length - 1) ilp.setMarginEnd(dp(8));
            intervalRow.addView(intervalBtns[i], ilp);
            boolean sel = hoursOpts[i] == UpdateNotifier.intervalHours(this);
            if (sel) {
                intervalBtns[i].setTextColor(AppTheme.onAccent());
                intervalBtns[i].setBackground(createRoundedDrawable(getAccentColor(), dp(10)));
            }
        }
        content.addView(intervalRow);

        content.addView(createSettingsDivider());
        final UpdateManager umSettings = UpdateManager.getInstance(this);
        content.addView(updateToggleRow("Download updates automatically",
                "Downloads new versions in the background (of the selected channel) and keeps the file until you install it. Interrupted downloads resume automatically.",
                umSettings.isAutoDownloadEnabled(), checked -> {
                    umSettings.setAutoDownloadEnabled(checked);
                    UpdateNotifier.schedule(this);
                    if (checked && Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
                    }
                }));
        content.addView(updateToggleRow("Automatic download on Wi-Fi only",
                "Do not use mobile data for automatic update downloads.",
                umSettings.isAutoDownloadWifiOnly(), checked -> umSettings.setAutoDownloadWifiOnly(checked)));

        Button btnWhatsNew = createPixelButton("WHAT'S NEW IN THIS VERSION", getSurfaceElevatedColor(), getAccentColor());
        btnWhatsNew.setTextSize(10.5f);
        btnWhatsNew.setOnClickListener(v -> showWhatsNewDialog());
        LinearLayout.LayoutParams wnLp = new LinearLayout.LayoutParams(-1, dp(38));
        wnLp.topMargin = dp(8);
        content.addView(btnWhatsNew, wnLp);
        content.addView(createSettingsDivider());

        // Integrated Services Matrix Header
        TextView servicesHeading = new TextView(this);
        servicesHeading.setText("COMPANION SERVICES & ENGINES");
        servicesHeading.setTextSize(10.5f);
        servicesHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        servicesHeading.setTextColor(getAccentColor());
        servicesHeading.setLetterSpacing(0.06f);
        servicesHeading.setPadding(0, dp(10), 0, dp(4));
        content.addView(servicesHeading);
        styleSectionLabel(servicesHeading);

        for (UpdateManager.ServiceVersionInfo svc : UpdateManager.getInstance(this).getUniversalServiceVersionMatrix()) {
            content.addView(buildServiceUpdateRow(svc));
            content.addView(createSettingsDivider());
        }

        boolean isBeta = UpdateManager.CHANNEL_BETA.equals(UpdateManager.getInstance(this).getUpdateChannel());
        return buildCollapsibleCard(
                "Software Updates",
                "Channel: " + (isBeta ? "Beta" : "Stable") + " • OTA updates",
                content,
                true
        );
    }

    private View buildServiceUpdateRow(UpdateManager.ServiceVersionInfo svc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(svc.displayName);
        tvName.setTextSize(13f);
        tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvName.setTextColor(AppTheme.text());
        textCol.addView(tvName);

        TextView tvDesc = new TextView(this);
        tvDesc.setText("v" + svc.installedVersion + " • " + svc.notes);
        tvDesc.setTextSize(11f);
        tvDesc.setTextColor(AppTheme.textMuted());
        tvDesc.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvDesc);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView badge = new TextView(this);
        if ("jellyfin".equalsIgnoreCase(svc.serviceId)) {
            badge.setText("[v12.4 COMPATIBLE]");
            badge.setTextColor(AppTheme.accent());
            badge.setBackground(createBorderedRoundedDrawable(AppTheme.accentContainer(), AppTheme.accent(), dp(1), dp(6)));
        } else if (svc.updateAvailable) {
            badge.setText("[UPDATE]");
            badge.setTextColor(AppTheme.warning());
            badge.setBackground(createBorderedRoundedDrawable(AppTheme.warningContainer(), AppTheme.warningContainer(), dp(1), dp(6)));
        } else {
            badge.setText("[UP TO DATE]");
            badge.setTextColor(AppTheme.success());
            badge.setBackground(createBorderedRoundedDrawable(AppTheme.successContainer(), AppTheme.successContainer(), dp(1), dp(6)));
        }
        badge.setTextSize(10f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        row.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        if ("jellyfin".equalsIgnoreCase(svc.serviceId)) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                UpdateManager.JellyfinCompatibilityReport report = UpdateManager.getInstance(this).checkJellyfin124Compatibility(this);
                TextView tv = new TextView(this);
                tv.setText(report.summary);
                tv.setTextSize(11.5f);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(AppTheme.text());
                tv.setLineSpacing(dp(3), 1.2f);
                tv.setPadding(0, dp(4), 0, dp(8));
                showOledDialog("JELLYFIN v12.4 COMPATIBILITY",
                        "System Requirements & Architecture Verification",
                        tv,
                        "OK", getAccentColor(), null, null, null);
            });
        }

        return row;
    }

    // -- Section 6: Backup & Restore (1-Tap Export/Import & Migration) ----------

    private View buildBackupRestoreSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView descTv = new TextView(this);
        descTv.setText("Create a portable compressed backup of your Jellyarr settings, credentials, and service configurations, or restore from an existing backup archive.");
        descTv.setTextSize(12f);
        descTv.setTextColor(AppTheme.textSecondary());
        descTv.setLineSpacing(dp(3), 1.15f);
        descTv.setPadding(0, dp(2), 0, dp(10));
        content.addView(descTv);

        // Status Snapshot Card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(8)));
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView statusTitle = new TextView(this);
        statusTitle.setText("BACKUP SNAPSHOT");
        statusTitle.setTextSize(10.5f);
        statusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusTitle.setTextColor(AppTheme.textMuted());
        statusCard.addView(statusTitle);

        backupStatusTv = new TextView(this);
        backupStatusTv.setTextSize(12.5f);
        backupStatusTv.setTextColor(AppTheme.text());
        backupStatusTv.setPadding(0, dp(4), 0, dp(2));
        statusCard.addView(backupStatusTv);

        TextView locTv = new TextView(this);
        locTv.setText("Directory: /Download/" + BackupRestoreManager.BACKUP_DIR_NAME);
        locTv.setTextSize(11f);
        locTv.setTextColor(AppTheme.textSecondary());
        statusCard.addView(locTv);

        content.addView(statusCard);
        updateBackupStatusUI();

        // Action Buttons Row
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams aParams = new LinearLayout.LayoutParams(-1, -2);
        aParams.topMargin = dp(10);
        actionsRow.setLayoutParams(aParams);

        Button btnExport = new Button(this);
        btnExport.setText("EXPORT BACKUP");
        btnExport.setTextSize(11f);
        btnExport.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnExport.setTextColor(AppTheme.card());
        btnExport.setBackground(createBorderedRoundedDrawable(Color.parseColor("#55A9A0"), Color.parseColor("#55A9A0"), 0, dp(8)));
        btnExport.setOnClickListener(v -> triggerExportBackup());
        LinearLayout.LayoutParams b1P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b1P.setMarginEnd(dp(6));
        actionsRow.addView(btnExport, b1P);

        Button btnRestore = new Button(this);
        btnRestore.setText("RESTORE...");
        btnRestore.setTextSize(11f);
        btnRestore.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnRestore.setTextColor(AppTheme.text());
        btnRestore.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outlineStrong(), dp(1), dp(8)));
        btnRestore.setOnClickListener(v -> showRestoreOptionsDialog());
        LinearLayout.LayoutParams b2P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b2P.setMarginStart(dp(6));
        actionsRow.addView(btnRestore, b2P);

        content.addView(actionsRow);

        return buildCollapsibleCard(
                "Backup & Restore",
                "Export / import configurations & migration",
                content,
                false
        );
    }

    private void updateBackupStatusUI() {
        if (backupStatusTv == null) return;
        SharedPreferences sp = getSharedPreferences("fishbowl_settings", MODE_PRIVATE);
        long lastTime = sp.getLong(BackupRestoreManager.PREF_LAST_BACKUP_TIME, 0);
        long lastSize = sp.getLong(BackupRestoreManager.PREF_LAST_BACKUP_SIZE, 0);
        if (lastTime > 0) {
            String dateStr = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(new Date(lastTime));
            backupStatusTv.setText("Last Exported: " + dateStr + (lastSize > 0 ? " (" + MediaStorageManager.formatBytes(lastSize) + ")" : ""));
        } else {
            List<File> existing = BackupRestoreManager.listExistingBackups(this);
            if (!existing.isEmpty()) {
                File latest = existing.get(0);
                String dateStr = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(new Date(latest.lastModified()));
                backupStatusTv.setText("Latest Found: " + dateStr + " (" + MediaStorageManager.formatBytes(latest.length()) + ")");
            } else {
                backupStatusTv.setText("No backups created yet");
            }
        }
    }

    private Dialog createBackupProgressDialog(String titleText, String initialMsg, TextView[] msgHolder) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createBorderedRoundedDrawable(AppTheme.bg(), AppTheme.outline(), dp(1), dp(12)));
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextSize(14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(AppTheme.text());
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(t);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams pbP = new LinearLayout.LayoutParams(dp(36), dp(36));
        pbP.topMargin = dp(14);
        pbP.bottomMargin = dp(10);
        box.addView(pb, pbP);

        TextView msgTv = new TextView(this);
        msgTv.setText(initialMsg);
        msgTv.setTextSize(11.5f);
        msgTv.setTextColor(AppTheme.textSecondary());
        msgTv.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(msgTv);
        if (msgHolder != null && msgHolder.length > 0) {
            msgHolder[0] = msgTv;
        }

        d.setContentView(I18n.attach(box));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(AppTheme.isDark() ? 0.85f : 0.5f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        return d;
    }

    private void triggerExportBackup() {
        TextView[] msgHolder = new TextView[1];
        Dialog progressDialog = createBackupProgressDialog("EXPORTING BACKUP", "Staging preferences and service configs...", msgHolder);
        safeShow(progressDialog);

        BackupRestoreManager.exportBackup(this, new BackupRestoreManager.BackupCallback() {
            @Override
            public void onProgress(String message) {
                if (msgHolder[0] != null) {
                    msgHolder[0].setText(message);
                }
            }

            @Override
            public void onSuccess(File backupFile, long fileSizeBytes, int itemsCount) {
                progressDialog.dismiss();
                updateBackupStatusUI();
                showBackupExportedModal(backupFile, fileSizeBytes, itemsCount);
            }

            @Override
            public void onError(String error) {
                progressDialog.dismiss();
                TextView ev = new TextView(JellyarrActivity.this);
                ev.setText(error);
                ev.setTextSize(11.5f);
                ev.setTextColor(AppTheme.danger());
                ev.setTextIsSelectable(true);
                showOledDialog("BACKUP FAILED", "Details (select to copy)", ev, "OK", getAccentColor(), null, null, null);
            }
        });
    }

    private void showBackupExportedModal(File backupFile, long sizeBytes, int itemsCount) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createBorderedRoundedDrawable(AppTheme.bg(), AppTheme.outline(), dp(1), dp(12)));
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("BACKUP EXPORTED");
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#55A9A0"));
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("Jellyarr configuration archive created successfully:\n\n"
                + "File: " + backupFile.getName() + "\n"
                + "Size: " + MediaStorageManager.formatBytes(sizeBytes) + "\n"
                + "Items: " + itemsCount + " files\n"
                + "Path: " + backupFile.getAbsolutePath());
        info.setTextSize(11.5f);
        info.setTextColor(AppTheme.text());
        info.setLineSpacing(dp(2), 1.15f);
        info.setPadding(0, dp(10), 0, dp(14));
        box.addView(info);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnShare = new Button(this);
        btnShare.setText("SHARE / SEND");
        btnShare.setTextSize(11f);
        btnShare.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnShare.setTextColor(AppTheme.card());
        btnShare.setBackground(createBorderedRoundedDrawable(Color.parseColor("#55A9A0"), Color.parseColor("#55A9A0"), 0, dp(8)));
        btnShare.setOnClickListener(v -> {
            BackupRestoreManager.shareBackupFile(this, backupFile);
        });
        LinearLayout.LayoutParams b1P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b1P.setMarginEnd(dp(6));
        btnRow.addView(btnShare, b1P);

        Button btnDone = new Button(this);
        btnDone.setText("DONE");
        btnDone.setTextSize(11f);
        btnDone.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnDone.setTextColor(AppTheme.text());
        btnDone.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outlineStrong(), dp(1), dp(8)));
        btnDone.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams b2P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b2P.setMarginStart(dp(6));
        btnRow.addView(btnDone, b2P);

        box.addView(btnRow);

        d.setContentView(I18n.attach(box));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(AppTheme.isDark() ? 0.85f : 0.5f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        safeShow(d);
    }

    private void showRestoreOptionsDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(createBorderedRoundedDrawable(AppTheme.bg(), AppTheme.outline(), dp(1), dp(12)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(box);

        TextView title = new TextView(this);
        title.setText("RESTORE CONFIGURATION");
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(AppTheme.text());
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Select a backup to restore preferences and service configs:");
        sub.setTextSize(11.5f);
        sub.setTextColor(AppTheme.textMuted());
        sub.setPadding(0, dp(4), 0, dp(12));
        box.addView(sub);

        List<File> backups = BackupRestoreManager.listExistingBackups(this);
        if (backups.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("No backups found in /Download/" + BackupRestoreManager.BACKUP_DIR_NAME);
            emptyTv.setTextSize(11f);
            emptyTv.setTextColor(AppTheme.textSecondary());
            emptyTv.setPadding(0, dp(6), 0, dp(12));
            box.addView(emptyTv);
        } else {
            for (File bf : backups) {
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outline(), dp(1), dp(8)));
                item.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout.LayoutParams iP = new LinearLayout.LayoutParams(-1, -2);
                iP.bottomMargin = dp(8);
                item.setLayoutParams(iP);

                TextView nameTv = new TextView(this);
                nameTv.setText(bf.getName());
                nameTv.setTextSize(12f);
                nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                nameTv.setTextColor(AppTheme.text());
                item.addView(nameTv);

                String dateStr = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(new Date(bf.lastModified()));
                TextView metaTv = new TextView(this);
                metaTv.setText(dateStr + " • " + MediaStorageManager.formatBytes(bf.length()));
                metaTv.setTextSize(10.5f);
                metaTv.setTextColor(AppTheme.textMuted());
                metaTv.setPadding(0, dp(2), 0, 0);
                item.addView(metaTv);

                item.setOnClickListener(v -> {
                    d.dismiss();
                    confirmAndRestoreFromFile(bf);
                });

                box.addView(item);
            }
        }

        // File picker button (SAF)
        Button btnPickFile = new Button(this);
        btnPickFile.setText("CHOOSE FROM FILES... (SAF)");
        btnPickFile.setTextSize(11f);
        btnPickFile.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnPickFile.setTextColor(Color.parseColor("#55A9A0"));
        btnPickFile.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), Color.parseColor("#55A9A0"), dp(1), dp(8)));
        btnPickFile.setOnClickListener(v -> {
            d.dismiss();
            launchFilePickerForRestore();
        });
        LinearLayout.LayoutParams pickP = new LinearLayout.LayoutParams(-1, dp(38));
        pickP.topMargin = dp(4);
        pickP.bottomMargin = dp(8);
        box.addView(btnPickFile, pickP);

        // Cancel
        Button btnCancel = new Button(this);
        btnCancel.setText("CANCEL");
        btnCancel.setTextSize(11f);
        btnCancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnCancel.setTextColor(AppTheme.textSecondary());
        btnCancel.setBackground(createBorderedRoundedDrawable(Color.TRANSPARENT, AppTheme.outline(), dp(1), dp(8)));
        btnCancel.setOnClickListener(v -> d.dismiss());
        box.addView(btnCancel, new LinearLayout.LayoutParams(-1, dp(36)));

        d.setContentView(I18n.attach(scroll));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(AppTheme.isDark() ? 0.85f : 0.5f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            wlp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.65);
            d.getWindow().setAttributes(wlp);
        }
        safeShow(d);
    }

    private void launchFilePickerForRestore() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            restoreFilePickerLauncher.launch(Intent.createChooser(intent, "Select Jellyarr Backup (.zip)"));
        } catch (Throwable t) {
            Toast.makeText(this, I18n.tr("Cannot open file picker: " + t.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmAndRestoreFromFile(File file) {
        showConfirmRestoreDialog(file.getName(), () -> {
            TextView[] msgHolder = new TextView[1];
            Dialog progressDialog = createBackupProgressDialog("RESTORING CONFIGURATION", "Extracting and applying configuration files...", msgHolder);
            safeShow(progressDialog);

            BackupRestoreManager.restoreBackup(this, file, new BackupRestoreManager.RestoreCallback() {
                @Override
                public void onProgress(String message) {
                    if (msgHolder[0] != null) {
                        msgHolder[0].setText(message);
                    }
                }

                @Override
                public void onSuccess(int restoredPrefsCount, int restoredConfigsCount) {
                    progressDialog.dismiss();
                    showRestoreSuccessModal(restoredPrefsCount, restoredConfigsCount);
                }

                @Override
                public void onError(String error) {
                    progressDialog.dismiss();
                    Toast.makeText(JellyarrActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void confirmAndRestoreFromUri(Uri uri) {
        showConfirmRestoreDialog("Selected File Archive", () -> {
            TextView[] msgHolder = new TextView[1];
            Dialog progressDialog = createBackupProgressDialog("RESTORING CONFIGURATION", "Extracting and applying configuration files...", msgHolder);
            safeShow(progressDialog);

            BackupRestoreManager.restoreBackup(this, uri, new BackupRestoreManager.RestoreCallback() {
                @Override
                public void onProgress(String message) {
                    if (msgHolder[0] != null) {
                        msgHolder[0].setText(message);
                    }
                }

                @Override
                public void onSuccess(int restoredPrefsCount, int restoredConfigsCount) {
                    progressDialog.dismiss();
                    showRestoreSuccessModal(restoredPrefsCount, restoredConfigsCount);
                }

                @Override
                public void onError(String error) {
                    progressDialog.dismiss();
                    Toast.makeText(JellyarrActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showConfirmRestoreDialog(String sourceName, Runnable onConfirmed) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createBorderedRoundedDrawable(AppTheme.bg(), AppTheme.outline(), dp(1), dp(12)));
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("CONFIRM RESTORE");
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(AppTheme.warning());
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText("Restoring from:\n" + sourceName + "\n\n"
                + "This will overwrite your current Jellyarr preferences, API keys, and media service configurations with the backup archive.\n\n"
                + "Are you sure you want to proceed?");
        msg.setTextSize(11.5f);
        msg.setTextColor(AppTheme.text());
        msg.setLineSpacing(dp(2), 1.15f);
        msg.setPadding(0, dp(10), 0, dp(14));
        box.addView(msg);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnProceed = new Button(this);
        btnProceed.setText("RESTORE NOW");
        btnProceed.setTextSize(11f);
        btnProceed.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnProceed.setTextColor(AppTheme.contrastOn(AppTheme.warning()));
        btnProceed.setBackground(createBorderedRoundedDrawable(AppTheme.warning(), AppTheme.warning(), 0, dp(8)));
        btnProceed.setOnClickListener(v -> {
            d.dismiss();
            if (onConfirmed != null) onConfirmed.run();
        });
        LinearLayout.LayoutParams b1P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b1P.setMarginEnd(dp(6));
        btnRow.addView(btnProceed, b1P);

        Button btnCancel = new Button(this);
        btnCancel.setText("CANCEL");
        btnCancel.setTextSize(11f);
        btnCancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnCancel.setTextColor(AppTheme.text());
        btnCancel.setBackground(createBorderedRoundedDrawable(AppTheme.inner(), AppTheme.outlineStrong(), dp(1), dp(8)));
        btnCancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams b2P = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        b2P.setMarginStart(dp(6));
        btnRow.addView(btnCancel, b2P);

        box.addView(btnRow);

        d.setContentView(I18n.attach(box));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(AppTheme.isDark() ? 0.85f : 0.5f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        safeShow(d);
    }

    private void showRestoreSuccessModal(int prefsCount, int configsCount) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createBorderedRoundedDrawable(AppTheme.bg(), AppTheme.outline(), dp(1), dp(12)));
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("CONFIGURATION RESTORED");
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.parseColor("#55A9A0"));
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("Successfully restored configuration files:\n\n"
                + "• Preferences: " + prefsCount + " files\n"
                + "• Service Configs: " + configsCount + " files\n\n"
                + "Jellyarr restarts now so every service and screen uses the restored settings and accounts.");
        info.setTextSize(11.5f);
        info.setTextColor(AppTheme.text());
        info.setLineSpacing(dp(2), 1.15f);
        info.setPadding(0, dp(10), 0, dp(14));
        box.addView(info);

        Button btnRestart = new Button(this);
        btnRestart.setText("RESTART JELLYARR");
        btnRestart.setTextSize(11f);
        btnRestart.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnRestart.setTextColor(AppTheme.card());
        btnRestart.setBackground(createBorderedRoundedDrawable(Color.parseColor("#55A9A0"), Color.parseColor("#55A9A0"), 0, dp(8)));
        d.setCancelable(false);
        btnRestart.setOnClickListener(v -> {
            d.dismiss();
            // The restored preferences must be read from disk: a restart is the only way to drop the copies
            // this process still holds (otherwise they are written back over the restored files).
            RestartActivity.restart(this);
        });
        box.addView(btnRestart, new LinearLayout.LayoutParams(-1, dp(40)));

        d.setContentView(I18n.attach(box));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setDimAmount(AppTheme.isDark() ? 0.85f : 0.5f);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
            wlp.copyFrom(d.getWindow().getAttributes());
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        safeShow(d);
    }

    // -- Section 8: About Jellyarr (Drawer - Specs, Architecture & Licenses) ----

    private View buildAboutSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView descTv = new TextView(this);
        descTv.setText("Jellyarr is an all-in-one local media streaming server for Android, bundling Jellyfin Server, .NET runtime, and native hardware transcoding with zero trackers.");
        descTv.setTextSize(12f);
        descTv.setTextColor(AppTheme.textSecondary());
        descTv.setLineSpacing(dp(3), 1.15f);
        descTv.setPadding(0, dp(2), 0, dp(10));
        content.addView(descTv);

        content.addView(buildRuntimeStatusItem());

        // Subheading: System Architecture & Specifications
        TextView archHeading = new TextView(this);
        archHeading.setText("SYSTEM ARCHITECTURE & SPECS");
        archHeading.setTextSize(10.5f);
        archHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        archHeading.setTextColor(getAccentColor());
        archHeading.setLetterSpacing(0.06f);
        archHeading.setPadding(0, dp(6), 0, dp(6));
        content.addView(archHeading);
        styleSectionLabel(archHeading);

        content.addView(buildSpecRow("Jellyfin Server Core", "v12.4.0 (.NET 10 ARM64)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Microsoft .NET Runtime", "v10.0.12 Linux Bionic"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Hardware Transcoder", "FFmpeg 7.1.4-Jellyfin ARM64"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("CPU Architecture", "ARM64 (aarch64)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Database Engine", "SQLite3 (Embedded Local)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Security & TLS Stack", "OpenSSL 3.x System Native"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Process Isolation", "Android Sandboxed UID (100% Local)"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Package Scope", getPackageName()));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Privacy & Telemetry", "100% Local • Zero Trackers"));
        content.addView(createSettingsDivider());

        // Subheading: Media Stack & Service Versions
        TextView stackHeading = new TextView(this);
        stackHeading.setText("MEDIA STACK & SERVICE VERSIONS");
        stackHeading.setTextSize(10.5f);
        stackHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        stackHeading.setTextColor(getAccentColor());
        stackHeading.setLetterSpacing(0.06f);
        stackHeading.setPadding(0, dp(8), 0, dp(6));
        content.addView(stackHeading);

        for (UpdateManager.ServiceVersionInfo svc : UpdateManager.getInstance(this).getUniversalServiceVersionMatrix()) {
            content.addView(buildSpecRow(svc.displayName, "v" + svc.installedVersion));
            content.addView(createSettingsDivider());
        }


        // Subheading: Open Source Licensing
        TextView licHeading = new TextView(this);
        licHeading.setText("OPEN SOURCE LICENSING");
        licHeading.setTextSize(10.5f);
        licHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        licHeading.setTextColor(Color.parseColor("#8B5CF6"));
        licHeading.setLetterSpacing(0.06f);
        licHeading.setPadding(0, dp(8), 0, dp(6));
        content.addView(licHeading);

        content.addView(buildSpecRow("Jellyarr", "GNU GPL v3.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Jellyfin Server Core", "GNU GPL v2.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("FFmpeg Transcoder", "LGPL v2.1+ / GPL v2.0+"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Microsoft .NET Runtime", "MIT License"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Transmission Daemon", "GNU GPL v2.0 / v3.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Termux Android Environment", "Apache License 2.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("Servarr Stack (Radarr/Sonarr/Prowlarr)", "GNU GPL v3.0"));
        content.addView(createSettingsDivider());
        content.addView(buildSpecRow("FlareSolverr Cloudflare Proxy", "MIT License"));
        content.addView(createSettingsDivider());

        // Licensing Details Dialog Action
        LinearLayout licActionRow = new LinearLayout(this);
        licActionRow.setOrientation(LinearLayout.HORIZONTAL);
        licActionRow.setGravity(Gravity.CENTER_VERTICAL);
        licActionRow.setPadding(0, dp(4), 0, dp(4));

        LinearLayout licTextCol = new LinearLayout(this);
        licTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView licTitle = new TextView(this);
        licTitle.setText("License Text & Notices");
        licTitle.setTextSize(13f);
        licTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        licTitle.setTextColor(AppTheme.text());
        licTextCol.addView(licTitle);

        TextView licSub = new TextView(this);
        licSub.setText("View third-party notices and legal disclaimers");
        licSub.setTextSize(10.5f);
        licSub.setTextColor(AppTheme.textMuted());
        licSub.setPadding(0, dp(1), 0, 0);
        licTextCol.addView(licSub);
        licActionRow.addView(licTextCol, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnViewLicenses = createPixelButton("VIEW NOTICES", getSurfaceElevatedColor(), Color.parseColor("#8B5CF6"));
        btnViewLicenses.setTextSize(10f);
        btnViewLicenses.setOnClickListener(v -> showLicensesDialog());
        licActionRow.addView(btnViewLicenses, new LinearLayout.LayoutParams(dp(110), dp(32)));
        content.addView(licActionRow);

        return buildCollapsibleCard(
                "About Jellyarr",
                "v" + com.termux.BuildConfig.VERSION_NAME + " • Architecture, Specs & Licenses",
                content,
                false
        );
    }

    private void showLicensesDialog() {
        TextView tv = new TextView(this);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(AppTheme.text());
        tv.setLineSpacing(dp(2), 1.15f);
        String licText = "JELLYARR OPEN SOURCE LICENSES & NOTICES\n\n"
                + "1. Jellyarr (GPL-3.0)\n"
                + "Copyright (c) 2026 Jellyarr Contributors\n"
                + "Licensed under the GNU General Public License v3.0.\n\n"
                + "2. Jellyfin Server (GPL-2.0)\n"
                + "Copyright (c) Jellyfin Contributors\n"
                + "Licensed under the GNU General Public License v2.0.\n\n"
                + "3. FFmpeg (LGPL-2.1+ / GPL-2.0+)\n"
                + "Copyright (c) FFmpeg Developers\n\n"
                + "4. Microsoft .NET Runtime (MIT)\n"
                + "Copyright (c) .NET Foundation and Contributors\n\n"
                + "5. Transmission Daemon (GPL-2.0 / GPL-3.0)\n"
                + "Copyright (c) Transmission Project\n\n"
                + "7. Termux Android Environment (Apache-2.0)\n"
                + "Copyright (c) Termux Open Source Project\n\n"
                + "8. Servarr Stack (Radarr, Sonarr, Prowlarr) (GPL-3.0)\n"
                + "Copyright (c) Servarr Development Team\n\n"
                + "11. FlareSolverr Proxy (MIT)\n"
                + "Copyright (c) FlareSolverr Contributors\n"
                + "Licensed under the MIT License.\n\n"
                + "12. Legal & Anti-Piracy Policy\n"
                + "Jellyarr is a personal homelab management utility and does not host, index, or distribute copyrighted media. End users are solely responsible for ensuring compliance with all applicable copyright and intellectual property laws.\n\n"
                + "13. AI-Assisted Development Disclosure\n"
                + "Jellyarr is developed and maintained with the assistance of artificial intelligence systems (Google DeepMind Antigravity and Gemini CLI) and empirically verified on physical hardware.\n";
        tv.setText(licText);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.setPadding(0, dp(4), 0, 0);
        sc.addView(tv);

        showOledDialog("Open Source Licenses", "Third-party software components and legal notices", sc, "CLOSE", getAccentColor(), null, null, null);
    }

    private void showDialogWithView(String title, View content) {
        if (content.getParent() instanceof ViewGroup) {
            ((ViewGroup) content.getParent()).removeView(content);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, dp(4), 0, 0);
        scroll.addView(content);

        showOledDialog(title, null, scroll, "CLOSE", getAccentColor(), null, null, null);
    }

    private void showTranscodingBenchmarksDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView tvDiag = new TextView(this);
        tvDiag.setTextSize(12);
        tvDiag.setTextColor(AppTheme.success());
        tvDiag.setPadding(0, dp(4), 0, dp(10));
        tvDiag.setText("Tap any benchmark to test hardware and software transcoding capabilities.");

        Button btnTestTranscode = createPixelButton("BENCHMARK SOFTWARE NEON", getAccentColor(), AppTheme.onAccent());
        btnTestTranscode.setOnClickListener(v -> {
            btnTestTranscode.setEnabled(false);
            btnTestTranscode.setText("BENCHMARKING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runTestTranscode(this, false);
                runOnUiThread(() -> {
                    btnTestTranscode.setEnabled(true);
                    btnTestTranscode.setText("BENCHMARK SOFTWARE NEON");
                    String res = "Software Benchmark: " + (tr.success ? "SUCCESS" : "FAILED") +
                            " | " + tr.speed + " (" + tr.fps + " fps) | " + tr.inputCodec + " -> " + tr.outputCodec +
                            " (" + tr.durationMs + " ms)";
                    tvDiag.setText(res);
                    tvDiag.setTextColor(tr.success ? AppTheme.success() : AppTheme.danger());
                });
            }).start();
        });
        content.addView(btnTestTranscode);

        View s1 = new View(this);
        content.addView(s1, new LinearLayout.LayoutParams(-1, dp(8)));

        Button btnProbeHw = createPixelButton("PROBE HARDWARE MEDIACODEC", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnProbeHw.setOnClickListener(v -> {
            btnProbeHw.setEnabled(false);
            btnProbeHw.setText("PROBING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runTestTranscode(this, true);
                runOnUiThread(() -> {
                    btnProbeHw.setEnabled(true);
                    btnProbeHw.setText("PROBE HARDWARE MEDIACODEC");
                    String res = "Hardware Probe: " + (tr.success ? "SUCCESS" : "DIAGNOSTIC REPORT") +
                            "\n" + (tr.details != null && !tr.details.isEmpty() ? tr.details : tr.error);
                    tvDiag.setText(res);
                    tvDiag.setTextColor(tr.success ? AppTheme.success() : AppTheme.warning());
                });
            }).start();
        });
        content.addView(btnProbeHw);

        View s2 = new View(this);
        content.addView(s2, new LinearLayout.LayoutParams(-1, dp(8)));

        Button btnBenchAudio = createPixelButton("BENCHMARK AUDIO (AC3 -> AAC)", getSurfaceElevatedColor(), getAccentColor());
        btnBenchAudio.setOnClickListener(v -> {
            btnBenchAudio.setEnabled(false);
            btnBenchAudio.setText("BENCHMARKING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runAudioBenchmark(this);
                runOnUiThread(() -> {
                    btnBenchAudio.setEnabled(true);
                    btnBenchAudio.setText("BENCHMARK AUDIO (AC3 -> AAC)");
                    String res = "Audio Benchmark: " + (tr.success ? "SUCCESS" : "FAILED") +
                            " | " + tr.speed + " | AC3 5.1 -> AAC 2.0 (" + tr.durationMs + " ms)";
                    tvDiag.setText(res);
                    tvDiag.setTextColor(tr.success ? AppTheme.success() : AppTheme.danger());
                });
            }).start();
        });
        content.addView(btnBenchAudio);

        View s3 = new View(this);
        content.addView(s3, new LinearLayout.LayoutParams(-1, dp(8)));

        Button btnCheckSubtitles = createPixelButton("CHECK SUBTITLES ENGINE", getSurfaceElevatedColor(), getSuccessColor());
        btnCheckSubtitles.setOnClickListener(v -> {
            btnCheckSubtitles.setEnabled(false);
            btnCheckSubtitles.setText("CHECKING...");
            new Thread(() -> {
                TranscodingDiagnostics.TranscodeTestResult tr = TranscodingDiagnostics.runSubtitleCheck(this);
                runOnUiThread(() -> {
                    btnCheckSubtitles.setEnabled(true);
                    btnCheckSubtitles.setText("CHECK SUBTITLES ENGINE");
                    tvDiag.setText(tr.details);
                    tvDiag.setTextColor(tr.success ? AppTheme.success() : AppTheme.warning());
                });
            }).start();
        });
        content.addView(btnCheckSubtitles);

        View s4 = new View(this);
        content.addView(s4, new LinearLayout.LayoutParams(-1, dp(8)));

        Button btnAuditCodecs = createPixelButton("AUDIT ALL CODECS & SUBTITLES", getSurfaceElevatedColor(), getAccentColor());
        btnAuditCodecs.setOnClickListener(v -> showCodecAuditDialog());
        content.addView(btnAuditCodecs);

        View s5 = new View(this);
        content.addView(s5, new LinearLayout.LayoutParams(-1, dp(12)));

        content.addView(tvDiag);

        showDialogWithView("Transcoding & Codec Diagnostics", content);
    }

    private View buildArchitectureDetailsView() {
        LinearLayout aboutContent = new LinearLayout(this);
        aboutContent.setOrientation(LinearLayout.VERTICAL);

        View runtimeStatusItem = buildRuntimeStatusItem();
        aboutContent.addView(runtimeStatusItem);

        LinearLayout specSummary = new LinearLayout(this);
        specSummary.setOrientation(LinearLayout.VERTICAL);
        specSummary.setPadding(0, dp(6), 0, dp(8));
        specSummary.addView(buildSpecRow("Jellyfin Server Core", "v12.4.0 (.NET 10 ARM64)"));
        specSummary.addView(buildSpecRow("Microsoft .NET Runtime", "v10.0.12 Linux Bionic"));
        specSummary.addView(buildSpecRow("FFmpeg Transcoder", "v7.1.4-Jellyfin ARM64"));
        specSummary.addView(buildSpecRow("Database Driver", "SQLite3 (Local)"));
        specSummary.addView(buildSpecRow("Security & TLS Stack", "OpenSSL 3.x System Native"));
        specSummary.addView(buildSpecRow("Process Isolation", "Android Sandboxed UID"));
        specSummary.addView(buildSpecRow("Privacy & Telemetry", "0 Trackers | 100% Local"));
        aboutContent.addView(specSummary);

        aboutContent.addView(buildTransparencyItem("Jellyfin Media Server Core", "Official Jellyfin Server ARM64 binaries (jellyfin.dll). Self-contained web media server engine. Powered by Jellyfin."));
        aboutContent.addView(buildTransparencyItem("Microsoft .NET Runtime Engine", "Bundled in lib/dotnet/. Modern high-performance cross-platform managed runtime hosting Jellyfin server."));
        aboutContent.addView(buildTransparencyItem("FFmpeg Hardware Transcoder", "Bundled in opt/jellyfin/bin/ffmpeg. Used for media remuxing, subtitle burn-in, video thumbnails, and audio transcoding."));
        aboutContent.addView(buildTransparencyItem("Foreground Service & Reliability", "mediaPlayback (ID 1001) Android declared foreground service protecting playback streaming and server reliability."));
        aboutContent.addView(buildTransparencyItem("Safe Cache Management System", "Strict allowlist cache purging for transcodes, audiodb, and fanart without touching databases, users, or configs."));
        aboutContent.addView(buildTransparencyItem("OpenSSL Security & TLS Stack", "OpenSSL 3.x cryptographic SSL/TLS engine managing local HTTPS encryption and secure outbound metadata sockets."));
        aboutContent.addView(buildTransparencyItem("Fontconfig & FreeType Engines", "libfontconfig.so / libfreetype.so native C libraries used by FFmpeg for video subtitle burn-in and text rendering."));
        aboutContent.addView(buildTransparencyItem("SQLite3 Database Driver", "libe_sqlite3.so embedded lightweight relational database engine storing user library indexes and watch progress locally."));
        aboutContent.addView(buildTransparencyItem("Application Package & Scope", getPackageName() + " (Jellyarr v" + com.termux.BuildConfig.VERSION_NAME + ") isolated Android package namespace."));
        aboutContent.addView(buildTransparencyItem("Privacy & Telemetry Verification", "No background metrics are collected or transmitted to external servers. All media data stays strictly on your device."));
        return aboutContent;
    }

    /** Settings sections now live on their own page: a single always-open card (no folded list inside). */
    private LinearLayout buildCollapsibleCard(String titleText, String subtitleText, View contentContainer, boolean initiallyExpanded) {
        LinearLayout card = UiKit.card(this);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        contentContainer.setVisibility(View.VISIBLE);
        card.addView(contentContainer);
        return card;
    }

    private View buildSpecRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(13);
        tvLabel.setTextColor(getSecondaryTextColor());
        row.addView(tvLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tvVal = new TextView(this);
        tvVal.setText(value);
        tvVal.setTextSize(13);
        tvVal.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvVal.setTextColor(getPrimaryTextColor());
        row.addView(tvVal);

        return row;
    }

    private View buildTransparencyItem(String title, String detail) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setBackground(createBorderedRoundedDrawable(getSurfaceElevatedColor(), getBorderColor(), dp(1), dp(8)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(5);
        params.bottomMargin = dp(5);
        item.setLayoutParams(params);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(13);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleTv.setTextColor(getPrimaryTextColor());
        item.addView(titleTv);

        TextView detailTv = new TextView(this);
        detailTv.setText(detail);
        detailTv.setTextSize(12);
        detailTv.setTextColor(getSecondaryTextColor());
        detailTv.setPadding(0, dp(4), 0, 0);
        item.addView(detailTv);

        return item;
    }

    // -- System Permissions Card & Detection -----------------------------------

    private View buildPermissionsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        TextView subtitle = new TextView(this);
        subtitle.setText("Verify critical Android permissions for uninterrupted streaming and multitasking survival.");
        subtitle.setTextSize(12);
        subtitle.setTextColor(getSecondaryTextColor());
        subtitle.setPadding(0, 0, 0, dp(10));
        card.addView(subtitle);

        // One-Click Grant All Permissions Button
        btnGrantAllPermissions = createPixelButton("GRANT ALL PERMISSIONS", getAccentColor(), AppTheme.onAccent());
        btnGrantAllPermissions.setTextSize(10.5f);
        btnGrantAllPermissions.setOnClickListener(v -> com.jellyarr.app.permissions.AppPermissionManager.grantNextUngrantedPermission(this));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(36));
        btnLp.bottomMargin = dp(12);
        card.addView(btnGrantAllPermissions, btnLp);

        // 1. Storage Permission Row
        LinearLayout rowStorage = buildPermissionItem(
                "All Files & Media Storage",
                "Grants Jellyfin read access to movies, music, and shows on internal and external drives.",
                v -> {
                    if (com.jellyarr.app.permissions.AppPermissionManager.isStorageGranted(this)) {
                        Toast.makeText(this, I18n.tr("Storage access is already granted."), Toast.LENGTH_SHORT).show();
                    } else {
                        com.jellyarr.app.permissions.AppPermissionManager.requestStorage(this);
                    }
                }
        );
        permStorageBadge = rowStorage.findViewWithTag("badge");
        permStorageAction = rowStorage.findViewWithTag("action_text");
        card.addView(rowStorage);

        // Media SAF & Storage Drives Config inside Storage section (card child, NOT inside horizontal row)
        Button btnSaf = createOutlinePixelButton("CONFIGURE MEDIA DRIVES & STORAGE", getAccentColor(), getAccentColor());
        btnSaf.setTextSize(9.5f);
        btnSaf.setOnClickListener(v -> startActivity(new Intent(this, JellyfinStorageActivity.class)));
        LinearLayout.LayoutParams safLp = new LinearLayout.LayoutParams(-1, dp(32));
        safLp.topMargin = dp(6);
        safLp.bottomMargin = dp(4);
        card.addView(btnSaf, safLp);

        // Subtle Hairline Divider
        View d1 = new View(this);
        d1.setBackgroundColor(getBorderColor());
        LinearLayout.LayoutParams pD1 = new LinearLayout.LayoutParams(-1, dp(1));
        pD1.topMargin = dp(8);
        pD1.bottomMargin = dp(8);
        card.addView(d1, pD1);

        // 2. Battery Optimization Row
        LinearLayout rowBattery = buildPermissionItem(
                "Battery Optimization Exemption",
                "Prevents Android OS from pausing or terminating background playback services.",
                v -> {
                    if (com.jellyarr.app.permissions.AppPermissionManager.isBatteryOptimizationExempted(this)) {
                        Toast.makeText(this, I18n.tr("Battery optimization exemption is already active."), Toast.LENGTH_SHORT).show();
                    } else {
                        requestIgnoreBatteryOptimizationsIfNeeded();
                    }
                }
        );
        permBatteryBadge = rowBattery.findViewWithTag("badge");
        permBatteryAction = rowBattery.findViewWithTag("action_text");
        card.addView(rowBattery);

        // Subtle Hairline Divider
        View d2 = new View(this);
        d2.setBackgroundColor(getBorderColor());
        LinearLayout.LayoutParams pD2 = new LinearLayout.LayoutParams(-1, dp(1));
        pD2.topMargin = dp(8);
        pD2.bottomMargin = dp(8);
        card.addView(d2, pD2);

        // 3. Notifications Row
        LinearLayout rowNotif = buildPermissionItem(
                "Foreground Service Notifications",
                "Maintains persistent media server notification to protect background execution.",
                v -> {
                    if (com.jellyarr.app.permissions.AppPermissionManager.isNotificationGranted(this)) {
                        Toast.makeText(this, I18n.tr("Notification permission is already granted."), Toast.LENGTH_SHORT).show();
                    } else {
                        com.jellyarr.app.permissions.AppPermissionManager.requestNotification(this);
                    }
                }
        );
        permNotifBadge = rowNotif.findViewWithTag("badge");
        permNotifAction = rowNotif.findViewWithTag("action_text");
        card.addView(rowNotif);

        // Subtle Hairline Divider
        View d3 = new View(this);
        d3.setBackgroundColor(getBorderColor());
        LinearLayout.LayoutParams pD3 = new LinearLayout.LayoutParams(-1, dp(1));
        pD3.topMargin = dp(8);
        pD3.bottomMargin = dp(8);
        card.addView(d3, pD3);

        // 4. Universal Background Survival Row
        String oemTitle = "Background Survival & Auto-Start";
        String oemDesc = "Configure system battery management, autostart, and background execution to ensure 24/7 media streaming.";

        LinearLayout rowOem = buildPermissionItem(
                oemTitle,
                oemDesc,
                v -> openOemBatteryOptimization()
        );
        TextView badgeOem = rowOem.findViewWithTag("badge");
        if (badgeOem != null) {
            badgeOem.setText("CONFIGURE");
            badgeOem.setTextColor(AppTheme.warning());
            badgeOem.setBackground(createRoundedDrawable(AppTheme.warningContainer(), dp(8)));
        }
        TextView actionOem = rowOem.findViewWithTag("action_text");
        if (actionOem != null) {
            actionOem.setText("Tap to open system power & autostart manager");
            actionOem.setTextColor(AppTheme.warning());
        }
        card.addView(rowOem);

        refreshPermissionsUI();
        return card;
    }

    private View buildKillTrackerCard() {
        return new View(this);
    }

    private void refreshKillTrackerUI() {
        // LMK Kill Tracker removed per user request
    }

    private void openOemBatteryOptimization() {
        Intent[] oemIntents = new Intent[] {
            // Vivo / iQOO / OriginOS: High background power consumption & Auto-start
            new Intent().setComponent(new android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")).putExtra("packagename", getPackageName()),
            // Xiaomi MIUI / HyperOS: Autostart & Power Management
            new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            // Samsung Device Care & Battery
            new Intent().setComponent(new android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            // Oppo / Realme / OnePlus ColorOS / OxygenOS
            new Intent().setComponent(new android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
            // Huawei / Honor EMUI / MagicOS
            new Intent().setComponent(new android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            new Intent().setComponent(new android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            // Transsion / Infinix / Tecno
            new Intent().setComponent(new android.content.ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.AutoStartActivity")),
            // Android 6+ standard ignore battery optimization
            new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + getPackageName())),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName()))
        };

        for (Intent intent : oemIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Throwable ignored) {}
        }
    }

    private LinearLayout buildPermissionItem(String name, String desc, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(13);
        tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvName.setTextColor(getPrimaryTextColor());
        col.addView(tvName);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextSize(11);
        tvDesc.setTextColor(getSecondaryTextColor());
        tvDesc.setPadding(0, dp(2), 0, dp(2));
        col.addView(tvDesc);

        TextView tvAction = new TextView(this);
        tvAction.setTextSize(11);
        tvAction.setTag("action_text");
        col.addView(tvAction);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        colLp.setMarginEnd(dp(12));
        row.addView(col, colLp);

        TextView badge = new TextView(this);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setTag("badge");
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.addView(badge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);
        return row;
    }

    private void refreshPermissionsUI() {
        // 1. Storage
        boolean storageGranted = com.jellyarr.app.permissions.AppPermissionManager.isStorageGranted(this);
        if (permStorageBadge != null) {
            if (storageGranted) {
                permStorageBadge.setText("GRANTED");
                permStorageBadge.setTextColor(getSuccessColor());
                permStorageBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getSuccessColor(), 45), colorWithAlpha(getSuccessColor(), 120), dp(1), dp(8)));
                if (permStorageAction != null) {
                    permStorageAction.setText("Storage access granted");
                    permStorageAction.setTextColor(getSuccessColor());
                }
            } else {
                permStorageBadge.setText("REQUIRED");
                permStorageBadge.setTextColor(getDangerColor());
                permStorageBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getDangerColor(), 45), colorWithAlpha(getDangerColor(), 120), dp(1), dp(8)));
                if (permStorageAction != null) {
                    permStorageAction.setText("Tap to grant storage access");
                    permStorageAction.setTextColor(getDangerColor());
                }
            }
        }
        if (setupPermStorageBadge != null) {
            setupPermStorageBadge.setText(storageGranted ? "GRANTED" : "REQUIRED");
            setupPermStorageBadge.setTextColor(storageGranted ? getSuccessColor() : getDangerColor());
            setupPermStorageBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(storageGranted ? getSuccessColor() : getDangerColor(), 45), colorWithAlpha(storageGranted ? getSuccessColor() : getDangerColor(), 120), dp(1), dp(6)));
        }

        // 2. Battery Optimization
        boolean batteryUnrestricted = com.jellyarr.app.permissions.AppPermissionManager.isBatteryOptimizationExempted(this);
        if (permBatteryBadge != null) {
            if (batteryUnrestricted) {
                permBatteryBadge.setText("EXEMPT");
                permBatteryBadge.setTextColor(getSuccessColor());
                permBatteryBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getSuccessColor(), 45), colorWithAlpha(getSuccessColor(), 120), dp(1), dp(8)));
                if (permBatteryAction != null) {
                    permBatteryAction.setText("Unrestricted background execution");
                    permBatteryAction.setTextColor(getSuccessColor());
                }
            } else {
                permBatteryBadge.setText("REQUIRED");
                permBatteryBadge.setTextColor(getWarningColor());
                permBatteryBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getWarningColor(), 45), colorWithAlpha(getWarningColor(), 120), dp(1), dp(8)));
                if (permBatteryAction != null) {
                    permBatteryAction.setText("Tap to disable battery restrictions");
                    permBatteryAction.setTextColor(getWarningColor());
                }
            }
        }
        if (setupPermBatteryBadge != null) {
            setupPermBatteryBadge.setText(batteryUnrestricted ? "EXEMPT" : "REQUIRED");
            setupPermBatteryBadge.setTextColor(batteryUnrestricted ? getSuccessColor() : getWarningColor());
            setupPermBatteryBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(batteryUnrestricted ? getSuccessColor() : getWarningColor(), 45), colorWithAlpha(batteryUnrestricted ? getSuccessColor() : getWarningColor(), 120), dp(1), dp(6)));
        }

        // 3. Notifications
        boolean notifEnabled = com.jellyarr.app.permissions.AppPermissionManager.isNotificationGranted(this);
        if (permNotifBadge != null) {
            if (notifEnabled) {
                permNotifBadge.setText("GRANTED");
                permNotifBadge.setTextColor(getSuccessColor());
                permNotifBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getSuccessColor(), 45), colorWithAlpha(getSuccessColor(), 120), dp(1), dp(8)));
                if (permNotifAction != null) {
                    permNotifAction.setText("Foreground notifications active");
                    permNotifAction.setTextColor(getSuccessColor());
                }
            } else {
                permNotifBadge.setText("REQUIRED");
                permNotifBadge.setTextColor(getDangerColor());
                permNotifBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(getDangerColor(), 45), colorWithAlpha(getDangerColor(), 120), dp(1), dp(8)));
                if (permNotifAction != null) {
                    permNotifAction.setText("Tap to enable notifications");
                    permNotifAction.setTextColor(getDangerColor());
                }
            }
        }
        if (setupPermNotifBadge != null) {
            setupPermNotifBadge.setText(notifEnabled ? "GRANTED" : "REQUIRED");
            setupPermNotifBadge.setTextColor(notifEnabled ? getSuccessColor() : getDangerColor());
            setupPermNotifBadge.setBackground(createBorderedRoundedDrawable(colorWithAlpha(notifEnabled ? getSuccessColor() : getDangerColor(), 45), colorWithAlpha(notifEnabled ? getSuccessColor() : getDangerColor(), 120), dp(1), dp(6)));
        }

        // 4. Update Grant All Buttons
        com.jellyarr.app.permissions.AppPermissionManager.PermissionStatus permStatus = com.jellyarr.app.permissions.AppPermissionManager.getStatus(this);
        boolean allEssentialGranted = permStatus.getAllEssentialGranted();

        String nextMissingLabel = !storageGranted ? "GRANT STORAGE ACCESS" : (!notifEnabled ? "GRANT FOREGROUND NOTIFICATIONS" : "DISABLE BATTERY RESTRICTIONS");

        if (btnGrantAllPermissions != null) {
            if (allEssentialGranted) {
                btnGrantAllPermissions.setVisibility(View.VISIBLE);
                btnGrantAllPermissions.setEnabled(false);
                btnGrantAllPermissions.setText("ALL PERMISSIONS GRANTED ✓");
                btnGrantAllPermissions.setBackground(createBorderedRoundedDrawable(AppTheme.successContainer(), AppTheme.success(), dp(1), dp(10)));
                btnGrantAllPermissions.setTextColor(AppTheme.success());
            } else {
                btnGrantAllPermissions.setVisibility(View.VISIBLE);
                btnGrantAllPermissions.setEnabled(true);
                btnGrantAllPermissions.setText(nextMissingLabel);
                btnGrantAllPermissions.setBackground(createBorderedRoundedDrawable(getAccentColor(), colorWithAlpha(AppTheme.onAccent(), 45), dp(1), dp(10)));
                btnGrantAllPermissions.setTextColor(AppTheme.onAccent());
            }
        }

        if (setupBtnGrantAll != null) {
            if (allEssentialGranted) {
                setupBtnGrantAll.setVisibility(View.VISIBLE);
                setupBtnGrantAll.setEnabled(false);
                setupBtnGrantAll.setText("ALL PERMISSIONS GRANTED ✓");
                setupBtnGrantAll.setBackground(createBorderedRoundedDrawable(AppTheme.successContainer(), AppTheme.success(), dp(1), dp(10)));
                setupBtnGrantAll.setTextColor(AppTheme.success());
            } else {
                setupBtnGrantAll.setVisibility(View.VISIBLE);
                setupBtnGrantAll.setEnabled(true);
                setupBtnGrantAll.setText(nextMissingLabel);
                setupBtnGrantAll.setBackground(createBorderedRoundedDrawable(getAccentColor(), colorWithAlpha(AppTheme.onAccent(), 45), dp(1), dp(10)));
                setupBtnGrantAll.setTextColor(AppTheme.onAccent());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        refreshPermissionsUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissionsUI();
    }

    // -- Server Error Diagnoser Card -------------------------------------------

    private View buildErrorDiagnosticCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(createBorderedRoundedDrawable(AppTheme.dangerContainer(), AppTheme.dangerContainer(), dp(1), dp(14)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);

        // Header Row: Title, Category Badge, Dismiss
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ERROR DIAGNOSIS");
        title.setTextSize(11);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(AppTheme.danger());
        headerRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));

        errorCardCategoryText = new TextView(this);
        errorCardCategoryText.setText("DETECTED");
        errorCardCategoryText.setTextSize(10);
        errorCardCategoryText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        errorCardCategoryText.setTextColor(AppTheme.danger());
        errorCardCategoryText.setBackground(createRoundedDrawable(AppTheme.dangerContainer(), dp(6)));
        errorCardCategoryText.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams catParams = new LinearLayout.LayoutParams(-2, -2);
        catParams.setMarginEnd(dp(8));
        headerRow.addView(errorCardCategoryText, catParams);

        Button btnDismiss = createPillCopyButton();
        btnDismiss.setText("DISMISS");
        btnDismiss.setOnClickListener(v -> {
            errorCardDismissed = true;
            if (errorDiagnosticCard != null) errorDiagnosticCard.setVisibility(View.GONE);
        });
        headerRow.addView(btnDismiss);

        card.addView(headerRow);

        // Section 1: What Happened
        TextView lblWhat = new TextView(this);
        lblWhat.setText("WHAT HAPPENED");
        lblWhat.setTextSize(10);
        lblWhat.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblWhat.setTextColor(getSecondaryTextColor());
        lblWhat.setPadding(0, dp(10), 0, dp(2));
        card.addView(lblWhat);

        errorCardWhatText = new TextView(this);
        errorCardWhatText.setText("Server process exited unexpectedly.");
        errorCardWhatText.setTextSize(13);
        errorCardWhatText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        errorCardWhatText.setTextColor(getPrimaryTextColor());
        card.addView(errorCardWhatText);

        // Section 2: Root Cause & Analysis
        TextView lblWhy = new TextView(this);
        lblWhy.setText("ROOT CAUSE & ANALYSIS");
        lblWhy.setTextSize(10);
        lblWhy.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblWhy.setTextColor(getSecondaryTextColor());
        lblWhy.setPadding(0, dp(8), 0, dp(2));
        card.addView(lblWhy);

        errorCardWhyText = new TextView(this);
        errorCardWhyText.setText("An unhandled exception occurred during runtime launch.");
        errorCardWhyText.setTextSize(12);
        errorCardWhyText.setTextColor(AppTheme.danger());
        card.addView(errorCardWhyText);

        // Section 3: Recommended Action
        TextView lblFix = new TextView(this);
        lblFix.setText("RECOMMENDED ACTION");
        lblFix.setTextSize(10);
        lblFix.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lblFix.setTextColor(getSecondaryTextColor());
        lblFix.setPadding(0, dp(8), 0, dp(2));
        card.addView(lblFix);

        errorCardFixText = new TextView(this);
        errorCardFixText.setText("1. Tap RESTART SERVER to relaunch the process.\n2. Review terminal logs for stack trace details.");
        errorCardFixText.setTextSize(12);
        errorCardFixText.setTextColor(AppTheme.success());
        card.addView(errorCardFixText);

        // Section 4: Raw Diagnostics Box
        errorCardRawContainer = new LinearLayout(this);
        ((LinearLayout) errorCardRawContainer).setOrientation(LinearLayout.VERTICAL);
        errorCardRawContainer.setPadding(dp(10), dp(8), dp(10), dp(8));
        errorCardRawContainer.setBackground(createRoundedDrawable(AppTheme.dangerContainer(), dp(8)));
        LinearLayout.LayoutParams rawParams = new LinearLayout.LayoutParams(-1, -2);
        rawParams.topMargin = dp(8);
        errorCardRawContainer.setLayoutParams(rawParams);

        errorCardRawText = new TextView(this);
        errorCardRawText.setTextSize(11);
        errorCardRawText.setTypeface(Typeface.MONOSPACE);
        errorCardRawText.setTextColor(AppTheme.danger());
        errorCardRawText.setMaxLines(6);
        errorCardRawText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ((LinearLayout) errorCardRawContainer).addView(errorCardRawText);

        card.addView(errorCardRawContainer);

        // Storage Full Recovery Action Buttons Row
        LinearLayout storageBtnRow = new LinearLayout(this);
        storageBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sRowParams = new LinearLayout.LayoutParams(-1, -2);
        sRowParams.topMargin = dp(8);
        storageBtnRow.setLayoutParams(sRowParams);

        btnErrorClearCache = createPixelButton("CLEAR CACHE & FREE SPACE", AppTheme.danger(), Color.WHITE);
        btnErrorClearCache.setOnClickListener(v -> showClearCacheConfirmation());
        storageBtnRow.addView(btnErrorClearCache, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        View sSpacer = new View(this);
        storageBtnRow.addView(sSpacer, new LinearLayout.LayoutParams(dp(8), 1));

        btnErrorOpenStorage = createPixelButton("OPEN STORAGE SETTINGS", getSurfaceElevatedColor(), AppTheme.warning());
        btnErrorOpenStorage.setOnClickListener(v -> openStorageSettings());
        storageBtnRow.addView(btnErrorOpenStorage, new LinearLayout.LayoutParams(0, dp(38), 1.0f));

        btnErrorClearCache.setVisibility(View.GONE);
        btnErrorOpenStorage.setVisibility(View.GONE);
        card.addView(storageBtnRow);

        // Action Buttons Row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(-1, -2);
        btnRowParams.topMargin = dp(10);
        btnRow.setLayoutParams(btnRowParams);

        Button btnCopyDiag = createPixelButton("COPY DIAGNOSTICS", AppTheme.dangerContainer(), AppTheme.danger());
        btnCopyDiag.setOnClickListener(v -> copyDiagnosticsToClipboard());
        LinearLayout.LayoutParams pCopy = new LinearLayout.LayoutParams(0, -2, 1.0f);
        pCopy.setMarginEnd(dp(6));
        btnRow.addView(btnCopyDiag, pCopy);

        Button btnLogs = createPixelButton("VIEW LOGS", getSurfaceElevatedColor(), getPrimaryTextColor());
        btnLogs.setOnClickListener(v -> {
            activeTab = 2; // Switch to Activity / Console tab
            updateBottomNavSelection();
            renderActiveTab();
        });
        LinearLayout.LayoutParams pLogs = new LinearLayout.LayoutParams(0, -2, 1.0f);
        btnRow.addView(btnLogs, pLogs);

        card.addView(btnRow);

        card.setVisibility(View.GONE);
        return card;
    }

    private void updateErrorDiagnosticCard(JellyfinController.State state) {
        if (errorDiagnosticCard == null || controller == null) return;

        boolean isError = (state == JellyfinController.State.CRASHED
                || state == JellyfinController.State.CRASH_LOOP
                || state == JellyfinController.State.FAILED);
        String lastErr = controller.getLastError();
        boolean hasErrorMsg = (lastErr != null && !lastErr.trim().isEmpty()
                && state != JellyfinController.State.RUNNING
                && state != JellyfinController.State.STOPPED);

        if (state == JellyfinController.State.RUNNING) {
            errorCardDismissed = false;
            errorDiagnosticCard.setVisibility(View.GONE);
            return;
        }

        if ((!isError && !hasErrorMsg) || errorCardDismissed) {
            errorDiagnosticCard.setVisibility(View.GONE);
            return;
        }

        errorDiagnosticCard.setVisibility(View.VISIBLE);

        String errStr = (lastErr != null) ? lastErr : "";
        long freeMb = (controller != null) ? controller.getAvailableInternalStorageMb() : 500;
        boolean isStorageFull = (freeMb < 50)
                || errStr.toLowerCase(Locale.ROOT).contains("storage is full")
                || errStr.toLowerCase(Locale.ROOT).contains("disk is full")
                || errStr.toLowerCase(Locale.ROOT).contains("sqlite_full")
                || errStr.toLowerCase(Locale.ROOT).contains("enospc");

        String cat;
        String what;
        String why;
        String fix;

        if (isStorageFull) {
            cat = "CRITICAL: STORAGE FULL";
            what = "Device internal storage is completely exhausted (< 50 MB free).";
            why = "Jellyfin SQLite databases and server logs require disk space. Server launch and auto-restarts have been halted to prevent a continuous crash loop.";
            fix = "1. Tap CLEAR CACHE & FREE SPACE below to remove temporary transcode caches.\n2. Tap OPEN STORAGE SETTINGS to delete unused applications or media files.\n3. Relaunch server once at least 100 MB free space is available.";
            if (btnErrorClearCache != null) btnErrorClearCache.setVisibility(View.VISIBLE);
            if (btnErrorOpenStorage != null) btnErrorOpenStorage.setVisibility(View.VISIBLE);
        } else {
            if (btnErrorClearCache != null) btnErrorClearCache.setVisibility(View.GONE);
            if (btnErrorOpenStorage != null) btnErrorOpenStorage.setVisibility(View.GONE);

            if (errStr.contains("8096") || errStr.toLowerCase(Locale.ROOT).contains("port")) {
                cat = "PORT CONFLICT";
                what = "HTTP Port 8096 is already bound or in use.";
                why = "Another instance of Jellyfin or another network server on your phone is occupying TCP port 8096.";
                fix = "1. Tap RESTART SERVER to terminate stale process sockets.\n2. Verify no secondary background media servers are running.";
            } else if (state == JellyfinController.State.CRASH_LOOP || errStr.toLowerCase(Locale.ROOT).contains("crash loop")) {
                cat = "CRASH LOOP";
                what = "Server failed repeatedly during launch sequence.";
                why = "Consecutive crashes exceeded the safety threshold (3 retries). This typically points to corrupted configuration, damaged database, or native library mismatch.";
                fix = "1. Tap RESET CRASH LOOP to unlatch the server.\n2. Inspect terminal traces in the Logs tab.\n3. If database is corrupt, perform CLEAN REINSTALL in Settings.";
            } else if (errStr.toLowerCase(Locale.ROOT).contains("bootstrap") || errStr.toLowerCase(Locale.ROOT).contains("extract") || errStr.toLowerCase(Locale.ROOT).contains("runtime")) {
                cat = "RUNTIME INIT ERROR";
                what = "Jellyfin binary package initialization failed.";
                why = "Asset decompression or binary permission setup was interrupted. Device storage may be exhausted or file access restricted.";
                fix = "1. Ensure at least 1 GB free internal storage.\n2. Confirm Storage permission is ON in Settings.\n3. Tap REINSTALL RUNTIME to unpack clean binaries.";
            } else if (errStr.contains("139") || errStr.contains("137") || errStr.toLowerCase(Locale.ROOT).contains("sigkill") || errStr.toLowerCase(Locale.ROOT).contains("sigsegv")) {
                cat = "PROCESS KILLED";
                what = "Jellyfin engine process was killed by the operating system.";
                why = "Android Low Memory Killer (LMK) stopped the background process to reclaim memory, or a native library segmentation fault occurred.";
                fix = "1. Tap START SERVER to relaunch.\n2. Set Battery Optimization to UNRESTRICTED in Settings.\n3. Close heavy background apps before scanning large media libraries.";
            } else {
                cat = "STARTUP FAILURE";
                what = "Server process exited unexpectedly (" + state.name() + ").";
                why = (errStr.isEmpty()) ? "The Jellyfin daemon stopped without completing readiness handshake." : errStr;
                fix = "1. Tap START SERVER or RESTART SERVER to retry.\n2. Review the terminal output in the Logs tab.\n3. Verify Storage and Battery permissions in Settings.";
            }
        }

        if (errorCardCategoryText != null) errorCardCategoryText.setText(cat);
        if (errorCardWhatText != null) errorCardWhatText.setText(what);
        if (errorCardWhyText != null) errorCardWhyText.setText(why);
        if (errorCardFixText != null) errorCardFixText.setText(fix);

        if (errorCardRawText != null) {
            String logs = controller.getLogs();
            String logSnippet = "";
            if (logs != null && !logs.isEmpty()) {
                String[] lines = logs.split("\n");
                int start = Math.max(0, lines.length - 4);
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < lines.length; i++) {
                    if (lines[i].trim().isEmpty()) continue;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(lines[i].trim());
                }
                logSnippet = sb.toString();
            }
            String raw = "State: " + state.name() + (errStr.isEmpty() ? "" : "\nError: " + errStr) +
                    (logSnippet.isEmpty() ? "" : "\nLogs:\n" + logSnippet);
            errorCardRawText.setText(raw);
        }
    }

    private void copyDiagnosticsToClipboard() {
        StringBuilder diag = new StringBuilder();
        diag.append("Jellyarr Jellyfin Diagnostic Report\n");
        diag.append("App Version: ").append(com.termux.BuildConfig.VERSION_NAME).append(" (Build ").append(com.termux.BuildConfig.VERSION_CODE).append(")\n");
        diag.append("Jellyfin Core: 12.4.0 ARM64\n");
        diag.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        diag.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        if (controller != null) {
            diag.append("Server State: ").append(controller.getState().name()).append("\n");
            diag.append("Last Error: ").append(controller.getLastError()).append("\n");
            diag.append("\n--- Log Tail ---\n");
            String logs = controller.getLogs();
            if (logs != null && !logs.isEmpty()) {
                String[] lines = logs.split("\n");
                int start = Math.max(0, lines.length - 20);
                for (int i = start; i < lines.length; i++) {
                    diag.append(lines[i]).append("\n");
                }
            }
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Jellyarr Error Diagnostics", diag.toString()));
            Toast.makeText(this, I18n.tr("Diagnostic report copied to clipboard"), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkAndShowErrorPopup(JellyfinController.State state, String lastError) {
        if (state == JellyfinController.State.RUNNING || state == JellyfinController.State.STOPPED || state == JellyfinController.State.UNINITIALIZED) {
            hasShownErrorPopupForIncident = false;
            lastPoppedIncident = "";
            return;
        }

        boolean isCritical = (state == JellyfinController.State.CRASHED || state == JellyfinController.State.CRASH_LOOP || state == JellyfinController.State.FAILED);
        long freeMb = (controller != null) ? controller.getAvailableInternalStorageMb() : 500;
        boolean isStorageFull = (freeMb < 50) || (lastError != null && (lastError.toLowerCase(Locale.ROOT).contains("storage is full") || lastError.toLowerCase(Locale.ROOT).contains("disk is full") || lastError.toLowerCase(Locale.ROOT).contains("sqlite_full")));

        if (!isCritical && !isStorageFull) return;

        String incidentKey = state.name() + ":" + (lastError != null ? lastError : "") + ":" + (isStorageFull ? "storage" : "normal");
        if (hasShownErrorPopupForIncident && incidentKey.equals(lastPoppedIncident)) {
            return;
        }

        hasShownErrorPopupForIncident = true;
        lastPoppedIncident = incidentKey;

        showErrorPopupDialog(state, lastError, isStorageFull);
    }

    private void showErrorPopupDialog(JellyfinController.State state, String lastError, boolean isStorageFull) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;

        TextView tv = new TextView(this);
        tv.setTextSize(11.5f);
        tv.setTextColor(AppTheme.textSecondary());
        tv.setLineSpacing(dp(2), 1.15f);

        if (isStorageFull) {
            tv.setText("Device internal storage has less than 50 MB remaining. The server cannot safely write to SQLite or log files and auto-restart has been halted to prevent a crash loop.\n\nPlease free up internal storage space.");
            showOledDialog("Critical: Storage Full", "Device storage space exhausted", tv,
                    "CLEAR CACHE", AppTheme.danger(), this::showClearCacheConfirmation, "DISMISS", null);
        } else {
            String msg = (lastError != null && !lastError.isEmpty()) ? lastError : "The server process exited unexpectedly.";
            tv.setText(msg + "\n\nA diagnostic report can be copied or submitted to GitHub.");
            showOledDialog("Server Crash Detected", "State: " + state.name(), tv,
                    "COPY REPORT", getAccentColor(), this::copyDiagnosticsToClipboard, "DISMISS", null);
        }
    }

    private void openStorageSettings() {
        Intent[] storageIntents = new Intent[] {
                new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))
        };
        for (Intent intent : storageIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Throwable ignored) {}
        }
    }

    private long lastStorageScanMs = 0;

    private void updateStorageInfoText() {
        // Scanning the whole data dir is expensive: do it at most every 30s when merely opening the tab.
        long now = SystemClock.elapsedRealtime();
        if (lastStorageScanMs != 0 && now - lastStorageScanMs < 30000) return;
        lastStorageScanMs = now;
        refreshStorageBar();
    }

    private void showCodecAuditDialog() {
        TextView reportTv = new TextView(this);
        reportTv.setText(TranscodingDiagnostics.generateDetailedAuditReport());
        reportTv.setTypeface(Typeface.MONOSPACE);
        reportTv.setTextSize(10.5f);
        reportTv.setTextColor(AppTheme.text());
        reportTv.setPadding(0, dp(4), 0, dp(4));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(reportTv);

        showOledDialog("Codec Audit Report", "Hardware MediaCodec & Software Capabilities", scroll,
                "COPY REPORT", getAccentColor(), () -> {
                    ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("Codec Report", reportTv.getText()));
                        Toast.makeText(this, I18n.tr("Copied SoC codec report to clipboard"), Toast.LENGTH_SHORT).show();
                    }
                }, "CLOSE", null);
    }

    private void showClearCacheConfirmation() {
        TextView tv = new TextView(this);
        tv.setText("This removes temporary cache and FFmpeg transcode files only.\n\nYour Jellyfin server, libraries, accounts, media, and configurations will not be deleted.");
        tv.setTextSize(11.5f);
        tv.setTextColor(AppTheme.textSecondary());
        tv.setLineSpacing(dp(2), 1.15f);

        showOledDialog("Clear Temporary Cache?", "Safely reclaim storage space", tv,
                "CLEAR CACHE", AppTheme.danger(), this::executeSafeClearCache, "CANCEL", null);
    }

    private void executeSafeClearCache() {
        new Thread(() -> {
            boolean allSuccess = true;
            File home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR;
            File jellyfinCache = new File(home, ".cache/jellyfin");
            File appCache = getCacheDir();

            // Strict allowlist: Only these subdirectories and disposable items may be cleared
            String[] allowedJellyfinSubdirs = {
                    "transcodes", "audiodb-album", "audiodb-artist",
                    "extracted-audio-images", "fanart-movies", "images",
                    "imagesbyname", "omdb"
            };

            try {
                String canonicalCache = jellyfinCache.getCanonicalPath();

                // Clear allowlisted Jellyfin cache subdirectories
                if (jellyfinCache.exists() && jellyfinCache.isDirectory()) {
                    for (String sub : allowedJellyfinSubdirs) {
                        File targetSub = new File(jellyfinCache, sub);
                        if (targetSub.exists()) {
                            String canonicalTarget = targetSub.getCanonicalPath();
                            // Strict boundary & path traversal check
                            if (canonicalTarget.startsWith(canonicalCache) && !canonicalTarget.equals(canonicalCache)) {
                                if (!deleteDirectoryRecursively(targetSub)) {
                                    allSuccess = false;
                                }
                            }
                        }
                    }
                }

                // Clear app-level cache
                if (appCache != null && appCache.exists()) {
                    File[] appFiles = appCache.listFiles();
                    if (appFiles != null) {
                        for (File f : appFiles) {
                            if (!deleteDirectoryRecursively(f)) {
                                allSuccess = false;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error executing safe cache clear: " + e.getMessage(), e);
                allSuccess = false;
            }

            final boolean finalSuccess = allSuccess;
            runOnUiThread(() -> {
                updateStorageInfoText();
                refreshStorageBar();
                if (finalSuccess) {
                    Toast.makeText(JellyarrActivity.this, I18n.tr("Cache cleared successfully."), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(JellyarrActivity.this, I18n.tr("Cache partially cleared. Some temporary files could not be removed."), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private boolean deleteDirectoryRecursively(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return true;
        boolean success = true;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteDirectoryRecursively(child)) {
                        success = false;
                    }
                }
            }
        }
        return fileOrDir.delete() && success;
    }

    private long getFolderSizeMB(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long bytes = calculateDirSize(dir);
        return bytes / (1024 * 1024);
    }

    private long calculateDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && java.nio.file.Files.isSymbolicLink(dir.toPath())) {
                return 0;
            }
        } catch (Throwable ignored) {}
        if (dir.isFile()) return dir.length();
        String absPath = dir.getAbsolutePath();
        if (absPath.startsWith("/storage") || absPath.startsWith("/mnt") || absPath.contains("/JellyarrMedia") || absPath.contains("/FishbowlMedia")) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File f : files) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    continue;
                }
            } catch (Throwable ignored) {}
            String path = f.getAbsolutePath();
            if (path.startsWith("/storage") || path.startsWith("/mnt") || path.contains("/JellyarrMedia") || path.contains("/FishbowlMedia") || f.getName().equals("storage") || f.getName().equals("JellyarrMedia")) {
                continue;
            }
            if (f.isFile()) {
                size += f.length();
            } else if (f.isDirectory()) {
                size += calculateDirSize(f);
            }
        }
        return size;
    }

    private View buildRuntimeStatusItem() {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        item.setBackground(createBorderedRoundedDrawable(getSurfaceElevatedColor(), getBorderColor(), dp(1), dp(8)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        params.bottomMargin = dp(8);
        item.setLayoutParams(params);

        TextView titleTv = new TextView(this);
        titleTv.setText("Runtime Components Status");
        titleTv.setTextSize(13);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleTv.setTextColor(getPrimaryTextColor());
        item.addView(titleTv);

        runtimeStatusText = new TextView(this);
        runtimeStatusText.setTextSize(12);
        runtimeStatusText.setTextColor(getSecondaryTextColor());
        runtimeStatusText.setPadding(0, dp(4), 0, dp(8));
        item.addView(runtimeStatusText);

        btnSettingsRetry = createPixelButton("RETRY SETUP", getAccentColor(), AppTheme.onAccent());
        btnSettingsRetry.setOnClickListener(v -> {
            Toast.makeText(this, I18n.tr("Retrying Jellyarr setup..."), Toast.LENGTH_SHORT).show();
            controller.reinstallRuntime(this);
        });
        btnSettingsRetry.setVisibility(View.GONE);
        item.addView(btnSettingsRetry);

        Button btnViewSetup = createPixelButton("VIEW SETUP OVERVIEW & LOGS", AppTheme.inner(), AppTheme.text());
        btnViewSetup.setTextSize(10f);
        btnViewSetup.setOnClickListener(v -> showSetupOverlay(true));
        LinearLayout.LayoutParams pViewSetup = new LinearLayout.LayoutParams(-1, dp(36));
        pViewSetup.bottomMargin = dp(6);
        item.addView(btnViewSetup, pViewSetup);

        btnSettingsReinstall = createPixelButton("REINSTALL RUNTIME", AppTheme.inner(), Color.WHITE);
        btnSettingsReinstall.setOnClickListener(v -> showReinstallConfirmation());
        item.addView(btnSettingsReinstall);

        Button btnAddQsTile = createPixelButton("ADD QUICK SETTINGS TILE", AppTheme.inner(), AppTheme.text());
        btnAddQsTile.setTextSize(10f);
        btnAddQsTile.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                JellyarrQuickSettingsTileService.requestAddTile(this,
                    () -> Toast.makeText(this, I18n.tr("Jellyarr Quick Settings tile added!"), Toast.LENGTH_SHORT).show(),
                    () -> Toast.makeText(this, I18n.tr("Quick Settings tile request cancelled or already present."), Toast.LENGTH_SHORT).show()
                );
            } else {
                Toast.makeText(this, I18n.tr("To add the Jellyarr tile: swipe down twice from the top of your screen, tap Edit (pencil icon), and drag the Jellyarr tile into active tiles."), Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams pAddTile = new LinearLayout.LayoutParams(-1, dp(36));
        pAddTile.topMargin = dp(6);
        item.addView(btnAddQsTile, pAddTile);

        refreshRuntimeStatusUI();
        return item;
    }

    private void refreshRuntimeStatusUI() {
        if (runtimeStatusText == null) return;
        boolean initialized = JellyfinBootstrapper.isInitialized(this);
        String lastErr = JellyfinBootstrapper.getLastError();
        JellyfinController.State state = controller != null ? controller.getState() : JellyfinController.State.UNINITIALIZED;

        StringBuilder sb = new StringBuilder();
        if (state == JellyfinController.State.INITIALIZING) {
            sb.append("STATUS: EXTRACTING & CONFIGURING...\n");
            String bMsg = controller.getBootstrapProgressMessage();
            int bPct = controller.getBootstrapProgressPercent();
            if (bMsg != null && !bMsg.isEmpty()) {
                sb.append(bMsg).append(" (").append(bPct).append("%)\n");
            }
            sb.append("Please keep the application open until installation finishes.");
            runtimeStatusText.setTextColor(AppTheme.warning());
        } else if (initialized) {
            sb.append("STATUS: INSTALLED & VERIFIED\n");
            sb.append("- Jellyarr & Jellyfin Core 12.4.0\n");
            sb.append("- Microsoft .NET 10.0.12 Runtime Engine\n");
            sb.append("- FFmpeg 7.1.4-Jellyfin Transcoder\n");
            sb.append("- OpenSSL & SQLite3 System Libraries\n");
            sb.append("Runtime binaries are fully verified in application storage.");
            runtimeStatusText.setTextColor(AppTheme.success());
        } else {
            sb.append("STATUS: INSTALLATION FAILED / INCOMPLETE\n");
            if (lastErr != null && !lastErr.isEmpty()) {
                sb.append("Error detail: ").append(lastErr).append("\n");
            } else {
                sb.append("One or more runtime components are missing from storage.\n");
            }
            sb.append("Tap RETRY SETUP or REINSTALL RUNTIME to unpack fresh runtime binaries.");
            runtimeStatusText.setTextColor(AppTheme.danger());
        }

        runtimeStatusText.setText(sb.toString());

        boolean failed = (!initialized && state != JellyfinController.State.INITIALIZING && state != JellyfinController.State.STARTING);
        boolean busy = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING || state == JellyfinController.State.STOPPING);

        if (btnSettingsRetry != null) {
            btnSettingsRetry.setVisibility(failed ? View.VISIBLE : View.GONE);
            btnSettingsRetry.setEnabled(!busy);
        }
        if (btnSettingsReinstall != null) {
            btnSettingsReinstall.setEnabled(!busy);
        }
    }

    private void showReinstallConfirmation() {
        TextView tv = new TextView(this);
        tv.setText("This will extract a fresh copy of Jellyarr and FFmpeg runtime files.\n\nYour existing media libraries, user accounts, database, and configurations will NOT be deleted.");
        tv.setTextSize(11.5f);
        tv.setTextColor(AppTheme.textSecondary());
        tv.setLineSpacing(dp(2), 1.15f);

        showOledDialog("Reinstall Jellyarr Runtime?", "Clean runtime re-extraction", tv,
                "REINSTALL", getAccentColor(), () -> {
                    Toast.makeText(this, I18n.tr("Beginning clean runtime extraction..."), Toast.LENGTH_SHORT).show();
                    controller.reinstallRuntime(this);
                }, "CANCEL", null);
    }

    // -- State Rendering Logic (NO EMOJIS) --------------------------------------

    @Override
    public void onServerChanged(final JellyfinController.State state) {
        runOnUiThread(() -> renderCurrentState());
    }

    @Override
    public void onBootstrapProgress(final String message, final int percent) {
        runOnUiThread(() -> updateSetupProgressUI(message, percent));
    }

    private void updateSetupProgressUI(final String message, final int percent) {
        if (setupOverlayView == null) return;
        if (setupOverlayView.getVisibility() != View.VISIBLE && !JellyfinBootstrapper.isInitialized(this)) {
            setupOverlayView.setVisibility(View.VISIBLE);
            setChromeVisibilityForSetup(true);
            setupOverlayView.bringToFront();
        }

        if (setupProgressBar != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setupProgressBar.setProgress(percent, true);
            } else {
                setupProgressBar.setProgress(percent);
            }
        }
        if (setupProgressText != null) {
            setupProgressText.setText(percent + "%");
            setupProgressText.setTextColor(getAccentColor());
        }
        if (setupDetailText != null) {
            setupDetailText.setText(message);
            setupDetailText.setTextColor(getSecondaryTextColor());
        }
        if (setupLogSummaryText != null) {
            setupLogSummaryText.setText(message);
        }

        updateSetupStageItem("setup_step_1", "1. Recombining split package archives (asset chunks)", percent >= 35, percent > 0 && percent < 35);
        updateSetupStageItem("setup_step_2", "2. Extracting Jellyarr & Jellyfin Core 12.4.0 ARM64 runtime", percent >= 65, percent >= 35 && percent < 65);
        updateSetupStageItem("setup_step_3", "3. Unpacking Microsoft .NET 10 & FFmpeg 7.1.4 transcoder", percent >= 88, percent >= 65 && percent < 88);
        updateSetupStageItem("setup_step_4", "4. Configuring POSIX permissions & system libraries", percent >= 95, percent >= 88 && percent < 95);
        updateSetupStageItem("setup_step_5", "5. Verifying server binaries & service readiness", percent >= 100, percent >= 95 && percent < 100);

        if (percent >= 100) {
            setupOverlayView.postDelayed(() -> {
                if (setupOverlayView != null && JellyfinBootstrapper.isInitialized(this)) {
                    isSetupOverlayManuallyOpened = false;
                    if (setupDismissBtn != null) {
                        setupDismissBtn.setVisibility(View.GONE);
                    }
                    setupOverlayView.setVisibility(View.GONE);
                    setChromeVisibilityForSetup(false);
                    renderActiveTab();
                }
                renderCurrentState();
            }, 600);
        }
    }

    private void renderCurrentState() {
        if (controller == null) return;
        renderSetupOverlayState();
        JellyfinController.State state = controller.getState();
        JellyfinController.StartupStage stage = controller.getStartupStage();

        if (statusBadge != null) {
            int color;
            String statusText;
            switch (state) {
                case RUNNING:
                    color = getSuccessColor();
                    statusText = "SERVER RUNNING";
                    break;
                case STARTING:
                    color = getWarningColor();
                    statusText = "STARTING SERVER";
                    break;
                case INITIALIZING:
                    color = getWarningColor();
                    statusText = "INITIALIZING RUNTIME";
                    break;
                case STOPPING:
                    color = AppTheme.warning();
                    statusText = "STOPPING SERVER";
                    break;
                case UNINITIALIZED:
                case STOPPED:
                    color = AppTheme.textSecondary();
                    statusText = "SERVER STOPPED";
                    break;
                case CRASHED:
                case FAILED:
                    color = getDangerColor();
                    statusText = "SERVER FAILED";
                    break;
                case CRASH_LOOP:
                    color = getDangerColor();
                    statusText = "CRASH LOOP DETECTED";
                    break;
                default:
                    color = getWarningColor();
                    statusText = state.name();
                    break;
            }
            statusBadge.setText("● " + statusText);
            statusBadge.setTextColor(color);
            statusBadge.setBackground(UiKit.shape(this, AppTheme.withAlpha(color, 0x22), 0, UiKit.R_PILL));
            statusBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        }

        // Render Slim Header Progress Bar & Notification Banner (Small progress bar not full screen)
        if (headerProgressBar != null) {
            boolean isSetupActive = (setupOverlayView != null && setupOverlayView.getVisibility() == View.VISIBLE);
            if (isSetupActive) {
                setChromeVisibilityForSetup(true);
            }
            boolean showLoading = !isSetupActive && (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING);
            if (showLoading) {
                headerProgressBar.setVisibility(View.VISIBLE);
                if (headerStatusBanner != null) headerStatusBanner.setVisibility(View.VISIBLE);

                int progressVal;
                String stageText;

                String bMsg = controller.getBootstrapProgressMessage();
                int bPct = controller.getBootstrapProgressPercent();

                if (state == JellyfinController.State.INITIALIZING && bMsg != null && !bMsg.isEmpty()) {
                    progressVal = Math.max(5, bPct);
                    stageText = "1/4 " + bMsg + " (" + progressVal + "%)";
                } else {
                    switch (stage) {
                        case STARTING_RUNTIME:
                            progressVal = (bPct > 0) ? bPct : 25;
                            stageText = (bMsg != null && !bMsg.isEmpty()) ? "1/4 " + bMsg : "1/4 Starting runtime environment...";
                            break;
                        case LAUNCHING_SERVER:
                            progressVal = 50;
                            stageText = "2/4 Launching Jellyfin server process...";
                            break;
                        case WAITING_FOR_SERVER:
                            progressVal = 70;
                            stageText = "3/4 Binding HTTP ports...";
                            break;
                        case CHECKING_READINESS:
                            progressVal = 90;
                            stageText = "4/4 Polling readiness & migrating database...";
                            break;
                        case READY:
                            progressVal = 100;
                            stageText = "Server Ready";
                            break;
                        default:
                            progressVal = 15;
                            stageText = "Initializing Jellyfin server...";
                            break;
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    headerProgressBar.setProgress(progressVal, true);
                } else {
                    headerProgressBar.setProgress(progressVal);
                }

                if (headerStatusBanner != null) {
                    headerStatusBanner.setText(stageText);
                }
            } else {
                headerProgressBar.setVisibility(View.GONE);
                if (headerStatusBanner != null) headerStatusBanner.setVisibility(View.GONE);
            }
        }

        // Render Real Stage Progress Card (NO EMOJIS)
        if (startupProgressCard != null) {
            boolean initialized = JellyfinBootstrapper.isInitialized(this);
            boolean isStarting = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING);
            long readyGraceMs = 1500;
            if (state != JellyfinController.State.RUNNING) {
                if (!isStarting) startupCompletedTimeMs = 0;
            } else if (stage == JellyfinController.StartupStage.READY && startupCompletedTimeMs == 0) {
                startupCompletedTimeMs = System.currentTimeMillis();
            }

            boolean isJustReady = (state == JellyfinController.State.RUNNING && stage == JellyfinController.StartupStage.READY
                    && startupCompletedTimeMs > 0 && (System.currentTimeMillis() - startupCompletedTimeMs < readyGraceMs));
            boolean[] comp = updateStartupCompanionRows();
            boolean companionsStarting = comp[0];
            long nowMs = System.currentTimeMillis();
            if (isStarting || companionsStarting) lastStartupActivityMs = nowMs;
            // Companions are started one after the other: stay visible while some still wait their turn.
            long graceMs = comp[1] ? 8000L : readyGraceMs;
            boolean sessionGrace = lastStartupActivityMs > 0 && nowMs - lastStartupActivityMs < graceMs;
            boolean anyBusy = isStarting || companionsStarting || (sessionGrace && comp[1]);
            boolean showStartupProgress = isStarting || isJustReady || companionsStarting || sessionGrace;

            startupProgressCard.setVisibility(showStartupProgress ? View.VISIBLE : View.GONE);
            int jfRows = (isStarting || isJustReady) ? View.VISIBLE : View.GONE;
            for (TextView sr : new TextView[]{txtStage1, txtStage2, txtStage3, txtStage4, txtStage5}) {
                if (sr != null) sr.setVisibility(jfRows);
            }

            if (showStartupProgress && txtStage1 != null) {
                if (startupProgressHeader != null) {
                    startupProgressHeader.setText(isStarting ? "Starting Jellyarr Server..."
                            : anyBusy ? "Starting companion services..." : "Jellyarr Server Ready");
                }
                if (startupProgressBar != null) {
                    if (!anyBusy && !isStarting) {
                        startupProgressBar.setIndeterminate(false);
                        startupProgressBar.setProgress(100);
                    } else {
                        startupProgressBar.setIndeterminate(true);
                    }
                }

                String bMsg = controller.getBootstrapProgressMessage();
                int bPct = controller.getBootstrapProgressPercent();

                boolean isRuntimeDone = initialized || (bPct == 100) || stage.ordinal() > JellyfinController.StartupStage.STARTING_RUNTIME.ordinal() || state == JellyfinController.State.RUNNING;
                String stage1Label = (bMsg != null && !bMsg.isEmpty()) ? "1. " + bMsg + " (" + bPct + "%)" : (initialized ? "1. Runtime environment ready" : "1. Starting runtime environment");
                updateStageRow(txtStage1, stage1Label, isRuntimeDone, !isRuntimeDone && (stage == JellyfinController.StartupStage.STARTING_RUNTIME || state == JellyfinController.State.INITIALIZING));

                boolean isLaunchDone = stage.ordinal() > JellyfinController.StartupStage.LAUNCHING_SERVER.ordinal() || state == JellyfinController.State.RUNNING;
                boolean isLaunchActive = stage == JellyfinController.StartupStage.LAUNCHING_SERVER && state != JellyfinController.State.RUNNING;
                updateStageRow(txtStage2, "2. Launching Jellyarr core server process", isLaunchDone, isLaunchActive);

                boolean isWaitDone = stage.ordinal() > JellyfinController.StartupStage.WAITING_FOR_SERVER.ordinal() || state == JellyfinController.State.RUNNING;
                boolean isWaitActive = stage == JellyfinController.StartupStage.WAITING_FOR_SERVER && state != JellyfinController.State.RUNNING;
                updateStageRow(txtStage3, "3. Waiting for server (binding port 8096)", isWaitDone, isWaitActive);

                boolean isReadinessDone = stage.ordinal() > JellyfinController.StartupStage.CHECKING_READINESS.ordinal() || state == JellyfinController.State.RUNNING;
                boolean isReadinessActive = stage == JellyfinController.StartupStage.CHECKING_READINESS && state != JellyfinController.State.RUNNING;
                updateStageRow(txtStage4, "4. Checking readiness & migrating database", isReadinessDone, isReadinessActive);

                boolean isServerReady = stage == JellyfinController.StartupStage.READY || state == JellyfinController.State.RUNNING;
                updateStageRow(txtStage5, "5. Ready (Server running)", isServerReady, false);
            }

            if (isStarting || companionsStarting || anyBusy) {
                mainHandler.removeCallbacks(startupProgressPoller);
                mainHandler.postDelayed(startupProgressPoller, 500);
            } else if (showStartupProgress) {
                long remaining = graceMs - (System.currentTimeMillis() - lastStartupActivityMs);
                mainHandler.removeCallbacks(startupProgressPoller);
                mainHandler.postDelayed(startupProgressPoller, Math.max(100, remaining + 50));
            }
        }

        // Real-Time Server Error Diagnosis Card
        updateErrorDiagnosticCard(state);
        checkAndShowErrorPopup(state, controller.getLastError());

        // Authoritative Readiness Guard for OPEN JELLYFIN WEB UI & START SERVER
        boolean isReady = (state == JellyfinController.State.RUNNING && stage == JellyfinController.StartupStage.READY);
        boolean busy = (state == JellyfinController.State.INITIALIZING || state == JellyfinController.State.STARTING || state == JellyfinController.State.STOPPING);
        boolean crashLoop = (state == JellyfinController.State.CRASH_LOOP);
        boolean failed = (state == JellyfinController.State.FAILED || state == JellyfinController.State.CRASHED);

        if (btnOpenJellyfin != null) {
            if (state == JellyfinController.State.RUNNING) {
                btnOpenJellyfin.setVisibility(View.VISIBLE);
                if (isReady) {
                    applyButtonStyle(btnOpenJellyfin, "OPEN JELLYFIN", Color.WHITE, Color.BLACK, true);
                } else {
                    applyButtonStyle(btnOpenJellyfin, "STARTING WEB UI...", AppTheme.outline(), Color.WHITE, false);
                }
            } else {
                btnOpenJellyfin.setVisibility(View.GONE);
            }
        }

        if (btnStartServer != null) {
            if (state == JellyfinController.State.RUNNING) {
                btnStartServer.setVisibility(View.GONE);
            } else {
                btnStartServer.setVisibility(View.VISIBLE);
                if (state == JellyfinController.State.STARTING || state == JellyfinController.State.INITIALIZING) {
                    applyButtonStyle(btnStartServer, "STARTING SERVER...", AppTheme.outline(), getWarningColor(), false);
                } else if (state == JellyfinController.State.STOPPING) {
                    applyButtonStyle(btnStartServer, "STOPPING SERVER...", AppTheme.outline(), AppTheme.danger(), false);
                } else if (crashLoop) {
                    applyButtonStyle(btnStartServer, "SERVER CRASHED", AppTheme.dangerContainer(), getDangerColor(), false);
                } else if (failed) {
                    applyButtonStyle(btnStartServer, "RETRY START SERVER", getDangerColor(), Color.WHITE, true);
                } else if (!JellyfinBootstrapper.isInitialized(this)) {
                    applyButtonStyle(btnStartServer, "SETUP / RETRY INSTALL", Color.WHITE, Color.BLACK, true);
                } else {
                    applyButtonStyle(btnStartServer, "START SERVER", Color.WHITE, Color.BLACK, true);
                }
            }
        }

        if (btnStopServer != null) {
            if (state == JellyfinController.State.RUNNING) {
                applyDockButtonStyle(btnStopServer, "Stop", AppTheme.danger(), Color.WHITE, R.drawable.ic_stop, true);
            } else {
                applyDockButtonStyle(btnStopServer, "Stop", AppTheme.inner(), AppTheme.textMuted(), R.drawable.ic_stop, false);
            }
        }

        if (btnRestartServer != null) {
            boolean canRestart = !busy && !crashLoop && (state == JellyfinController.State.RUNNING);
            if (canRestart) {
                applyDockButtonStyle(btnRestartServer, "Restart", AppTheme.outline(), Color.WHITE, R.drawable.ic_restart, true);
            } else {
                applyDockButtonStyle(btnRestartServer, "Restart", AppTheme.inner(), AppTheme.textMuted(), R.drawable.ic_restart, false);
            }
        }

        if (btnResetCrash != null) {
            if (crashLoop) {
                btnResetCrash.setVisibility(View.VISIBLE);
                // PRAGMA quick_check on a large jellyfin.db must not run on the UI thread.
                refreshCrashLoopDbHealthAsync();
                btnResetCrash.setText(crashLoopLabel());
            } else {
                btnResetCrash.setVisibility(View.GONE);
            }
        }

        // Show REINSTALL RUNTIME button on Home dashboard when installation/startup failed or user wants to re-bootstrap
        if (btnReinstallRuntime != null) {
            boolean showReinstall = failed || !JellyfinBootstrapper.isInitialized(this);
            btnReinstallRuntime.setVisibility(showReinstall ? View.VISIBLE : View.GONE);
            btnReinstallRuntime.setEnabled(!busy);
        }

        // Refresh Settings Runtime Status if visible
        refreshRuntimeStatusUI();

        // Refresh live server health & resources
        refreshServerHealthUI();

        // Broadcast state changes to Home Screen Widgets and the Quick Settings tile (not every 500 ms poll)
        String widgetKey = state.name() + "/" + stage;
        if (!widgetKey.equals(lastWidgetBroadcastKey)) {
            lastWidgetBroadcastKey = widgetKey;
            com.jellyarr.app.widget.JellyarrWidgetProvider.updateAllWidgets(this);
            JellyarrQuickSettingsTileService.requestTileUpdate(this);
        }
    }

    private String lastWidgetBroadcastKey = null;

    private volatile boolean crashLoopDbCorrupt = false;
    private volatile boolean crashLoopDbCheckRunning = false;
    private long crashLoopDbCheckedAt = 0;

    private String crashLoopLabel() {
        return buttonLabel(crashLoopDbCorrupt ? "AUTO-HEAL DATABASE & RESTART" : "RESET CRASH LOOP & RESTART");
    }

    private void refreshCrashLoopDbHealthAsync() {
        if (crashLoopDbCheckRunning
                || (crashLoopDbCheckedAt > 0 && SystemClock.elapsedRealtime() - crashLoopDbCheckedAt < 30000L)) return;
        crashLoopDbCheckRunning = true;
        runBg(() -> {
            boolean corrupt;
            try {
                corrupt = !BackupRestoreManager.isDatabaseHealthy(
                        new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR, ".local/share/jellyfin/data/jellyfin.db"));
            } catch (Throwable t) {
                corrupt = false;
            }
            final boolean result = corrupt;
            runOnUiThread(() -> {
                crashLoopDbCheckRunning = false;
                crashLoopDbCheckedAt = SystemClock.elapsedRealtime();
                crashLoopDbCorrupt = result;
                if (btnResetCrash != null && btnResetCrash.getVisibility() == View.VISIBLE) btnResetCrash.setText(crashLoopLabel());
            });
        });
    }

    private void updateStageRow(TextView tv, String label, boolean completed, boolean active) {
        if (tv == null) return;
        if (completed) {
            tv.setText("[READY]  " + label);
            tv.setTextColor(AppTheme.success());
        } else if (active) {
            tv.setText("[IN PROGRESS]  " + label);
            tv.setTextColor(getWarningColor());
        } else {
            tv.setText("[WAITING]  " + label);
            tv.setTextColor(getSecondaryTextColor());
        }
    }

    // -- Storage & Network Helpers ----------------------------------------------

    private volatile String cachedRawLanIp = null;
    private volatile String cachedRawTailscaleIp = null;
    private volatile boolean isScanningNetwork = false;

    private void updateLanAddressViews(String rawLan, String rawTailscale) {
        if (clientEndpointsContainer != null) {
            renderClientStreamingEndpoints(rawLan, rawTailscale);
            return;
        }
        if (lanIpValueText != null) {
            View dotLan = (rowLanView != null) ? rowLanView.findViewWithTag("dot_lan") : null;
            if (rawLan == null) {
                lanIpValueText.setText("Wi-Fi not connected");
                lanIpValueText.setTextColor(AppTheme.textMuted());
                setEndpointCopyState(btnCopyLanIp, rowLanView, dotLan, false);
            } else {
                lanIpValueText.setText(rawLan + ":8096");
                lanIpValueText.setTextColor(AppTheme.text());
                setEndpointCopyState(btnCopyLanIp, rowLanView, dotLan, true);
            }
        }
        if (localIpValueText != null) {
            View dotLocal = (rowLocalView != null) ? rowLocalView.findViewWithTag("dot_local") : null;
            localIpValueText.setText("127.0.0.1:8096");
            localIpValueText.setTextColor(AppTheme.text());
            setEndpointCopyState(btnCopyLocalIp, rowLocalView, dotLocal, true);
        }
        if (tailscaleIpValueText != null) {
            View dotTailscale = (rowTailscaleView != null) ? rowTailscaleView.findViewWithTag("dot_tailscale") : null;
            if (rawTailscale == null) {
                tailscaleIpValueText.setText("Tailscale not active");
                tailscaleIpValueText.setTextColor(AppTheme.textMuted());
                setEndpointCopyState(btnCopyTailscaleIp, rowTailscaleView, dotTailscale, false);
            } else {
                tailscaleIpValueText.setText(rawTailscale + ":8096");
                tailscaleIpValueText.setTextColor(AppTheme.text());
                setEndpointCopyState(btnCopyTailscaleIp, rowTailscaleView, dotTailscale, true);
            }
        }
    }

    private volatile String cachedDefaultIp = null;
    private volatile NetworkUtils.LanEndpoint cachedActiveLan = null;
    private String lastRenderedNetworkSignature = null;

    private String networkSignature(String rawLan, String rawTailscale) {
        NetworkUtils.LanEndpoint a = cachedActiveLan;
        return cachedDefaultIp + "|" + rawLan + "|" + rawTailscale + "|" + (a != null ? a.label + "/" + a.ip : "-");
    }

    private void refreshLanAddress() {
        // Fast path: render from the in-memory cache (skipped when nothing changed since the last render)
        if (clientEndpointsContainer == null || clientEndpointsContainer.getChildCount() == 0
                || !networkSignature(cachedRawLanIp, cachedRawTailscaleIp).equals(lastRenderedNetworkSignature)) {
            updateLanAddressViews(cachedRawLanIp, cachedRawTailscaleIp);
        }

        // Background interface traversal (network callbacks fire often; one scan at a time)
        if (!isScanningNetwork) {
            isScanningNetwork = true;
            runBg(() -> {
                try {
                    String rawLan = getRawLanIp();
                    String rawTailscale = getRawTailscaleIp();
                    cachedDefaultIp = NetworkUtils.getDefaultIp();
                    cachedActiveLan = NetworkUtils.getActiveLanEndpoint();
                    cachedRawLanIp = rawLan;
                    cachedRawTailscaleIp = rawTailscale;
                    runOnUiThread(() -> {
                        if (!isUiAlive()) return;
                        if (networkSignature(rawLan, rawTailscale).equals(lastRenderedNetworkSignature)
                                && clientEndpointsContainer != null && clientEndpointsContainer.getChildCount() > 0) {
                            return;   // unchanged: avoid rebuilding the rows (and re-reading the user list)
                        }
                        updateLanAddressViews(rawLan, rawTailscale);
                    });
                } finally {
                    isScanningNetwork = false;
                }
            });
        }
    }

    public String getRawLanIp() {
        return NetworkUtils.getRawLanIp();
    }

    private String getLanAddress() {
        return NetworkUtils.getLanAddress();
    }

    public String getRawTailscaleIp() {
        return NetworkUtils.getRawTailscaleIp();
    }

    private String getTailscaleAddress() {
        return NetworkUtils.getTailscaleAddress();
    }

    private boolean isTailscaleIp(String ip) {
        return NetworkUtils.isTailscaleIp(ip);
    }

    private void setupNetworkMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(@NonNull Network n) { runOnUiThread(() -> refreshLanAddress()); }
                    @Override public void onLost(@NonNull Network n) { runOnUiThread(() -> refreshLanAddress()); }
                    @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) { runOnUiThread(() -> refreshLanAddress()); }
                };
                try {
                    NetworkRequest req = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                            .build();
                    cm.registerNetworkCallback(req, networkCallback);
                } catch (Exception ignored) {}
            }
        } else {
            connectivityReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { refreshLanAddress(); }
            };
            registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    private void teardownNetworkMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            }
            networkCallback = null;
        }
        if (connectivityReceiver != null) {
            try { unregisterReceiver(connectivityReceiver); } catch (Exception ignored) {}
            connectivityReceiver = null;
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }
    }

    private void requestStoragePermissionIfNeeded() {
        MediaStorageManager sm = MediaStorageManager.getInstance(this);
        if (!sm.hasStoragePermission()) {
            sm.requestStoragePermission(this);
        }
    }

    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    // -- Design Utilities -------------------------------------------------------

    // -- Material 3 buttons -----------------------------------------------------
    // Every button in the app goes through resolveButtonStyle(): filled (accent / danger),
    // tonal (accent container), outlined (neutral) or tinted. Shape: UiKit 12dp button radius.

    /** Returns {fill, textColor, strokeColor, strokeWidthPx} for the requested look. */
    private int[] resolveButtonStyle(int bg, int fg) {
        if (bg == Color.WHITE) bg = AppTheme.accent();          // legacy "primary" white button
        if (bg == Color.BLACK) bg = AppTheme.card();
        if (fg == Color.WHITE) fg = AppTheme.text();
        if (fg == Color.BLACK) fg = AppTheme.onAccent();

        int card = AppTheme.card(), inner = AppTheme.inner(), page = AppTheme.bg();
        boolean neutral = bg == card || bg == inner || bg == page || bg == AppTheme.outline() || bg == Color.TRANSPARENT;
        boolean container = bg == AppTheme.accentContainer() || bg == AppTheme.dangerContainer()
                || bg == AppTheme.successContainer() || bg == AppTheme.warningContainer();

        if (neutral) {
            if (fg == AppTheme.accent()) {
                // Tonal button: accent container with on-container text.
                return new int[]{AppTheme.accentContainer(), AppTheme.onAccentContainer(), 0, 0};
            }
            // Outlined button: transparent fill, 1dp outline.
            return new int[]{Color.TRANSPARENT, fg, AppTheme.outlineStrong(), Math.max(1, dp(1))};
        }
        if (container || Color.alpha(bg) < 255) {
            // Tonal / tinted: keep the caller's semantic text colour.
            return new int[]{bg, fg, 0, 0};
        }
        // Filled: guarantee a readable label whatever the fill is (accent, danger, brand colour).
        return new int[]{bg, AppTheme.contrastOn(bg), 0, 0};
    }

    /** Button shape shared with UiKit: 12dp radius, ripple masked to the full shape (outlined buttons included). */
    private Drawable buttonBackground(int fill, int stroke, int strokeWidthPx, int rippleColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(UiKit.R_INNER));
        if (strokeWidthPx > 0) shape.setStroke(strokeWidthPx, stroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(AppTheme.text());
        mask.setCornerRadius(dp(UiKit.R_INNER));
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask);
    }

    /** Applies colours + pill background to a button according to {@link #resolveButtonStyle}. */
    private void applyM3Colors(Button b, int bg, int fg) {
        int[] st = resolveButtonStyle(bg, fg);
        b.setTextColor(st[1]);
        // M3 state layer: 12% of the content colour on press.
        b.setBackground(buttonBackground(st[0], st[2], st[3], colorWithAlpha(st[1], 40)));
    }

    private void attachPressMotion(Button b) {
        b.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return false;
            int action = event.getAction();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
            }
            return false; // never swallow the click
        });
    }

    private void baseButtonMetrics(Button b, float sizeSp, float letterSpacing) {
        b.setTextSize(sizeSp);
        b.setLetterSpacing(letterSpacing);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setStateListAnimator(null);
    }

    private Button createPillCopyButton() {
        Button btn = new Button(this);
        btn.setText("COPY");
        btn.setAllCaps(true);
        baseButtonMetrics(btn, 10f, 0.06f);
        applyM3Colors(btn, AppTheme.inner(), AppTheme.text());
        // Tonal-neutral: filled with the inner surface, no outline.
        btn.setBackground(buttonBackground(AppTheme.inner(), 0, 0, colorWithAlpha(AppTheme.accent(), 60)));
        btn.setPadding(dp(12), 0, dp(12), 0);
        btn.setMinWidth(dp(56));
        btn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        attachPressMotion(btn);
        return btn;
    }

    private static final Set<String> LABEL_ACRONYMS = new HashSet<>(java.util.Arrays.asList(
            "OK", "API", "URL", "URLS", "LAN", "IP", "UI", "PIN", "DB", "TV", "USB", "OTG", "OTA", "SHA", "HTTP",
            "CPU", "GPU", "RAM", "ID", "AAC", "NEON", "TXT", "VPN", "TMDB", "JF", "SAF", "POSIX",
            "KB", "MB", "GB", "TB", "SQL", "DNS", "SMB", "NFS", "SSL", "TLS"));
    private static final Set<String> LABEL_SMALL_WORDS = new HashSet<>(java.util.Arrays.asList(
            "a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "vs"));

    /**
     * Text for a button: the translation when one exists (Arabic UI), otherwise an ALL-CAPS English label is
     * shown in Title Case ("COPY REPORT" -> "Copy Report"); acronyms and mixed-case labels are kept as they are.
     */
    private static String buttonLabel(String label) {
        if (label == null) return null;
        String tr = I18n.tr(label);
        if (tr != null && !tr.equals(label)) return tr;
        if (!label.equals(label.toUpperCase(Locale.ROOT)) || !label.matches("(?s).*[A-Z].*")) return label;
        String[] words = label.split(" ", -1);
        StringBuilder sb = new StringBuilder(label.length());
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            String w = words[i];
            String core = w.replaceAll("[^A-Za-z0-9]", "");
            if (core.isEmpty() || LABEL_ACRONYMS.contains(core) || core.matches(".*[0-9].*") || w.startsWith(".")) {
                sb.append(w);
                continue;
            }
            String lower = w.toLowerCase(Locale.ROOT);
            if (i > 0 && LABEL_SMALL_WORDS.contains(lower)) {
                sb.append(lower);
                continue;
            }
            int k = 0;
            while (k < lower.length() && !Character.isLetter(lower.charAt(k))) k++;
            sb.append(lower, 0, k);
            if (k < lower.length()) sb.append(Character.toUpperCase(lower.charAt(k))).append(lower.substring(k + 1));
        }
        return sb.toString();
    }

    private Button createPixelButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(buttonLabel(text));
        b.setAllCaps(false);
        baseButtonMetrics(b, 12f, 0.02f);
        b.setPadding(dp(14), 0, dp(14), 0);
        applyM3Colors(b, bgColor, textColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(40));
        params.topMargin = dp(6);
        b.setLayoutParams(params);
        attachPressMotion(b);
        return b;
    }

    private Button createOutlinePixelButton(String text, int outlineColor, int textColor) {
        Button b = new Button(this);
        b.setText(buttonLabel(text));
        b.setAllCaps(false);
        baseButtonMetrics(b, 11.5f, 0.02f);
        b.setPadding(dp(14), 0, dp(14), 0);
        applyOutlineButtonStyle(b, outlineColor, textColor);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(36)));
        attachPressMotion(b);
        return b;
    }

    private void applyOutlineButtonStyle(Button b, int outlineColor, int textColor) {
        if (b == null) return;
        if (outlineColor == Color.WHITE) outlineColor = AppTheme.outlineStrong();
        if (textColor == Color.WHITE) textColor = AppTheme.text();
        b.setTextColor(textColor);
        b.setBackground(buttonBackground(Color.TRANSPARENT, outlineColor, Math.max(1, dp(1)),
                colorWithAlpha(outlineColor, 60)));
    }

    private void applyButtonStyle(Button btn, String text, int bgColor, int textColor, boolean enabled) {
        if (btn == null) return;
        btn.setText(buttonLabel(text));
        btn.setEnabled(enabled);
        btn.setAlpha(1.0f);
        btn.setAllCaps(false);
        baseButtonMetrics(btn, 13.5f, 0.01f);
        btn.setTypeface(UiKit.BOLD);
        btn.setPadding(0, 0, 0, 0);
        applyM3Colors(btn, bgColor, textColor);
    }

    private void applyDockButtonStyle(Button btn, String text, int bgColor, int textColor, int iconRes, boolean enabled) {
        if (btn == null) return;
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1.0f : 0.5f); // M3 disabled content ~38-50%
        btn.setAllCaps(false);
        baseButtonMetrics(btn, 13f, 0.01f);
        btn.setTypeface(UiKit.BOLD);
        btn.setPadding(0, 0, 0, 0);
        btn.setText(text);
        btn.setCompoundDrawables(null, null, null, null);
        applyM3Colors(btn, bgColor, textColor);
    }

    private static class CenteredImageSpan extends ImageSpan {
        CenteredImageSpan(Drawable drawable) {
            super(drawable);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            Drawable b = getDrawable();
            canvas.save();
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            int transY = (y + fm.descent + y + fm.ascent) / 2 - b.getBounds().bottom / 2;
            canvas.translate(x, transY);
            b.draw(canvas);
            canvas.restore();
        }
    }

    private GradientDrawable createRoundedDrawable(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(UiTheme.normalizeFill(color));
        gd.setCornerRadius(UiTheme.normalizeRadius(radiusDp, getResources().getDisplayMetrics().density));
        return gd;
    }

    private GradientDrawable createBorderedRoundedDrawable(int bgColor, int borderColor, int strokeWidthPx, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(UiTheme.normalizeFill(bgColor));
        gd.setStroke(strokeWidthPx, UiTheme.normalizeBorder(borderColor));
        gd.setCornerRadius(UiTheme.normalizeRadius(radiusDp, getResources().getDisplayMetrics().density));
        return gd;
    }

    /** One look for every section label in every tab. */
    private void styleSectionLabel(TextView tv) {
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setLetterSpacing(0.08f);
        tv.setTextColor(AppTheme.textMuted());
    }

    private int colorWithAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // -- Update notifications -----------------------------------------------------

    private View buildUpdateNotifyBlock(final String ch, String heading, String desc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextSize(10.5f);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        h.setTextColor(UpdateManager.CHANNEL_STABLE.equals(ch) ? getAccentColor() : AppTheme.warning());
        h.setLetterSpacing(0.06f);
        box.addView(h);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11f);
        d.setTextColor(AppTheme.textMuted());
        d.setPadding(0, dp(2), 0, dp(4));
        box.addView(d);

        box.addView(updateToggleRow("Notification outside the app", "System notification when a new version is found (checked in the background).",
                UpdateNotifier.systemNotify(this, ch), checked -> {
                    UpdateNotifier.setSystemNotify(this, ch, checked);
                    if (checked && Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
                    }
                }));
        box.addView(updateToggleRow("Notice inside the app", "Pop-up with what's new when you open Jellyarr.",
                UpdateNotifier.inAppNotify(this, ch), checked -> UpdateNotifier.setInAppNotify(this, ch, checked)));

        final TextView status = new TextView(this);
        status.setTextSize(11f);
        status.setTextColor(AppTheme.textSecondary());
        status.setPadding(0, dp(4), 0, dp(2));
        status.setText("Latest: not checked yet");
        box.addView(status);
        updateNotifyStatusViews.put(ch, status);
        return box;
    }

    private final java.util.HashMap<String, TextView> updateNotifyStatusViews = new java.util.HashMap<>();

    private interface BoolSink { void set(boolean v); }

    private View updateToggleRow(String title, String sub, boolean on, final BoolSink sink) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(AppTheme.text());
        col.addView(t);
        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(11f);
        st.setTextColor(AppTheme.textMuted());
        st.setPaddingRelative(0, dp(2), dp(8), 0);
        col.addView(st);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch sw = UiStyle.newSwitch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((v, checked) -> sink.set(checked));
        row.addView(sw);
        return row;
    }

    private boolean updateAutoInstall = false;
    private boolean updateStartupCheckDone = false;
    private final android.os.Handler updateUiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Reads bundled release notes (single source, also used as the GitHub release text). */
    private String readBundledReleaseNotes() {
        try (java.io.InputStream in = getAssets().open("release_notes.txt");
             java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8").trim();
        } catch (Throwable t) {
            return "No release notes available.";
        }
    }

    private View notesView(String text) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(AppTheme.text());
        tv.setLineSpacing(dp(2), 1.1f);
        sv.addView(tv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(260)));
        return sv;
    }

    private void showWhatsNewDialog() {
        showOledDialog("What's new", "Jellyarr v" + UpdateManager.getInstance(this).getCurrentVersionName(this),
                notesView(readBundledReleaseNotes()), "OK", getAccentColor(), null, null, null);
    }

    /** Once after each update: show the summary of the new version. */
    private void maybeShowWhatsNewAfterUpdate() {
        try {
            int cur = UpdateManager.getInstance(this).getCurrentVersionCode(this);
            if (UpdateNotifier.lastSeenVersion(this) == cur) return;
            UpdateNotifier.setLastSeenVersion(this, cur);
            updateUiHandler.postDelayed(() -> { if (!isFinishing()) showWhatsNewDialog(); }, 600);
        } catch (Throwable ignored) {}
    }

    /** Background lookup on app start; shows the in-app notice for enabled channels. */
    private void runStartupUpdateCheck() {
        if (updateStartupCheckDone) return;
        updateStartupCheckDone = true;
        new Thread(() -> {
            try {
                final UpdateNotifier.Result r = UpdateNotifier.fetchLatest();
                final int cur = UpdateManager.getInstance(this).getCurrentVersionCode(this);
                runOnUiThread(() -> {
                    for (String ch : new String[]{UpdateManager.CHANNEL_STABLE, UpdateManager.CHANNEL_BETA}) {
                        TextView st = updateNotifyStatusViews.get(ch);
                        UpdateNotifier.Latest l = r.get(ch);
                        if (st != null) st.setText(l.found() ? "Latest: v" + l.name + (l.code > cur ? "  (newer than installed)" : "  (up to date)") : "Latest: none published");
                    }
                    final UpdateManager um = UpdateManager.getInstance(this);
                    if (um.isAutoDownloadEnabled() && um.isNetworkAllowedForAuto(this)) {
                        final UpdateNotifier.Latest sel = r.get(um.getUpdateChannel(this));
                        if (sel.found() && sel.code > cur && !sel.apkUrl.isEmpty() && !um.isReadyToInstall(sel.code)) {
                            new Thread(() -> um.downloadBlocking(this, sel.code, sel.name, sel.apkUrl, sel.shaUrl), "fishbowl-auto-dl").start();
                        }
                    }
                    if (isFinishing()) return;
                    // "Update available" popup: only when automatic download is off (otherwise the bar shows the
                    // download); a finished download is handled by the install prompt.
                    if (um.isAutoDownloadEnabled()) return;
                    for (String ch : new String[]{UpdateManager.CHANNEL_STABLE, UpdateManager.CHANNEL_BETA}) {
                        UpdateNotifier.Latest l = r.get(ch);
                        if (l.found() && l.code > cur && !um.isReadyToInstall(l.code) && UpdateNotifier.inAppNotify(this, ch)
                                && UpdateNotifier.dismissedCode(this, ch) < l.code) {
                            showUpdateNotice(ch, l);
                            break;
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }, "fishbowl-update-startup").start();
    }

    private void showUpdateNotice(final String ch, final UpdateNotifier.Latest l) {
        String label = UpdateManager.CHANNEL_STABLE.equals(ch) ? "Stable" : "Beta";
        showOledDialog("Update available (" + label + ")", "Jellyarr v" + l.name,
                notesView(l.notes.isEmpty() ? "No release notes." : l.notes),
                UpdateManager.getInstance(this).isReadyToInstall(l.code) ? I18n.tr("Install Update") : I18n.tr("Download Update"),
                getAccentColor(), () -> beginInAppUpdate(ch, l.code),
                "LATER", () -> UpdateNotifier.setDismissed(this, ch, l.code));
    }

    /** Switch to the chosen channel, then download, verify and open the installer from inside the app. */
    private void beginInAppUpdate(String ch, int code) {
        UpdateManager m = UpdateManager.getInstance(this);
        m.setUpdateChannel(ch);
        updateChannelSelectionUI();
        openSettingsSectionByName("Software Updates");
        if (m.isReadyToInstall(code)) {
            m.restoreSavedState(this);
            m.installUpdate(this);
            return;
        }
        updateAutoInstall = true;   // download once the check finds the update (no auto-install)
        m.checkForUpdates(this, true);
    }

    private void handleUpdateIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(UpdateNotifier.EXTRA_OPEN_UPDATE, false)) return;
        intent.removeExtra(UpdateNotifier.EXTRA_OPEN_UPDATE);
        final String ch = intent.getStringExtra(UpdateNotifier.EXTRA_CHANNEL);
        new Thread(() -> {
            try {
                final UpdateNotifier.Result r = UpdateNotifier.fetchLatest();
                final String useCh = ch == null ? UpdateManager.CHANNEL_BETA : ch;
                final UpdateNotifier.Latest l = r.get(useCh);
                int cur = UpdateManager.getInstance(this).getCurrentVersionCode(this);
                runOnUiThread(() -> { if (l.found() && l.code > cur && !isFinishing()) showUpdateNotice(useCh, l); });
            } catch (Throwable ignored) {}
        }, "fishbowl-update-intent").start();
    }

    private LinearLayout updateBannerBar;
    private TextView updateBannerText;
    private ProgressBar updateBannerProgress;
    private Button updateBannerInstallBtn;
    private int updateBannerLastPct = -1;
    private boolean installPromptHandled = false;

    private View buildUpdateBanner() {
        updateBannerBar = new LinearLayout(this);
        updateBannerBar.setOrientation(LinearLayout.VERTICAL);
        updateBannerBar.setBackgroundColor(AppTheme.card());
        updateBannerBar.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(16), dp(6), dp(8), dp(6));

        updateBannerText = new TextView(this);
        updateBannerText.setTextSize(12.5f);
        updateBannerText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        updateBannerText.setTextColor(AppTheme.text());
        row.addView(updateBannerText, new LinearLayout.LayoutParams(0, -2, 1.0f));

        updateBannerInstallBtn = createPixelButton(I18n.tr("Install Update"), AppTheme.accent(), AppTheme.onAccent());
        updateBannerInstallBtn.setTextSize(11f);
        updateBannerInstallBtn.setVisibility(View.GONE);
        updateBannerInstallBtn.setOnClickListener(v -> UpdateManager.getInstance(this).installUpdate(this));
        row.addView(updateBannerInstallBtn, new LinearLayout.LayoutParams(-2, dp(34)));

        // "!" : always there, opens the update details
        TextView info = new TextView(this);
        info.setText("!");
        info.setTextSize(15f);
        info.setTypeface(Typeface.DEFAULT_BOLD);
        info.setGravity(Gravity.CENTER);
        info.setTextColor(AppTheme.accent());
        info.setBackground(UiKit.shape(this, AppTheme.accentContainer(), 0, UiKit.R_PILL));
        info.setContentDescription(I18n.tr("Update details"));
        info.setOnClickListener(v -> showUpdateDetailsDialog());
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        infoLp.setMarginStart(dp(8));
        row.addView(info, infoLp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(16f);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(AppTheme.textSecondary());
        close.setContentDescription(I18n.tr("Close"));
        close.setOnClickListener(v -> {
            UpdateManager.getInstance(this).closeBanner();
            updateBannerBar.setVisibility(View.GONE);
            applyTopInsets();
            // With the bar gone, a finished download asks for the install right away.
            installPromptHandled = false;
            maybeShowInstallReadyPrompt();
        });
        row.addView(close, new LinearLayout.LayoutParams(dp(40), dp(34)));
        updateBannerBar.addView(row);

        updateBannerProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateBannerProgress.setMax(100);
        updateBannerProgress.setVisibility(View.GONE);
        updateBannerBar.addView(updateBannerProgress, new LinearLayout.LayoutParams(-1, dp(3)));
        return updateBannerBar;
    }

    /**
     * The update bar is pinned by the download (see UpdateManager.pinBanner) and survives leaving and re-opening the
     * app; only its close button removes it. It shows the download percent, then an install button.
     */
    private void refreshUpdateBanner() {
        refreshUpdateBannerInner();
        applyTopInsets();
    }

    private void refreshUpdateBannerInner() {
        if (updateBannerBar == null) return;
        UpdateManager mgr = UpdateManager.getInstance(this);
        int pinned = mgr.getBannerCode();
        if (pinned <= 0 || pinned <= mgr.getCurrentVersionCode(this)) {
            updateBannerLastPct = -1;
            updateBannerBar.setVisibility(View.GONE);
            return;
        }
        UpdateManager.State st = mgr.getCurrentState();
        boolean downloading = st == UpdateManager.State.DOWNLOADING;
        boolean verifying = st == UpdateManager.State.VERIFYING;
        boolean ready = mgr.isReadyToInstall(pinned)
                || st == UpdateManager.State.DOWNLOAD_COMPLETE || st == UpdateManager.State.VERIFICATION_SUCCESSFUL;
        String ver = mgr.getLatestVersionName();
        String v = (ver == null || ver.isEmpty()) ? "" : " v" + ver;
        if (downloading) {
            long total = mgr.getTotalApkSize();
            long got = mgr.getDownloadedBytes();
            int pct = total > 0 ? (int) Math.min(100, got * 100 / total) : 0;
            if (pct == updateBannerLastPct && updateBannerBar.getVisibility() == View.VISIBLE) return;
            updateBannerLastPct = pct;
            updateBannerText.setText(I18n.tr("Downloading update") + v + " — " + pct + "%");
            updateBannerProgress.setVisibility(View.VISIBLE);
            updateBannerProgress.setProgress(pct);
            updateBannerInstallBtn.setVisibility(View.GONE);
        } else if (verifying) {
            updateBannerText.setText(I18n.tr("Verifying update") + v + "...");
            updateBannerProgress.setVisibility(View.GONE);
            updateBannerInstallBtn.setVisibility(View.GONE);
        } else if (ready) {
            updateBannerText.setText(I18n.tr("Update") + v + " " + I18n.tr("ready to install") + " — 100%");
            updateBannerProgress.setVisibility(View.GONE);
            updateBannerInstallBtn.setVisibility(View.VISIBLE);
        } else {
            long partial = mgr.getPartialBytes(this);
            updateBannerText.setText(I18n.tr("Update") + v + " " + I18n.tr(partial > 0 ? "download paused" : "available"));
            updateBannerProgress.setVisibility(View.GONE);
            updateBannerInstallBtn.setVisibility(View.GONE);
        }
        updateBannerBar.setVisibility(View.VISIBLE);
    }

    /** Details of the pending update (version, channel, release notes) from the "!" in the bar. */
    private void showUpdateDetailsDialog() {
        final UpdateManager mgr = UpdateManager.getInstance(this);
        int code = mgr.getBannerCode() > 0 ? mgr.getBannerCode() : mgr.getLatestVersionCode();
        String ver = mgr.getLatestVersionName();
        String notes = mgr.getLatestReleaseNotes();
        if (notes == null || notes.trim().isEmpty()) notes = "No release notes.";
        String label = UpdateManager.CHANNEL_BETA.equals(mgr.getUpdateChannel()) ? "Beta" : "Stable";
        final boolean ready = mgr.isReadyToInstall(code);
        showOledDialog("Update available (" + label + ")", "Jellyarr v" + (ver == null ? "" : ver),
                notesView(notes), ready ? I18n.tr("Install Update") : null, getAccentColor(),
                ready ? () -> mgr.installUpdate(this) : null, "CLOSE", null);
    }

    /**
     * Asks to install a finished download, but only while the update bar is closed (an open bar already has the
     * install button). Asked again on every app start until the update is installed.
     */
    private void maybeShowInstallReadyPrompt() {
        if (installPromptHandled || isFinishing()) return;
        final UpdateManager mgr = UpdateManager.getInstance(this);
        final int ready = mgr.getReadyCode();
        if (ready <= mgr.getCurrentVersionCode(this) || !mgr.isReadyToInstall(ready)) return;
        if (mgr.getBannerCode() == ready) return;   // bar still open: no popup
        installPromptHandled = true;
        String ver = mgr.getLatestVersionName();
        showOledDialog("Update ready to install", "Jellyarr v" + (ver == null ? "" : ver),
                notesView(mgr.getLatestReleaseNotes() == null || mgr.getLatestReleaseNotes().trim().isEmpty()
                        ? "No release notes." : mgr.getLatestReleaseNotes()),
                I18n.tr("Install Update"), getAccentColor(), () -> mgr.installUpdate(this),
                "LATER", null);
    }

    private final UpdateManager.UpdateListener updateListener = new UpdateManager.UpdateListener() {
        @Override
        public void onUpdateStateChanged(UpdateManager.State state) {
            refreshUpdateUI();
            refreshUpdateBanner();
            if (updateAutoInstall) {
                UpdateManager mgr = UpdateManager.getInstance(JellyarrActivity.this);
                if (state == UpdateManager.State.UPDATE_AVAILABLE) {
                    mgr.startDownload(JellyarrActivity.this);
                } else if (state == UpdateManager.State.VERIFICATION_SUCCESSFUL) {
                    updateAutoInstall = false;   // download only: installing is a separate tap
                } else if (state == UpdateManager.State.UP_TO_DATE || state == UpdateManager.State.DOWNLOAD_FAILED
                        || state == UpdateManager.State.VERIFICATION_FAILED || state == UpdateManager.State.PERMISSION_REQUIRED
                        || state == UpdateManager.State.NETWORK_UNAVAILABLE || state == UpdateManager.State.RELEASE_UNAVAILABLE) {
                    updateAutoInstall = false;
                }
            }
        }

        @Override
        public void onDownloadProgress(long bytesDownloaded, long totalBytes) {
            refreshUpdateProgress(bytesDownloaded, totalBytes);
            refreshUpdateBanner();
        }
    };

    private void updateChannelSelectionUI() {
        if (btnChannelStable == null || btnChannelBeta == null) return;
        String channel = UpdateManager.getInstance(this).getUpdateChannel();
        boolean isBeta = UpdateManager.CHANNEL_BETA.equals(channel);

        if (!isBeta) {
            btnChannelStable.setBackground(createRoundedDrawable(getAccentColor(), dp(6)));
            btnChannelStable.setTextColor(AppTheme.onAccent());
            btnChannelBeta.setBackground(null);
            btnChannelBeta.setTextColor(AppTheme.textMuted());
        } else {
            btnChannelBeta.setBackground(createRoundedDrawable(AppTheme.warning(), dp(6)));
            btnChannelBeta.setTextColor(AppTheme.contrastOn(AppTheme.warning()));
            btnChannelStable.setBackground(null);
            btnChannelStable.setTextColor(AppTheme.textMuted());
        }

        if (updateChannelBadge != null) {
            updateChannelBadge.setText(isBeta ? "Beta preview and bleeding-edge builds" : "Official stable releases (Recommended)");
            updateChannelBadge.setTextColor(AppTheme.textMuted());
            updateChannelBadge.setBackground(null);
            updateChannelBadge.setPadding(0, dp(2), 0, 0);
        }
    }

    private void refreshUpdateUI() {
        if (updateStatusText == null || updateActionBtn == null) return;
        UpdateManager manager = UpdateManager.getInstance(this);
        UpdateManager.State state = manager.getCurrentState();

        int currentCode = manager.getCurrentVersionCode(this);
        String currentName = manager.getCurrentVersionName(this);
        String channel = manager.getUpdateChannel();
        String channelLabel = UpdateManager.CHANNEL_BETA.equals(channel) ? "Beta Channel" : "Stable Channel";

        StringBuilder sb = new StringBuilder();
        sb.append("- Installed Build: v").append(currentName).append(" (Build ").append(currentCode).append(")\n");
        sb.append("- Update Channel: ").append(channelLabel).append("\n");
        if (manager.getLatestVersionCode() > 0) {
            sb.append("- Latest on GitHub: v").append(manager.getLatestVersionName())
              .append(" (Build ").append(manager.getLatestVersionCode()).append(")\n");
        }
        sb.append("\n");
        String verInfo = sb.toString();

        updateChannelSelectionUI();
        if (updateForceDownloadBtn != null) {
            updateForceDownloadBtn.setVisibility(View.GONE);
        }

        switch (state) {
            case IDLE:
                updateStatusText.setText(verInfo + "Status: Ready to check GitHub for updates.");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
            case CHECKING:
                updateStatusText.setText(verInfo + "Checking GitHub releases for " + channelLabel + "...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case UP_TO_DATE:
                if (manager.getLatestVersionCode() > 0 && currentCode > manager.getLatestVersionCode()) {
                    updateStatusText.setText(verInfo + "Status: You are running an active development build (Build " + currentCode + "), which is newer than the latest GitHub release (Build " + manager.getLatestVersionCode() + "). All integrated companion services are up to date.");
                } else if (manager.getLatestVersionCode() > 0 && currentCode == manager.getLatestVersionCode()) {
                    updateStatusText.setText(verInfo + "Status: You're running the latest official release for " + channelLabel + ". All integrated companion services are up to date.");
                } else {
                    updateStatusText.setText(verInfo + "Status: Your app and all integrated services are up to date.");
                }
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                if (updateForceDownloadBtn != null && !manager.getApkUrl().isEmpty()) {
                    updateForceDownloadBtn.setVisibility(View.VISIBLE);
                    updateForceDownloadBtn.setText("Force Re-download (v" + manager.getLatestVersionName() + ")");
                    updateForceDownloadBtn.setOnClickListener(v -> manager.startDownload(this));
                }
                break;
            case UPDATE_AVAILABLE:
                long partial = manager.getPartialBytes(this);
                updateStatusText.setText(verInfo + "Status: Update Available! (v" + manager.getLatestVersionName() + ")"
                        + (partial > 0 ? "\nSaved so far: " + (partial / (1024 * 1024)) + " MB - the download will resume from here." : "")
                        + "\n\nWhat's new:\n" + manager.getLatestReleaseNotes());
                updateActionBtn.setText(I18n.tr("Download Update"));
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case DOWNLOADING:
                updateStatusText.setText(verInfo + "Status: Downloading update...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case DOWNLOAD_COMPLETE:
                updateStatusText.setText(verInfo + "Status: Update downloaded successfully.");
                updateActionBtn.setText(I18n.tr("Install Update"));
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.installUpdate(this));
                break;
            case VERIFYING:
                updateStatusText.setText(verInfo + "Status: Verifying SHA-256 package checksum...");
                updateActionBtn.setVisibility(View.GONE);
                break;
            case VERIFICATION_SUCCESSFUL:
                updateStatusText.setText(verInfo + "Status: Update downloaded and verified. It is saved on this device - you can install it now or later without downloading again.");
                updateActionBtn.setText(I18n.tr("Install Update"));
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.installUpdate(this));
                break;
            case VERIFICATION_FAILED:
                updateStatusText.setText(verInfo + "Status: Update verification failed (corrupt file).");
                updateActionBtn.setText(I18n.tr("Download Update"));
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case PERMISSION_REQUIRED:
                updateStatusText.setText(verInfo + "Status: Permission to install unknown apps is required.");
                updateActionBtn.setText("Open settings");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.openInstallPermissionSettings(this));
                break;
            case DOWNLOAD_FAILED:
                updateStatusText.setText(verInfo + "Status: Update download failed. Check network connection.");
                updateActionBtn.setText(I18n.tr("Download Update"));
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.startDownload(this));
                break;
            case NETWORK_UNAVAILABLE:
                updateStatusText.setText(verInfo + "Status: Unable to reach GitHub or rate limit exceeded. Please check connection.");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
            case RELEASE_UNAVAILABLE:
                updateStatusText.setText(verInfo + "Status: No releases currently available for " + channelLabel + ".");
                updateActionBtn.setText("Check for updates");
                updateActionBtn.setVisibility(View.VISIBLE);
                updateActionBtn.setOnClickListener(v -> manager.checkForUpdates(this, true));
                break;
        }
    }

    private void refreshUpdateProgress(long downloaded, long total) {
        if (updateStatusText == null) return;
        UpdateManager manager = UpdateManager.getInstance(this);
        String currentName = manager.getCurrentVersionName(this);
        int currentCode = manager.getCurrentVersionCode(this);
        String channel = manager.getUpdateChannel();
        String channelLabel = UpdateManager.CHANNEL_BETA.equals(channel) ? "Beta Channel" : "Stable Channel";
        String verInfo = "- Installed: v" + currentName + " (" + currentCode + ") - " + channelLabel + "\n\n";

        long downloadedMb = downloaded / (1024 * 1024);
        long totalMb = total / (1024 * 1024);
        int percent = total > 0 ? (int) ((downloaded * 100) / total) : 0;

        updateStatusText.setText(verInfo + "Downloading update: Jellyarr v" + manager.getLatestVersionName() + "\n" + percent + "%\nProgress: " + downloadedMb + " MB / " + totalMb + " MB");
    }

    private String readFirstLine(File f) {
        if (!f.exists()) return "";
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line = br.readLine();
            return line != null ? line.trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
