package com.coldcoffee.claudechan;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Floating pet window.
 *
 * The window is moved by this service (drag / throw / wander); the WebView only
 * draws the sprite, bubble and squash animation. Keeping the window position in
 * native code avoids the coordinate jumps you get when a WebView is moved while
 * it is also receiving touch events.
 */
public class PetService extends Service {
    public static final String ACTION_START = "com.coldcoffee.claudechan.START";
    public static final String ACTION_STOP = "com.coldcoffee.claudechan.STOP";
    public static final String ACTION_PLAY = "com.coldcoffee.claudechan.PLAY";
    public static final String ACTION_REFRESH = "com.coldcoffee.claudechan.REFRESH";
    public static final String ACTION_REPOSITION = "com.coldcoffee.claudechan.REPOSITION";

    public static volatile boolean isRunning = false;

    private static final String TAG = "ClaudeChanPet";
    private static final String CHANNEL = "pet_overlay";
    private static final int NOTIF_ID = 0x51;
    private static final float GRAVITY_DP = 1400f;
    private static final float RESTITUTION = 0.78f;
    private static final float GROUND_FRICTION = 2.5f;
    private static final float WALK_SPEED_DP = 90f;
    private static final float MAX_THROW_DP = 2600f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private WindowManager wm;
    private WebView webView;
    private FrameLayout root;
    private WindowManager.LayoutParams params;

    private float density;
    private int screenW;
    private int screenH;
    private int workLeft;
    private int workRight;
    private int workTop;
    private int workBottom;
    private int winW;
    private int winH;
    private int petH;

    private float posX;
    private float posY;
    private float velX;
    private float velY;
    private int facing = 1;

    private boolean overlayAdded;
    private boolean pageReady;
    private boolean throwing;
    private boolean dragging;
    private boolean walking;
    private boolean menuShowing;
    private String pendingClip;

