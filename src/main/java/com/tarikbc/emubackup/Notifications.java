package com.tarikbc.emubackup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * The two notification channels.
 *
 * <p>Progress is silent and ongoing; results are not. The split matters because a scheduled
 * backup runs unattended, and a failure that stays quiet is the exact situation this app exists
 * to prevent. See {@code ARCHITECTURE.md} section 8.
 */
public final class Notifications {

    public static final String CHANNEL_PROGRESS = "eb_progress";
    public static final String CHANNEL_RESULT = "eb_result";
    public static final int ID_PROGRESS = 1;
    public static final int ID_RESULT = 2;

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS,
                "Backup progress", NotificationManager.IMPORTANCE_LOW);
        progress.setShowBadge(false);
        nm.createNotificationChannel(progress);

        NotificationChannel result = new NotificationChannel(CHANNEL_RESULT,
                "Backup results", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(result);
    }

    public static Notification progress(Context ctx, String title, String text, int percent) {
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(ctx, BackupActivity.class));
        if (percent >= 0) b.setProgress(100, percent, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    public static void result(Context ctx, String title, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.notify(ID_RESULT, new Notification.Builder(ctx, CHANNEL_RESULT)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open(ctx, ShellActivity.class))
                .build());
    }

    private static PendingIntent open(Context ctx, Class<?> activity) {
        return PendingIntent.getActivity(ctx, 0, new Intent(ctx, activity),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notifications() {}
}
