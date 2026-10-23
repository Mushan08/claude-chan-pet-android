package com.coldcoffee.claudechan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Optional auto-start after boot / app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Prefs prefs = new Prefs(context);
        if (!prefs.autoStart() || !Settings.canDrawOverlays(context)) return;

        Intent service = new Intent(context, PetService.class).setAction(PetService.ACTION_START);
        try {
            context.startForegroundService(service);
        } catch (Exception ignored) {
            // Some OEMs block background FGS starts; the user can still open the app.
        }
    }
}