    private long lastTick;
    private float walkTarget;
    private int walkDir = 1;

    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private float downPosX;
    private float downPosY;
    private boolean moved;
    private boolean longFired;
    private int touchSlop;
    private VelocityTracker velocityTracker;
    private Runnable longPressRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        prefs = new Prefs(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        createNotificationChannel();
        updateMetrics();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        ensureForeground();
        if (!Settings.canDrawOverlays(this)) {
            toast("还没有悬浮窗权限，先在设置里打开");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PLAY.equals(action) && intent != null) {
            pendingClip = intent.getStringExtra("clip");
        }

        if (!overlayAdded) {
            buildOverlay();
        } else if (ACTION_REFRESH.equals(action)) {
            refreshPrefs();
        } else if (ACTION_REPOSITION.equals(action)) {
            moveToDefault();
        }

        if (pendingClip != null && pageReady) {
            callJs("playClip", pendingClip);
            pendingClip = null;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        handler.post(() -> {
            if (!overlayAdded) return;
            Log.d(TAG, "configuration changed: " + newConfig.orientation);
            int oldWorkLeft = workLeft;
            int oldWorkRight = workRight;
            int oldWorkTop = workTop;
            int oldWorkBottom = workBottom;
            int oldWinW = winW;
            int oldWinH = winH;
            boolean wasOnFloor = Math.abs(posY - (oldWorkBottom - oldWinH)) < dp(4);
            float oldMaxX = Math.max(1, oldWorkRight - oldWorkLeft - oldWinW);
            float oldMaxY = Math.max(1, oldWorkBottom - oldWinH - oldWorkTop);
            float fx = (posX - oldWorkLeft) / oldMaxX;
            float fy = (posY - oldWorkTop) / oldMaxY;

            updateMetrics();
            computeSize();
            params.width = winW;
            params.height = winH;
            int maxX = Math.max(0, workRight - workLeft - winW);
            int maxY = Math.max(workTop, workBottom - winH);
            posX = workLeft + Math.max(0f, Math.min(1f, fx)) * maxX;
            if (wasOnFloor) {
                posY = maxY;
            } else {
                posY = workTop + Math.max(0f, Math.min(1f, fy)) * Math.max(0, maxY - workTop);
            }
            clampPosition();
            applyPosition();
            callJs("setHeight", prefs.petHeightDp());
        });
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("AndroidPet");
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Exception ignored) {
                // WebView teardown is best-effort.
            }
            webView = null;
        }
        if (overlayAdded && root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
                // Already detached.
            }
            overlayAdded = false;
        }
        stopForeground(true);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ window

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void buildOverlay() {
        updateMetrics();
        computeSize();
        restorePosition();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLongClickable(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setFocusable(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new Bridge(), "AndroidPet");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageReady = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "page finished " + url);
            }
        });
        webView.setOnTouchListener(this::onPetTouch);
        webView.loadUrl("file:///android_asset/pet/index.html");
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        params = new WindowManager.LayoutParams(
                winW,
                winH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.round(posX);
        params.y = Math.round(posY);
        if (Build.VERSION.SDK_INT >= 30) {
            // Do not let the system inset the overlay for status/navigation bars;
            // we already clamp against workTop/workBottom ourselves.
            params.setFitInsetsTypes(0);
        }

        try {
            wm.addView(root, params);
            overlayAdded = true;
            Log.d(TAG, String.format(Locale.US, "overlay %dx%d at %d,%d", winW, winH, params.x, params.y));
        } catch (Exception err) {
            Log.e(TAG, "addView failed", err);
            toast("悬浮窗创建失败：" + err.getMessage());
            stopSelf();
        }
    }

    private void updateMetrics() {
        density = getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowMetrics metrics = wm.getCurrentWindowMetrics();
                Rect bounds = metrics.getBounds();
                screenW = bounds.width();
                screenH = bounds.height();
                WindowInsets insets = metrics.getWindowInsets();
                workLeft = insets.getSystemWindowInsetLeft();
                workRight = screenW - insets.getSystemWindowInsetRight();
                workTop = insets.getSystemWindowInsetTop();
                workBottom = screenH - insets.getSystemWindowInsetBottom();
            } catch (Exception err) {
                Log.w(TAG, "WindowMetrics unavailable, falling back: " + err.getMessage());
                updateMetricsLegacy();
            }
        } else {
            updateMetricsLegacy();
        }
        if (workTop < 0) workTop = 0;
        if (workLeft < 0) workLeft = 0;
        if (workRight <= workLeft + dp(120)) {
            workLeft = 0;
            workRight = screenW;
        }
        if (workBottom <= workTop + dp(120)) workBottom = screenH;
    }

    private void updateMetricsLegacy() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        workLeft = 0;
        workRight = screenW;
        workTop = systemBarHeight("status_bar_height");
        workBottom = screenH - systemBarHeight("navigation_bar_height");
        if (screenW > screenH) {
            // In landscape the navigation bar usually sits on the right edge.
            workRight = screenW - systemBarHeight("navigation_bar_height");
        }
    }

    private int systemBarHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void computeSize() {
        petH = dp(prefs.petHeightDp());
        // widest clip is "read" at 695x480.
        int clipW = Math.round(petH * 1.447f) + dp(8);
        int petW = Math.round(petH * 392f / 457f);
        winW = Math.max(petW + dp(12), clipW);
        winH = petH + dp(80);
        winW = Math.min(winW, Math.max(dp(120), workRight - workLeft - dp(6)));
        winH = Math.min(winH, Math.max(dp(160), workBottom - workTop - dp(6)));
    }

    private void restorePosition() {
        int maxX = Math.max(0, workRight - workLeft - winW);
        int minY = workTop;
        int maxY = Math.max(minY, workBottom - winH);
        posX = workLeft + prefs.posX() * maxX;
        posY = minY + prefs.posY() * (maxY - minY);
        clampPosition();
    }

    private void savePosition() {
        int maxX = Math.max(1, workRight - workLeft - winW);
        int minY = workTop;
        int maxY = Math.max(minY + 1, workBottom - winH);
        prefs.setPos(
                Math.max(0f, Math.min(1f, (posX - workLeft) / maxX)),
                Math.max(0f, Math.min(1f, (posY - minY) / (maxY - minY))));
    }

    private void moveToDefault() {
        posX = workRight - winW - dp(2);
        posY = workBottom - winH;
        clampPosition();
        applyPosition();
        savePosition();
    }

    private void clampPosition() {
        int maxX = Math.max(workLeft, workRight - winW);
        int minY = workTop;
        int maxY = Math.max(minY, workBottom - winH);
        posX = Math.max(workLeft, Math.min(maxX, posX));
        posY = Math.max(minY, Math.min(maxY, posY));
    }

    private void applyPosition() {
        if (!overlayAdded || params == null || root == null) return;
        params.x = Math.round(posX);
        params.y = Math.round(posY);
        try {
            wm.updateViewLayout(root, params);
        } catch (Exception err) {
            Log.w(TAG, "updateViewLayout failed: " + err.getMessage());
        }
    }

    private void refreshPrefs() {
        int newH = dp(prefs.petHeightDp());
        callJs("setPrefs", configJson());
        if (newH != petH && overlayAdded) {
            int bottom = params.y + params.height;
            computeSize();
            params.width = winW;
            params.height = winH;
            posY = bottom - winH;
            clampPosition();
            applyPosition();
            callJs("setHeight", prefs.petHeightDp());
        }
        scheduleWander();
    }

    // ------------------------------------------------------------------ touch

    private boolean onPetTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelLongPress();
                cancelWalk();
                throwing = false;
                handler.removeCallbacks(physicsTick);

                downRawX = lastRawX = event.getRawX();
                downRawY = lastRawY = event.getRawY();
                downPosX = posX;
                downPosY = posY;
                moved = false;
                longFired = false;
                dragging = true;

                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                callJs("dragStart");
                scheduleLongPress();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(event);
                if (longFired) return true;

                float rawX = event.getRawX();
                float rawY = event.getRawY();
                if (!moved && Math.hypot(rawX - downRawX, rawY - downRawY) > touchSlop) {
                    moved = true;
                    cancelLongPress();
                }
                if (moved) {
                    float stepX = rawX - lastRawX;
                    float stepY = rawY - lastRawY;
                    lastRawX = rawX;
                    lastRawY = rawY;
                    posX = downPosX + (rawX - downRawX);
                    posY = downPosY + (rawY - downRawY);
                    clampPosition();
                    applyPosition();
                    callJs("drag", stepX / density, stepY / density);
                }
                return true;

            case MotionEvent.ACTION_UP:
                cancelLongPress();
                float vx = 0f;
                float vy = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000, MAX_THROW_DP * density);
                    vx = velocityTracker.getXVelocity();
                    vy = velocityTracker.getYVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                dragging = false;
                if (!moved && !longFired) {
                    callJs("tap");
                } else if (!longFired) {
                    callJs("dragEnd", vx / density, vy / density);
                    startThrow(vx, vy);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelLongPress();
                dragging = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                callJs("dragEnd", 0f, 0f);
                return true;

            default:
                return true;
        }
    }

    private void scheduleLongPress() {
        cancelLongPress();
        longPressRunnable = () -> {
            if (!moved && !menuShowing) {
                longFired = true;
                dragging = false;
                callJs("dragEnd", 0f, 0f);
                showMenu();
            }
        };
        handler.postDelayed(longPressRunnable, 520);
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    // ------------------------------------------------------------------ physics

    private void startThrow(float rawVx, float rawVy) {
        float cap = MAX_THROW_DP * density;
        float vx = Math.max(-cap, Math.min(cap, rawVx));
        float vy = Math.max(-cap, Math.min(cap, rawVy));
        float speedDp = (float) Math.hypot(vx, vy) / density;

        if (speedDp < 140f) {
            callJs("motion", "land", facing, 0f, vy / density);
            savePosition();
            scheduleWander();
            return;
        }

        velX = vx;
        velY = vy;
        facing = vx >= 0 ? 1 : -1;
        throwing = true;
        lastTick = SystemClock.uptimeMillis();
        callJs("motion", "throw", facing, velX / density, velY / density);
        handler.removeCallbacks(physicsTick);
        handler.post(physicsTick);
    }

    private final Runnable physicsTick = new Runnable() {
        @Override
        public void run() {
            if (!throwing) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min((now - lastTick) / 1000f, 0.05f);
            lastTick = now;

            velY += GRAVITY_DP * density * dt;
            posX += velX * dt;
            posY += velY * dt;

            int maxX = Math.max(workLeft, workRight - winW);
            int floor = workBottom - winH;
            int ceiling = workTop;

            if (posX < workLeft) {
                posX = workLeft;
                velX = Math.abs(velX) * RESTITUTION;
                facing = 1;
            } else if (posX > maxX) {
                posX = maxX;
                velX = -Math.abs(velX) * RESTITUTION;
                facing = -1;
            }
            if (posY < ceiling) {
                posY = ceiling;
                velY = Math.abs(velY) * RESTITUTION;
            }
            boolean onGround = posY >= floor - 0.5f;
            if (posY > floor) {
                posY = floor;
                if (Math.abs(velY) > 90f * density) {
                    velY = -Math.abs(velY) * RESTITUTION;
                } else {
                    velY = 0;
                }
            }
            if (onGround) {
                velX *= Math.exp(-GROUND_FRICTION * dt);
            }

            applyPosition();
            callJs("motion", "throw", facing, velX / density, velY / density);

            if (onGround && Math.abs(velX) < 20f * density && Math.abs(velY) < 90f * density) {
                throwing = false;
                velX = 0;
                velY = 0;
                savePosition();
                callJs("motion", "land", facing, 0f, 0f);
                scheduleWander();
                return;
            }
            handler.postDelayed(this, 16);
        }
    };

    private void scheduleWander() {
        handler.removeCallbacks(wanderRunnable);
        if (!prefs.wander() || !overlayAdded) return;
        long delay = 20000L + (long) (Math.random() * 40000L);
        handler.postDelayed(wanderRunnable, delay);
    }

    private final Runnable wanderRunnable = new Runnable() {
        @Override
        public void run() {
            if (!prefs.wander() || dragging || throwing || !overlayAdded) {
                scheduleWander();
                return;
            }
            startWalk();
        }
    };

    private void startWalk() {
        if (dragging || throwing || walking) return;
        int minX = workLeft + dp(4);
        int maxX = Math.max(minX, workRight - winW - dp(4));
        int dist = dp(50 + (int) (Math.random() * 70));
        int dir = Math.random() < 0.5 ? -1 : 1;
        float target = posX + dir * dist;
        if (target < minX || target > maxX) {
            target = posX - dir * dist;
        }
        target = Math.max(minX, Math.min(maxX, target));
        if (Math.abs(target - posX) < dp(12)) {
            scheduleWander();
            return;
        }
        walkTarget = target;
        walkDir = target > posX ? 1 : -1;
        facing = walkDir;
        walking = true;
        lastTick = SystemClock.uptimeMillis();
        handler.post(walkTick);
    }

    private void cancelWalk() {
        if (!walking) return;
        walking = false;
        handler.removeCallbacks(walkTick);
        savePosition();
        scheduleWander();
    }

    private final Runnable walkTick = new Runnable() {
        @Override
        public void run() {
            if (!walking || dragging || throwing) {
                walking = false;
                scheduleWander();
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = Math.min((now - lastTick) / 1000f, 0.05f);
            lastTick = now;
            posX += WALK_SPEED_DP * density * walkDir * dt;

            boolean arrived = (walkDir > 0 && posX >= walkTarget) || (walkDir < 0 && posX <= walkTarget);
            if (arrived) {
                posX = walkTarget;
                walking = false;
                facing = 1;
                clampPosition();
                applyPosition();
                savePosition();
                callJs("motion", "walk", facing, 0f, 0f);
                scheduleWander();
                return;
            }
            clampPosition();
            applyPosition();
            callJs("motion", "walk", walkDir, WALK_SPEED_DP, 0f);
            handler.postDelayed(this, 16);
        }
    };

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        if (menuShowing) return;
        menuShowing = true;
        String[] items = {"碎碎念一句", "换个表情", "动作", "回到右下角", "设置", "退出桌宠"};
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PetDialog)
                .setTitle("Claude 娘")
                .setItems(items, (d, which) -> {
                    d.dismiss();
                    handler.postDelayed(() -> handleMenu(which), 150);
                })
                .setOnDismissListener(d -> menuShowing = false)
                .create();
        overlayDialogWindow(dialog);
        dialog.show();
    }

    private void handleMenu(int which) {
        switch (which) {
            case 0:
                callJs("whisper");
                break;
            case 1:
                showFaceMenu();
                break;
            case 2:
                showClipMenu();
                break;
            case 3:
                moveToDefault();
                break;
            case 4:
                openSettings();
                break;
            case 5:
                stopSelf();
                break;
            default:
                break;
        }
    }

    private void showFaceMenu() {
        String[] labels = {"开心", "害羞", "生气", "惊讶", "委屈", "难过", "疑惑", "害羞·笑", "睡觉", "小Claude"};
        String[] items = new String[labels.length];
        for (int i = 0; i < labels.length; i++) {
            items[i] = "表情 " + (i + 1) + " · " + labels[i];
        }
        menuShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PetDialog)
                .setTitle("换个表情")
                .setItems(items, (d, which) -> {
                    d.dismiss();
                    callJs("face", which + 1);
                })
                .setOnDismissListener(d -> menuShowing = false)
                .create();
        overlayDialogWindow(dialog);
        dialog.show();
    }

    private void showClipMenu() {
        String[] items = {"抱玩偶", "招手", "比心", "看书", "想到办法"};
        String[] clips = {"hug", "wave", "love", "read", "think"};
        menuShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.PetDialog)
                .setTitle("动作")
                .setItems(items, (d, which) -> {
                    d.dismiss();
                    callJs("playClip", clips[which]);
                })
                .setOnDismissListener(d -> menuShowing = false)
                .create();
        overlayDialogWindow(dialog);
        dialog.show();
    }

    private void overlayDialogWindow(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.28f);
    }

    private void openSettings() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ------------------------------------------------------------------ JS bridge

    private void callJs(String method, Object... args) {
        if (webView == null || !pageReady) return;
        StringBuilder sb = new StringBuilder("window.PetHost&&window.PetHost.").append(method).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            Object arg = args[i];
            if (arg instanceof Number || arg instanceof Boolean) {
                sb.append(arg);
            } else {
                sb.append(JSONObject.quote(String.valueOf(arg)));
            }
        }
        sb.append(");");
        final String js = sb.toString();
        handler.post(() -> {
            if (webView != null) {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Exception err) {
                    Log.w(TAG, "evaluateJavascript failed: " + err.getMessage());
                }
            }
        });
    }

    private String configJson() {
        return String.format(
                Locale.US,
                "{\"petHeight\":%d,\"whisper\":%b,\"idleActions\":%b,\"wander\":%b}",
                prefs.petHeightDp(),
                prefs.whisper(),
                prefs.idleActions(),
                prefs.wander());
    }

    private final class Bridge {
        @JavascriptInterface
        public String config() {
            return configJson();
        }

        @JavascriptInterface
        public void ready() {
            handler.post(() -> {
                pageReady = true;
                if (pendingClip != null) {
                    callJs("playClip", pendingClip);
                    pendingClip = null;
                }
            });
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d(TAG, message == null ? "null" : message);
        }

        @JavascriptInterface
        public void menu() {
            handler.post(PetService.this::showMenu);
        }

        @JavascriptInterface
        public void settings() {
            handler.post(PetService.this::openSettings);
        }

        @JavascriptInterface
        public void close() {
            handler.post(PetService.this::stopSelf);
        }

        @JavascriptInterface
        public void save() {
            handler.post(PetService.this::savePosition);
        }
    }

    // ------------------------------------------------------------------ notification

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(channel);
    }

    private void ensureForeground() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, PetService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pet)
                .setColor(0xFFC8703C)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(openPi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(R.drawable.ic_stat_pet, "停止", stopPi)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
