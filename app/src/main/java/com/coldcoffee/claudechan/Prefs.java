package com.coldcoffee.claudechan;

import android.content.Context;
import android.content.SharedPreferences;

/** Small SharedPreferences wrapper; no AndroidX needed. */
public final class Prefs {
    private static final String NAME = "claude_chan_pet";
    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public int petHeightDp() {
        return clamp(sp.getInt("heightDp", 160), 110, 220);
    }

    public void setPetHeightDp(int value) {
        sp.edit().putInt("heightDp", clamp(value, 110, 220)).apply();
    }

    public boolean whisper() {
        return sp.getBoolean("whisper", true);
    }

    public void setWhisper(boolean value) {
        sp.edit().putBoolean("whisper", value).apply();
    }

    public boolean idleActions() {
        return sp.getBoolean("idleActions", true);
    }

    public void setIdleActions(boolean value) {
        sp.edit().putBoolean("idleActions", value).apply();
    }

    public boolean wander() {
        return sp.getBoolean("wander", false);
    }

    public void setWander(boolean value) {
        sp.edit().putBoolean("wander", value).apply();
    }

    public boolean autoStart() {
        return sp.getBoolean("autoStart", false);
    }

    public void setAutoStart(boolean value) {
        sp.edit().putBoolean("autoStart", value).apply();
    }

    public float posX() {
        return sp.getFloat("posX", 1f);
    }

    public float posY() {
        return sp.getFloat("posY", 1f);
    }

    public void setPos(float x, float y) {
        sp.edit()
                .putFloat("posX", Math.max(0f, Math.min(1f, x)))
                .putFloat("posY", Math.max(0f, Math.min(1f, y)))
                .apply();
    }
}
