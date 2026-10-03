package com.minegames.lan;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.core.content.pm.PackageInfoCompat;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private int insetTop;
    private int insetLeft;
    private int insetRight;
    private long apkDownloadId = -1;
    private Uri pendingInstall;
    private boolean downloadReceiverOn;
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != apkDownloadId) return;
            installDownloaded(id);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        applyCutout();
        super.onCreate(savedInstanceState);
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().addJavascriptInterface(new ExitBridge(), "LizhiApp");
            getBridge().getWebView().setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                enqueueApkDownload(url, mimeType, name);
            });
        }
        applyImmersive();
        applyStatusBarInset();
        keepScreenOn(true);
    }

    /** 游戏在前台时不让系统自动息屏；切到后台后恢复正常息屏。 */
    private void keepScreenOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public class ExitBridge {
        @JavascriptInterface
        public void exit() {
            runOnUiThread(() -> {
                finishAndRemoveTask();
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            });
        }

        @JavascriptInterface
        public int getVersionCode() {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                return (int) PackageInfoCompat.getLongVersionCode(info);
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public String getVersionName() {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                return info.versionName == null ? "" : info.versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void openBrowser(String url) {
            runOnUiThread(() -> openInBrowser(url));
        }

        @JavascriptInterface
        public void downloadApk(String url) {
            runOnUiThread(() -> enqueueApkDownload(url, "application/vnd.android.package-archive", "lizhi-update.apk"));
        }
    }

    private void openInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void enqueueApkDownload(String url, String mime, String name) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) {
                openInBrowser(url);
                return;
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) request.setMimeType(mime);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setTitle("荔枝娱乐");
            request.setDescription("正在下载新版本");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ensureDownloadReceiver();
            apkDownloadId = dm.enqueue(request);
            Toast.makeText(this, "开始下载，完成后将提示安装", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            openInBrowser(url);
        }
    }

    private void ensureDownloadReceiver() {
        if (downloadReceiverOn) return;
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        downloadReceiverOn = true;
    }

    private void installDownloaded(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        Cursor cursor = dm.query(query);
        try {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) return;
        } finally {
            if (cursor != null) cursor.close();
        }
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = uri;
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                startActivity(settings);
                Toast.makeText(this, "请允许安装应用，返回后会继续", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                launchInstall(uri);
            }
            return;
        }
        launchInstall(uri);
    }

    private void launchInstall(Uri uri) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception e) {
            Toast.makeText(this, "请在通知栏点开安装包完成安装", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroy() {
        if (downloadReceiverOn) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiverOn = false;
        }
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        keepScreenOn(true);
        if (pendingInstall != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls())) {
            Uri uri = pendingInstall;
            pendingInstall = null;
            launchInstall(uri);
        }
        applyImmersive();
        applyStatusBarInset();
        refitWeb();
        View content = findViewById(android.R.id.content);
        if (content != null) {
            content.postDelayed(this::refitWeb, 80);
            content.postDelayed(this::refitWeb, 360);
        }
        if (hasWindowFocus()) notifyWeb("window.__mgAppVisible&&window.__mgAppVisible()");
    }

    @Override
    public void onPause() {
        keepScreenOn(false);
        notifyWeb("window.__mgAppHidden&&window.__mgAppHidden()");
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
            refitWeb();
            notifyWeb("window.__mgAppVisible&&window.__mgAppVisible()");
        }
    }

    private void applyCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setAttributes(lp);
    }

    private void notifyWeb(String js) {
        if (getBridge() == null || getBridge().getWebView() == null) return;
        getBridge().getWebView().evaluateJavascript(js, null);
    }

    private void applyImmersive() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        applyCutout();
        View decor = window.getDecorView();
        decor.setBackgroundColor(Color.parseColor("#0A422A"));
        decor.setPadding(0, 0, 0, 0);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.navigationBars());
            controller.setAppearanceLightStatusBars(false);
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#0A422A")));
    }

    private void applyStatusBarInset() {
        View content = findViewById(android.R.id.content);
        if (content == null) return;
        content.setBackgroundColor(Color.TRANSPARENT);
        clearPaddingTree(content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            clearPaddingTree(v);
            Insets status = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            int top = Math.max(status.top, cutout.top);
            int left = Math.max(status.left, Math.max(cutout.left, nav.left));
            int right = Math.max(status.right, Math.max(cutout.right, nav.right));
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            pushInsets(top, left, right);
            pushKeyboard(ime.bottom);
            return new WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
                    .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.NONE)
                    .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.NONE)
                    .build();
        });
        ViewCompat.requestApplyInsets(content);
    }

    private void clearPaddingTree(View v) {
        if (v == null) return;
        if (v.getPaddingLeft() != 0 || v.getPaddingTop() != 0 || v.getPaddingRight() != 0 || v.getPaddingBottom() != 0) {
            v.setPadding(0, 0, 0, 0);
        }
        v.setFitsSystemWindows(false);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) clearPaddingTree(group.getChildAt(i));
        }
    }

    private int keyboardPx;

    private void pushKeyboard(int px) {
        keyboardPx = Math.max(0, px);
        notifyWeb("window.__mgKeyboardPx=" + keyboardPx
                + ";if(window.__mgKeyboard)window.__mgKeyboard(" + keyboardPx + ")");
    }

    private void pushInsets(int top, int left, int right) {
        insetTop = Math.max(0, top);
        insetLeft = Math.max(0, left);
        insetRight = Math.max(0, right);
        notifyWeb("window.__mgStatusPx=" + insetTop
                + ";window.__mgInsetL=" + insetLeft
                + ";window.__mgInsetR=" + insetRight
                + ";if(window.__mgStatusInset)window.__mgStatusInset("
                + insetTop + "," + insetLeft + "," + insetRight + ")");
    }

    private void refitWeb() {
        View decor = getWindow().getDecorView();
        decor.setPadding(0, 0, 0, 0);
        clearPaddingTree(findViewById(android.R.id.content));
        pushInsets(insetTop, insetLeft, insetRight);
        notifyWeb("window.__mgRefit&&window.__mgRefit()");
    }
}
