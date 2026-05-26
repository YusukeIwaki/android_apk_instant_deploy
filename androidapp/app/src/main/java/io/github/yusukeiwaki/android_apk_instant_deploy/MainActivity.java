package io.github.yusukeiwaki.android_apk_instant_deploy;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.StateSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = Color.parseColor("#1B1B1B");
    private static final int MUTED = Color.parseColor("#565C65");
    private static final int LINE = Color.parseColor("#DFE1E2");
    private static final int BLUE = Color.parseColor("#005EA8");
    private static final int BLUE_DISABLED = Color.parseColor("#9DBCD4");
    private static final int GREEN = Color.parseColor("#2E7044");
    private static final int RED_DARK = Color.parseColor("#B50909");
    private static final int BG = Color.parseColor("#F7F9FA");
    private static final int SUCCESS_BG = Color.parseColor("#ECF3EC");
    private static final int SUCCESS_BORDER = Color.parseColor("#B4D0B9");
    private static final int ERROR_BG = Color.parseColor("#F8DFE2");
    private static final int ERROR_BORDER = Color.parseColor("#F2938C");
    private static final int INFO_BG = Color.parseColor("#E7F6F8");
    private static final int INFO_INK = Color.parseColor("#3D4551");
    private static final int WARNING_BG = Color.parseColor("#FFF5C2");
    private static final int WARNING_BORDER = Color.parseColor("#FFBE2E");
    private static final int BADGE_INSTALL_BG = Color.parseColor("#ECF3EC");
    private static final int BADGE_AVAILABLE_BG = Color.parseColor("#FFF5C2");
    private static final int BADGE_AVAILABLE_TEXT = Color.parseColor("#7D5F00");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AppStore store;
    private ApiClient apiClient;
    private ApkDownloadStore downloadStore;
    private LinearLayout root;
    private LinearLayout body;
    private boolean bodyAttached;
    private LinearLayout currentList;
    private Runnable backAction;
    private final List<DownloadUiBinding> downloadUiBindings = new ArrayList<>();
    private ApiClient.PolicySnapshot displayedPolicySnapshot;
    private boolean showingPolicyHome;
    private int displayedHomeActiveDownloadCount;
    private boolean downloadRefreshScheduled;
    private final Runnable downloadStatusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            downloadRefreshScheduled = false;
            refreshDownloadStatusViews();
            if (hasVisibleDownloadUi() && activeDownloadCount() > 0) {
                startDownloadStatusRefresh();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new AppStore(this);
        apiClient = new ApiClient(store.serverBaseUrl(), store.deviceAuthToken());
        downloadStore = new ApkDownloadStore(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDownloadStatusViews();
        startDownloadStatusRefresh();
    }

    @Override
    protected void onPause() {
        stopDownloadStatusRefresh();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        stopDownloadStatusRefresh();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (backAction != null) {
            backAction.run();
        } else {
            super.onBackPressed();
        }
    }

    private void handleIntent(Intent intent) {
        Uri data = intent.getData();
        if (data != null && "apkdist".equals(data.getScheme()) && "register-device".equals(data.getHost())) {
            if (store.isRegistered()) {
                showAlreadyRegistered();
                return;
            }
            String identifier = data.getQueryParameter("identifier");
            String secret = data.getQueryParameter("secret");
            String serverBaseUrl = data.getQueryParameter("server_base_url");
            if (identifier == null || secret == null || identifier.trim().isEmpty() || secret.trim().isEmpty()) {
                showRegisterBlocked("このリンクは使えません", "新しい登録リンクを管理者に依頼してください。");
            } else if (serverBaseUrl != null && !serverBaseUrl.trim().isEmpty() && !isHttpUrl(serverBaseUrl)) {
                showRegisterBlocked("このリンクは使えません", "サーバーURLが正しくありません。新しい登録リンクを管理者に依頼してください。");
            } else {
                if (serverBaseUrl != null && !serverBaseUrl.trim().isEmpty()) {
                    store.setServerBaseUrl(serverBaseUrl);
                    apiClient = new ApiClient(store.serverBaseUrl(), store.deviceAuthToken());
                }
                showRegister(identifier, secret);
            }
            return;
        }

        if (store.isRegistered()) {
            showHome(false);
        } else {
            showLinkRequired();
        }
    }

    private void showLinkRequired() {
        beginScreen();
        notice(false, "登録リンクが必要です", "管理者から受け取ったリンクまたはQRコードを開いてください。");
        secondaryButton("閉じる", v -> finish());
    }

    private void showRegister(String identifier, String secret) {
        beginScreen();
        topBar("APK Remote Companion", false, null, null);
        title("この端末を登録します");
        body("管理者にもこの名前で表示されます。");
        inputLabel("表示名 (必須)");
        EditText displayName = input("例: Pixel 8 QA-01");
        TextView tokenStatus = caption("通知トークンを準備しています...");
        Button register = primaryButton("登録する", null);
        register.setEnabled(false);

        executor.execute(() -> {
            String fcmToken = new FcmTokenProvider().obtainToken(this);
            mainHandler.post(() -> {
                tokenStatus.setText("通知トークンを取得しました");
                Runnable update = () -> register.setEnabled(displayName.getText().toString().trim().length() > 0 && !fcmToken.isEmpty());
                displayName.addTextChangedListener(simpleTextWatcher(update));
                update.run();
                register.setOnClickListener(v -> registerDevice(identifier, secret, displayName.getText().toString().trim(), fcmToken));
            });
        });
    }

    private void registerDevice(String identifier, String secret, String displayName, String fcmToken) {
        showLoading("登録しています");
        executor.execute(() -> {
            try {
                ApiClient.RegisteredDevice device = new ApiClient(store.serverBaseUrl(), "").registerDevice(identifier, secret, displayName, fcmToken);
                store.saveRegistration(device, fcmToken);
                apiClient = new ApiClient(store.serverBaseUrl(), store.deviceAuthToken());
                mainHandler.post(this::showRegistrationDone);
            } catch (ApiClient.ApiException e) {
                mainHandler.post(() -> {
                    if ("DEVICE_ALREADY_REGISTERED".equals(e.code)) {
                        showRegisterBlocked("このリンクは使えません", "新しい登録リンクを管理者に依頼してください。");
                    } else if ("REGISTRATION_TOKEN_NOT_FOUND".equals(e.code)) {
                        showRegisterBlocked("このリンクは使えません", "新しい登録リンクを管理者に依頼してください。");
                    } else {
                        showRegisterBlocked("登録できませんでした", e.getMessage());
                    }
                });
            } catch (IOException | JSONException e) {
                mainHandler.post(() -> showRegisterBlocked("登録できませんでした", "通信できる場所で再試行してください。"));
            }
        });
    }

    private void showRegistrationDone() {
        beginScreen();
        notice(true, "登録完了", "この端末はAPK配信サーバーに登録されました。");
        detailRow("表示名", store.displayName());
        detailRow("通知", "受信できます");
        detailRow("サーバー", store.serverBaseUrl());
        spacerBody(8);
        primaryButton("はじめる", v -> {
            requestPostNotifications();
            requestInstallPermissionIfNeeded();
            showHome(true);
        });
    }

    private void showAlreadyRegistered() {
        beginScreen();
        notice(false, "登録できません", "この登録リンクはすでに使用されています。");
        detailRow("状況", "この端末は既に登録済みです");
        detailRow("対処", "アプリを開いて配信状況を確認できます");
        spacerBody(8);
        primaryButton("アプリを開く", v -> showHome(false));
        secondaryButton("閉じる", v -> finish());
    }

    private void showRegisterBlocked(String heading, String message) {
        beginScreen();
        notice(false, heading, message);
        secondaryButton("閉じる", v -> finish());
    }

    private boolean isHttpUrl(String value) {
        Uri uri = Uri.parse(value.trim());
        String scheme = uri.getScheme();
        return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null && !uri.getHost().trim().isEmpty();
    }

    private void showHome(boolean forceFetch) {
        beginScreen();
        topBar("APK Remote Companion", false, "設定", v -> showSettings());
        title("確認中");
        body("配信設定を確認しています。");

        if (forceFetch || store.pendingPolicyFetch() || store.fetchedPolicyJson().isEmpty()) {
            fetchPolicy();
        } else {
            try {
                showPolicy(ApiClient.parsePolicy(store.fetchedPolicyJson()));
            } catch (JSONException e) {
                fetchPolicy();
            }
        }
    }

    private void fetchPolicy() {
        showLoading("配信設定を確認しています");
        executor.execute(() -> {
            try {
                ApiClient.PolicySnapshot snapshot = apiClient.fetchPolicy();
                store.saveFetchedPolicy(snapshot.rawJson);
                mainHandler.post(() -> showPolicy(snapshot));
            } catch (ApiClient.ApiException e) {
                mainHandler.post(() -> {
                    if ("DEVICE_AUTH_REQUIRED".equals(e.code)) {
                        showRegisterBlocked("再登録が必要です", "管理者に新しい登録リンクを依頼してください。");
                    } else {
                        showOffline();
                    }
                });
            } catch (IOException | JSONException e) {
                mainHandler.post(this::showOffline);
            }
        });
    }

    private void showPolicy(ApiClient.PolicySnapshot snapshot) {
        beginScreen();
        displayedPolicySnapshot = snapshot;
        showingPolicyHome = true;
        topBar("APK Remote Companion", false, "設定", v -> showSettings());

        List<ApiClient.PolicyEntry> force = new ArrayList<>();
        List<ApiClient.PolicyEntry> available = new ArrayList<>();
        for (ApiClient.PolicyEntry entry : snapshot.entries) {
            if (entry.isForceInstalled()) {
                force.add(entry);
            } else if (entry.isAvailable()) {
                available.add(entry);
            }
        }

        int activeDownloads = activeDownloadCount();
        displayedHomeActiveDownloadCount = activeDownloads;
        if (activeDownloads > 0) {
            title("更新中");
            body("新しい配信設定に合わせて、必要なアプリをそろえています。");
            infoBanner("ダウンロード " + activeDownloads + "件を処理しています");
            if (!force.isEmpty()) {
                primaryButton("状況を見る", v -> showRequiredActions(force));
            }
        } else if (force.isEmpty()) {
            title("最新です");
            body("必要なアプリはすべて入っています。");
        } else {
            title("やることがあります");
            body("新しい配信設定に合わせて、必要なアプリをそろえます。");
            warningBanner("必須アプリ " + force.size() + "件のインストールが必要です");
            primaryButton("対応する", v -> showRequiredActions(force));
        }

        Button availableButton = secondaryButton("入れられるアプリを見る (" + available.size() + "件)", v -> showAvailableApps(available));
        availableButton.setEnabled(!available.isEmpty());
        secondaryButton("更新", v -> {
            store.setPendingPolicyFetch(true);
            fetchPolicy();
        });
        caption("最終確認 " + snapshot.updatedAt);
    }

    private void showOffline() {
        beginScreen();
        topBar("APK Remote Companion", false, "設定", v -> showSettings());
        title("オフラインで確認できません");
        body("通信できる場所で更新してください。");
        primaryButton("更新", v -> fetchPolicy());
    }

    private void showRequiredActions(List<ApiClient.PolicyEntry> entries) {
        beginScreen();
        backAction = () -> showHome(false);
        topBar("必要な対応", true, null, null);
        title("インストールを許可してください");
        body("続行するとAndroidの確認画面が開きます。");
        for (ApiClient.PolicyEntry entry : entries) {
            String subtitle = "v" + entry.versionName + " / versionCode " + entry.versionCode;
            RowViews row = listRow("インストール", BADGE_INSTALL_BG, GREEN, entry.packageName, subtitle, v -> installEntry(entry));
            bindDownloadStatus(entry, row.container, row.subtitle, subtitle, null, null);
        }
    }

    private void showAvailableApps(List<ApiClient.PolicyEntry> entries) {
        beginScreen();
        backAction = () -> showHome(false);
        topBar("入れられるアプリ", true, null, null);
        if (entries.isEmpty()) {
            body("現在入れられる任意アプリはありません。");
            return;
        }
        body("ご自身で入れたいときに選べます。");
        for (ApiClient.PolicyEntry entry : entries) {
            String subtitle = "v" + entry.versionName;
            RowViews row = listRow("任意", BADGE_AVAILABLE_BG, BADGE_AVAILABLE_TEXT, entry.packageName, subtitle, v -> showAppDetail(entry));
            bindDownloadStatus(entry, row.container, row.subtitle, subtitle, null, null);
        }
    }

    private void showAppDetail(ApiClient.PolicyEntry entry) {
        beginScreen();
        backAction = () -> showHome(false);
        topBar("アプリの詳細", true, null, null);
        title(entry.packageName);
        body("配信バージョン v" + entry.versionName + " / versionCode " + entry.versionCode);
        Button installButton = primaryButton("インストール", v -> installEntry(entry));
        TextView status = caption("");
        bindDownloadStatus(entry, installButton, status, "", installButton, "インストール");
        secondaryButton("一覧へ戻る", v -> showHome(false));
    }

    private void installEntry(ApiClient.PolicyEntry entry) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            requestInstallPermissionIfNeeded();
            Toast.makeText(this, "不明なアプリのインストールを許可してから再試行してください", Toast.LENGTH_LONG).show();
            return;
        }

        ApkDownloadStore.PendingApkDownload activeDownload = blockingDownloadFor(entry.releaseId);
        if (activeDownload != null) {
            Toast.makeText(this, downloadStatusText(activeDownload), Toast.LENGTH_LONG).show();
            refreshDownloadStatusViews();
            return;
        }

        try {
            new ApkDownloadManager().enqueue(this, entry.packageName, entry.releaseId, entry.versionCode);
            Toast.makeText(this, "ダウンロードを開始しました", Toast.LENGTH_LONG).show();
            startDownloadStatusRefresh();
            showHome(false);
        } catch (RuntimeException e) {
            showOffline();
            Toast.makeText(this, "ダウンロードを開始できませんでした", Toast.LENGTH_LONG).show();
        }
    }

    private int activeDownloadCount() {
        return downloadStore.all().size();
    }

    private ApkDownloadStore.PendingApkDownload blockingDownloadFor(int releaseId) {
        ApkDownloadStore.PendingApkDownload download = downloadStore.findActiveForRelease(releaseId);
        return download != null && download.isBlocking() ? download : null;
    }

    private void bindDownloadStatus(
            ApiClient.PolicyEntry entry,
            View actionView,
            TextView detailView,
            String idleDetail,
            Button button,
            String idleButtonLabel
    ) {
        DownloadUiBinding binding = new DownloadUiBinding(entry.releaseId, actionView, detailView, idleDetail, button, idleButtonLabel);
        downloadUiBindings.add(binding);
        refreshDownloadStatusBinding(binding);
    }

    private void refreshDownloadStatusViews() {
        if (!downloadUiBindings.isEmpty()) {
            for (DownloadUiBinding binding : new ArrayList<>(downloadUiBindings)) {
                refreshDownloadStatusBinding(binding);
            }
        }
        int activeDownloads = activeDownloadCount();
        if (showingPolicyHome && displayedPolicySnapshot != null) {
            if (activeDownloads != displayedHomeActiveDownloadCount) {
                showPolicy(displayedPolicySnapshot);
            }
        }
        if (activeDownloads > 0 && hasVisibleDownloadUi()) {
            startDownloadStatusRefresh();
        }
    }

    private void refreshDownloadStatusBinding(DownloadUiBinding binding) {
        ApkDownloadStore.PendingApkDownload download = blockingDownloadFor(binding.releaseId);
        boolean blocked = download != null;
        binding.actionView.setEnabled(!blocked);
        binding.actionView.setAlpha(blocked ? 0.55f : 1f);
        binding.detailView.setText(blocked ? downloadStatusText(download) : binding.idleDetail);
        binding.detailView.setVisibility(!blocked && binding.idleDetail.isEmpty() ? View.GONE : View.VISIBLE);
        if (binding.button != null && binding.idleButtonLabel != null) {
            binding.button.setText(blocked ? downloadActionText(download) : binding.idleButtonLabel);
        }
    }

    private String downloadStatusText(ApkDownloadStore.PendingApkDownload download) {
        if (download.state == ApkDownloadStore.STATE_ENQUEUED) {
            return "ダウンロード待機中";
        }
        if (download.state == ApkDownloadStore.STATE_RUNNING) {
            int percent = download.progressPercent();
            return percent >= 0 ? "ダウンロード中 " + percent + "%" : "ダウンロード中";
        }
        if (download.state == ApkDownloadStore.STATE_INSTALLING) {
            return "インストール準備中";
        }
        return "ダウンロード処理中";
    }

    private String downloadActionText(ApkDownloadStore.PendingApkDownload download) {
        if (download.state == ApkDownloadStore.STATE_INSTALLING) {
            return "インストール準備中";
        }
        return "ダウンロード中";
    }

    private void startDownloadStatusRefresh() {
        if (downloadRefreshScheduled) {
            return;
        }
        downloadRefreshScheduled = true;
        mainHandler.postDelayed(downloadStatusRefreshRunnable, 1000);
    }

    private void stopDownloadStatusRefresh() {
        downloadRefreshScheduled = false;
        mainHandler.removeCallbacks(downloadStatusRefreshRunnable);
    }

    private boolean hasVisibleDownloadUi() {
        return showingPolicyHome || !downloadUiBindings.isEmpty();
    }

    private void showSettings() {
        beginScreen();
        backAction = () -> showHome(false);
        topBar("設定", true, null, null);
        listRow(null, 0, 0, "表示名", store.displayName(), v -> Toast.makeText(this, "表示名変更APIは未定義です", Toast.LENGTH_SHORT).show());
        listRow(null, 0, 0, "サーバー", store.serverBaseUrl(), v -> showServerEditor());
        listRow(null, 0, 0, "このアプリについて", "バージョン 1.0.0", v -> { });
    }

    private void showServerEditor() {
        beginScreen();
        backAction = this::showSettings;
        topBar("サーバー", true, null, null);
        inputLabel("Server URL");
        EditText input = input(store.serverBaseUrl());
        primaryButton("保存", v -> {
            store.setServerBaseUrl(input.getText().toString());
            apiClient = new ApiClient(store.serverBaseUrl(), store.deviceAuthToken());
            showSettings();
        });
    }

    private void requestPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private void requestInstallPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void showLoading(String message) {
        beginScreen();
        topBar("APK Remote Companion", false, null, null);
        title(message);
        body("しばらくお待ちください。");
    }

    private void beginScreen() {
        downloadUiBindings.clear();
        showingPolicyHome = false;
        currentList = null;
        bodyAttached = false;
        backAction = null;
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.WHITE);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(true);
        applySystemBarInsets(scrollView);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(20), dp(18), dp(24));
        scrollView.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));
        setContentView(scrollView);
    }

    private LinearLayout body() {
        if (!bodyAttached) {
            root.addView(body, matchWrap());
            bodyAttached = true;
        }
        return body;
    }

    private void topBar(String titleText, boolean showBack, String actionLabel, View.OnClickListener listener) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        bar.setPadding(dp(showBack ? 8 : 16), dp(10), dp(12), dp(10));

        if (showBack) {
            TextView backIcon = new TextView(this);
            backIcon.setText("←");
            backIcon.setTextSize(22);
            backIcon.setTextColor(INK);
            backIcon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            backIcon.setGravity(Gravity.CENTER);
            backIcon.setPadding(dp(10), dp(4), dp(10), dp(4));
            backIcon.setOnClickListener(v -> onBack());
            backIcon.setBackground(selectableBackground());
            backIcon.setContentDescription("戻る");
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.rightMargin = dp(4);
            bar.addView(backIcon, bp);
        }

        TextView titleView = new TextView(this);
        titleView.setText(titleText);
        titleView.setTextSize(15);
        titleView.setTextColor(INK);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (actionLabel != null) {
            TextView actionView = new TextView(this);
            actionView.setText(actionLabel);
            actionView.setTextSize(14);
            actionView.setTextColor(BLUE);
            actionView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            actionView.setPadding(dp(8), dp(6), dp(8), dp(6));
            actionView.setOnClickListener(listener);
            actionView.setBackground(selectableBackground());
            bar.addView(actionView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        root.addView(bar, matchWrap());

        View border = new View(this);
        border.setBackgroundColor(LINE);
        root.addView(border, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void onBack() {
        if (backAction != null) {
            backAction.run();
        } else {
            finish();
        }
    }

    private void notice(boolean success, String heading, String message) {
        LinearLayout notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.VERTICAL);
        notice.setBackgroundColor(success ? SUCCESS_BG : ERROR_BG);
        notice.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView strong = new TextView(this);
        strong.setText(heading);
        strong.setTextSize(20);
        strong.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        strong.setTextColor(success ? GREEN : RED_DARK);
        notice.addView(strong, matchWrap());

        if (message != null && !message.isEmpty()) {
            TextView msg = new TextView(this);
            msg.setText(message);
            msg.setTextSize(14);
            msg.setTextColor(MUTED);
            msg.setLineSpacing(0, 1.45f);
            LinearLayout.LayoutParams mp = matchWrap();
            mp.topMargin = dp(6);
            notice.addView(msg, mp);
        }
        root.addView(notice, matchWrap());

        View border = new View(this);
        border.setBackgroundColor(success ? SUCCESS_BORDER : ERROR_BORDER);
        root.addView(border, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void title(String value) {
        currentList = null;
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(22);
        view.setTextColor(INK);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        body().addView(view, lp);
    }

    private void body(String value) {
        currentList = null;
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(15);
        view.setTextColor(MUTED);
        view.setLineSpacing(0, 1.55f);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(14);
        body().addView(view, lp);
    }

    private TextView caption(String value) {
        currentList = null;
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(12);
        view.setTextColor(MUTED);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(12);
        body().addView(view, lp);
        return view;
    }

    private void inputLabel(String value) {
        currentList = null;
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(13);
        view.setTextColor(MUTED);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(6);
        lp.topMargin = dp(4);
        body().addView(view, lp);
    }

    private EditText input(String hintOrValue) {
        currentList = null;
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setTextSize(16);
        editText.setTextColor(INK);
        editText.setHintTextColor(Color.parseColor("#9AA0A6"));
        editText.setBackground(outlinedBackground());
        editText.setPadding(dp(11), dp(10), dp(11), dp(10));
        if (hintOrValue != null && hintOrValue.startsWith("http")) {
            editText.setText(hintOrValue);
        } else {
            editText.setHint(hintOrValue);
        }
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(14);
        body().addView(editText, lp);
        return editText;
    }

    private Button primaryButton(String label, View.OnClickListener listener) {
        currentList = null;
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setLetterSpacing(0.0125f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.parseColor("#E5E5E5"), Color.WHITE}));
        button.setBackground(filledButtonBackground());
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(24), dp(10), dp(24), dp(10));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(8);
        body().addView(button, lp);
        return button;
    }

    private Button secondaryButton(String label, View.OnClickListener listener) {
        currentList = null;
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setLetterSpacing(0.0125f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{BLUE_DISABLED, BLUE}));
        button.setBackground(outlinedButtonBackground());
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(24), dp(10), dp(24), dp(10));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        body().addView(button, lp);
        return button;
    }

    private void infoBanner(String text) {
        banner(text, INFO_BG, BLUE);
    }

    private void warningBanner(String text) {
        banner(text, WARNING_BG, WARNING_BORDER);
    }

    private void banner(String text, int bgColor, int borderColor) {
        currentList = null;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setBackgroundColor(bgColor);

        View leftBar = new View(this);
        leftBar.setBackgroundColor(borderColor);
        container.addView(leftBar, new LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(INFO_INK);
        view.setLineSpacing(0, 1.45f);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        container.addView(view, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(12);
        body().addView(container, lp);
    }

    private RowViews listRow(String badgeText, int badgeBg, int badgeTextColor, String title, String subtitle, View.OnClickListener listener) {
        if (currentList == null) {
            currentList = new LinearLayout(this);
            currentList.setOrientation(LinearLayout.VERTICAL);
            currentList.setBackground(outlinedBackground());
            LinearLayout.LayoutParams listLp = matchWrap();
            listLp.bottomMargin = dp(12);
            body().addView(currentList, listLp);
        }
        if (currentList.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            currentList.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundColor(Color.WHITE);
        if (listener != null) {
            row.setOnClickListener(listener);
            row.setForeground(selectableBackground());
        }

        if (badgeText != null) {
            TextView badge = new TextView(this);
            badge.setText(badgeText);
            badge.setTextSize(11);
            badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            badge.setTextColor(badgeTextColor);
            badge.setBackgroundColor(badgeBg);
            badge.setPadding(dp(6), dp(4), dp(6), dp(4));
            badge.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.rightMargin = dp(10);
            bp.topMargin = dp(2);
            row.addView(badge, bp);
        }

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15);
        titleView.setTextColor(INK);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        textCol.addView(titleView, matchWrap());

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(MUTED);
        LinearLayout.LayoutParams sp = matchWrap();
        sp.topMargin = dp(2);
        textCol.addView(subtitleView, sp);

        row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        currentList.addView(row, matchWrap());
        return new RowViews(row, subtitleView);
    }

    private void detailRow(String label, String value) {
        currentList = null;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(11), 0, dp(11));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(12);
        labelView.setTextColor(MUTED);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(14);
        valueView.setTextColor(INK);
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        body().addView(row, matchWrap());

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        body().addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void spacerBody(int dpHeight) {
        View view = new View(this);
        body().addView(view, new LinearLayout.LayoutParams(1, dp(dpHeight)));
    }

    private GradientDrawable outlinedBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setStroke(dp(1), LINE);
        return drawable;
    }

    private Drawable filledButtonBackground() {
        float radius = dp(22);
        GradientDrawable enabled = new GradientDrawable();
        enabled.setColor(BLUE);
        enabled.setCornerRadius(radius);
        GradientDrawable disabled = new GradientDrawable();
        disabled.setColor(Color.parseColor("#E1E3E1"));
        disabled.setCornerRadius(radius);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        sld.addState(StateSet.WILD_CARD, enabled);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), sld, mask);
    }

    private Drawable outlinedButtonBackground() {
        float radius = dp(22);
        GradientDrawable enabled = new GradientDrawable();
        enabled.setColor(Color.WHITE);
        enabled.setStroke(dp(1), BLUE);
        enabled.setCornerRadius(radius);
        GradientDrawable disabled = new GradientDrawable();
        disabled.setColor(Color.WHITE);
        disabled.setStroke(dp(1), Color.parseColor("#E1E3E1"));
        disabled.setCornerRadius(radius);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        sld.addState(StateSet.WILD_CARD, enabled);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x1A005EA8), sld, mask);
    }

    private void applySystemBarInsets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    private android.graphics.drawable.Drawable selectableBackground() {
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        return getDrawable(outValue.resourceId);
    }

    private TextWatcher simpleTextWatcher(Runnable afterChanged) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { afterChanged.run(); }
        };
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class RowViews {
        final LinearLayout container;
        final TextView subtitle;

        RowViews(LinearLayout container, TextView subtitle) {
            this.container = container;
            this.subtitle = subtitle;
        }
    }

    private static final class DownloadUiBinding {
        final int releaseId;
        final View actionView;
        final TextView detailView;
        final String idleDetail;
        final Button button;
        final String idleButtonLabel;

        DownloadUiBinding(int releaseId, View actionView, TextView detailView, String idleDetail, Button button, String idleButtonLabel) {
            this.releaseId = releaseId;
            this.actionView = actionView;
            this.detailView = detailView;
            this.idleDetail = idleDetail;
            this.button = button;
            this.idleButtonLabel = idleButtonLabel;
        }
    }
}
