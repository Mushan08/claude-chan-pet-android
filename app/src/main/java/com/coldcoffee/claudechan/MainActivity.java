package com.coldcoffee.claudechan;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Permission onboarding + settings. Deliberately programmatic: no AndroidX,
 * no resource layouts, so the whole app builds with plain SDK tools.
 */
public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 7;

    private static final int PAPER = 0xFFF6F3ED;
    private static final int CARD = 0xFFFFFDF9;
    private static final int INK = 0xFF2B2724;
    private static final int MUTED = 0xFF7A716A;
    private static final int COPPER = 0xFFC8703C;
    private static final int COPPER_SOFT = 0xFFE0A179;
    private static final int OK_GREEN = 0xFF3F8E5A;

    private Prefs prefs;
    private TextView statusText;
    private Button startButton;
    private PermRow overlayRow;
    private PermRow notifRow;
    private PermRow batteryRow;
    private SeekBar sizeBar;
    private TextView sizeLabel;
    private CheckBox wanderBox;
    private CheckBox whisperBox;
    private CheckBox idleBox;
    private CheckBox autoStartBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        buildUi();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshUi();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshUi();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(82), dp(82));
        header.addView(icon, iconLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.leftMargin = dp(14);
        header.addView(titles, titlesLp);
        TextView title = text("Claude 娘桌宠", 25, INK, true);
        titles.addView(title);
        TextView subtitle = text("Android 悬浮窗版 · 橙发大小姐", 12.5f, MUTED, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);
        root.addView(header);

        // status card
        LinearLayout statusCard = card();
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(18);
        root.addView(statusCard, statusLp);
        statusText = text("桌宠未启动", 17, INK, true);
        statusCard.addView(statusText);
        TextView statusHint = text("悬浮在所有应用上面；拖动、甩出去、点一下都会回应。", 12, MUTED, false);
        statusHint.setPadding(0, dp(4), 0, 0);
        statusCard.addView(statusHint);

        startButton = primaryButton("启动桌宠");
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        startLp.topMargin = dp(12);
        root.addView(startButton, startLp);
        startButton.setOnClickListener(v -> togglePet());

        // permissions
        root.addView(section("权限"));
        LinearLayout permCard = card();
        root.addView(permCard);

        overlayRow = permissionRow(
                permCard,
                "悬浮窗权限",
                "必须。没有它，桌宠无法出现在其他应用上方。",
                "去授权",
                v -> openOverlaySettings());
        divider(permCard);
        notifRow = permissionRow(
                permCard,
                "通知权限",
                "Android 13+ 需要，用来显示常驻通知和“停止”按钮。",
                "去授权",
                v -> requestNotifications());
        divider(permCard);
        batteryRow = permissionRow(
                permCard,
                "电池优化白名单",
                "建议开启。否则部分系统会在后台把桌宠回收。",
                "去设置",
                v -> openBatterySettings());

        // behavior
        root.addView(section("外观与行为"));
        LinearLayout behaviorCard = card();
        root.addView(behaviorCard);

        LinearLayout sizeHeader = new LinearLayout(this);
        sizeHeader.setOrientation(LinearLayout.HORIZONTAL);
        sizeHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeTitle = text("立绘高度", 14, INK, false);
        sizeHeader.addView(sizeTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sizeLabel = text(String.format(Locale.US, "%d dp", prefs.petHeightDp()), 13, COPPER, true);
        sizeHeader.addView(sizeLabel);
        behaviorCard.addView(sizeHeader);

        sizeBar = new SeekBar(this);
        sizeBar.setMax(110);
        sizeBar.setProgress(prefs.petHeightDp() - 110);
        sizeBar.setProgressTintList(ColorStateList.valueOf(COPPER));
        sizeBar.setThumbTintList(ColorStateList.valueOf(COPPER));
        sizeBar.setPadding(0, dp(4), 0, dp(4));
        behaviorCard.addView(sizeBar);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizeLabel.setText(String.format(Locale.US, "%d dp", progress + 110));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.setPetHeightDp(seekBar.getProgress() + 110);
                refreshPet();
            }
        });

        whisperBox = checkBox("碎碎念（20~30 秒一句）", prefs.whisper(), (buttonView, isChecked) -> {
            prefs.setWhisper(isChecked);
            refreshPet();
        });
        behaviorCard.addView(whisperBox);

        idleBox = checkBox("空闲随机动作（12~15 秒一段）", prefs.idleActions(), (buttonView, isChecked) -> {
            prefs.setIdleActions(isChecked);
            refreshPet();
        });
        behaviorCard.addView(idleBox);

        wanderBox = checkBox("自己溜达（20~60 秒走一段）", prefs.wander(), (buttonView, isChecked) -> {
            prefs.setWander(isChecked);
            refreshPet();
        });
        behaviorCard.addView(wanderBox);

        autoStartBox = checkBox("开机自启（登录后自动出现）", prefs.autoStart(), (buttonView, isChecked) -> {
            prefs.setAutoStart(isChecked);
        });
        behaviorCard.addView(autoStartBox);

        // actions
        root.addView(section("动作测试"));
        LinearLayout actionCard = card();
        root.addView(actionCard);
        TextView actionHint = text("桌宠没在跑时会先启动，再播这一段。", 12, MUTED, false);
        actionHint.setPadding(0, 0, 0, dp(8));
        actionCard.addView(actionHint);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        String[] labels = {"抱玩偶", "招手", "比心", "看书", "想到办法"};
        String[] clips = {"hug", "wave", "love", "read", "think"};
        for (int i = 0; i < labels.length; i++) {
            Button button = smallButton(labels[i]);
            final String clip = clips[i];
            button.setOnClickListener(v -> playClip(clip));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (i == labels.length - 1) {
                lp.columnSpec = GridLayout.spec(0, 2, 1f);
                lp.rowSpec = GridLayout.spec(2);
            } else {
                lp.columnSpec = GridLayout.spec(i % 2, 1, 1f);
                lp.rowSpec = GridLayout.spec(i / 2);
            }
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(button, lp);
        }
        actionCard.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // about
        root.addView(section("关于"));
        LinearLayout aboutCard = card();
        root.addView(aboutCard);
        TextView about = text(
                "Android 版是独立桌宠：不读取 Claude Code hooks。\n"
                        + "电脑端的事件联动可以后续通过局域网接到手机上。\n\n"
                        + "v0.1.0 · MIT · 素材来自你自己的三视图设计稿",
                12.5f,
                MUTED,
                false);
        about.setLineSpacing(0, 1.25f);
        aboutCard.addView(about);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(CARD, dp(16), dp(1), 0x33C8703C));
        card.setElevation(dp(1));
        return card;
    }

    private TextView section(String label) {
        TextView view = text(label, 12, COPPER, true);
        view.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(8);
        view.setLayoutParams(lp);
        return view;
    }

    private PermRow permissionRow(
            LinearLayout parent,
            String title,
            String description,
            String actionLabel,
            View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 14, INK, true);
        top.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = text("", 12, MUTED, false);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateLp.rightMargin = dp(8);
        top.addView(state, stateLp);
        Button action = smallButton(actionLabel);
        action.setOnClickListener(listener);
        top.addView(action);
        row.addView(top);

        TextView desc = text(description, 11.5f, MUTED, false);
        desc.setPadding(0, dp(5), 0, 0);
        row.addView(desc);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new PermRow(state, action);
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(0x142B2724);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        parent.addView(line, lp);
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(COPPER, dp(14), 0, 0));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(48));
        button.setStateListAnimator(null);
        button.setElevation(dp(2));
        return button;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTextColor(INK);
        button.setBackground(rounded(CARD, dp(10), dp(1), COPPER_SOFT));
        button.setPadding(dp(10), dp(3), dp(10), dp(3));
        button.setMinHeight(dp(36));
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        return button;
    }

    private CheckBox checkBox(String label, boolean checked, android.widget.CompoundButton.OnCheckedChangeListener listener) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        box.setTextColor(INK);
        box.setChecked(checked);
        box.setButtonTintList(ColorStateList.valueOf(COPPER));
        box.setPadding(0, dp(5), 0, dp(5));
        box.setOnCheckedChangeListener(listener);
        return box;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private GradientDrawable rounded(int fill, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ state

    private void refreshUi() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notifications = hasNotificationPermission();
        boolean battery = isIgnoringBatteryOptimizations();
        boolean running = PetService.isRunning;

        statusText.setText(running ? "正在桌面上陪你" : "桌宠未启动");
        startButton.setText(running ? "停止桌宠" : "启动桌宠");
        startButton.setTextColor(running ? COPPER : Color.WHITE);
        startButton.setBackground(running
                ? rounded(PAPER, dp(14), dp(1), COPPER)
                : rounded(COPPER, dp(14), 0, 0));

        overlayRow.state.setText(overlay ? "已授权" : "未授权");
        overlayRow.state.setTextColor(overlay ? OK_GREEN : COPPER);
        overlayRow.action.setText(overlay ? "已开启" : "去授权");
        overlayRow.action.setEnabled(!overlay);
        overlayRow.action.setAlpha(overlay ? 0.55f : 1f);

        if (Build.VERSION.SDK_INT < 33) {
            notifRow.state.setText("系统无需授权");
            notifRow.state.setTextColor(OK_GREEN);
            notifRow.action.setEnabled(false);
            notifRow.action.setAlpha(0.55f);
        } else {
            notifRow.state.setText(notifications ? "已授权" : "未授权");
            notifRow.state.setTextColor(notifications ? OK_GREEN : COPPER);
            notifRow.action.setText(notifications ? "已开启" : "去授权");
            notifRow.action.setEnabled(!notifications);
            notifRow.action.setAlpha(notifications ? 0.55f : 1f);
        }

        batteryRow.state.setText(battery ? "已忽略" : "未设置");
        batteryRow.state.setTextColor(battery ? OK_GREEN : MUTED);
        batteryRow.action.setText(battery ? "已设置" : "去设置");
        batteryRow.action.setEnabled(!battery);
        batteryRow.action.setAlpha(battery ? 0.55f : 1f);
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // ------------------------------------------------------------------ actions

    private void togglePet() {
        if (PetService.isRunning) {
            stopPet();
        } else {
            startPet();
        }
    }

    private void startPet() {
        if (!Settings.canDrawOverlays(this)) {
            toast("先授予悬浮窗权限");
            openOverlaySettings();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        Intent intent = new Intent(this, PetService.class).setAction(PetService.ACTION_START);
        startForegroundService(intent);
        startButton.postDelayed(this::refreshUi, 600);
    }

    private void stopPet() {
        startService(new Intent(this, PetService.class).setAction(PetService.ACTION_STOP));
        startButton.postDelayed(this::refreshUi, 450);
    }

    private void refreshPet() {
        if (!PetService.isRunning) return;
        startService(new Intent(this, PetService.class).setAction(PetService.ACTION_REFRESH));
    }

    private void playClip(String clip) {
        if (!Settings.canDrawOverlays(this)) {
            toast("先授予悬浮窗权限");
            openOverlaySettings();
            return;
        }
        Intent intent = new Intent(this, PetService.class);
        intent.setAction(PetService.isRunning ? PetService.ACTION_PLAY : PetService.ACTION_START);
        intent.putExtra("clip", clip);
        startForegroundService(intent);
        startButton.postDelayed(this::refreshUi, 700);
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException err) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (ActivityNotFoundException ignored) {
                toast("这个系统没有悬浮窗设置页");
            }
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (ActivityNotFoundException err) {
            toast("这个系统没有通知设置页");
        }
    }

    @SuppressLint("BatteryLife")
    private void openBatterySettings() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException err) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                toast("这个系统没有电池优化设置页");
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class PermRow {
        final TextView state;
        final Button action;

        PermRow(TextView state, Button action) {
            this.state = state;
            this.action = action;
        }
    }
}
