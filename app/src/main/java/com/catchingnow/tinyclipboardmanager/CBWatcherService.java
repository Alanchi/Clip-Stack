package com.catchingnow.tinyclipboardmanager;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ClipboardManager.OnPrimaryClipChangedListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

public class CBWatcherService extends Service {

    private final static String PACKAGE_NAME = "com.catchingnow.tinyclipboardmanager";
    public final static String INTENT_EXTRA_FORCE_SHOW_NOTIFICATION = "com.catchingnow.tinyclipboardmanager.EXTRA.FORCE_SHOW_NOTIFICATION";
    public final static String INTENT_EXTRA_MY_ACTIVITY_ON_FORGROUND_MESSAGE = "com.catchingnow.tinyclipboardmanager.EXTRA.MY_ACTIVITY_ON_FOREGROUND_MESSAGE";
    public final static int JOB_ID = 1;
    public int NUMBER_OF_CLIPS = 6; //3-9
    private NotificationManager notificationManager;
    private Storage db;
    private boolean onListened = false;
    private int isMyActivitiesOnForeground = 0;
    private OnPrimaryClipChangedListener listener = new OnPrimaryClipChangedListener() {
        public void onPrimaryClipChanged() {
            performClipboardCheck();
        }
    };


    @Override
    public void onCreate() {
        Log.v(PACKAGE_NAME, "onCreate");
        if (!onListened) {
            db = new Storage(this.getBaseContext());
            bindJobScheduler();
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).addPrimaryClipChangedListener(listener);
            onListened = true;
        }
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(PACKAGE_NAME, "onStartCommand");
        if (intent != null) {
            Log.v(PACKAGE_NAME, "IIOOV:"+isMyActivitiesOnForeground);
            if (intent.getBooleanExtra(INTENT_EXTRA_FORCE_SHOW_NOTIFICATION, false)) {
                showNotification();
            }

            int myActivitiesOnForegroundMessage = intent.getIntExtra(INTENT_EXTRA_MY_ACTIVITY_ON_FORGROUND_MESSAGE, 0);
            isMyActivitiesOnForeground += myActivitiesOnForegroundMessage;

            SharedPreferences preference = PreferenceManager.getDefaultSharedPreferences(this);
            if (!preference.getBoolean(ActivitySetting.SERVICE_STATUS, true)) {
                if (isMyActivitiesOnForeground <= 0) {
                    Log.v(PACKAGE_NAME, "IIOOW:"+isMyActivitiesOnForeground);
                    stopSelf();
                    isMyActivitiesOnForeground = 0;
                    return Service.START_NOT_STICKY;
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(PACKAGE_NAME, "onBind");
        return null;
    }

    @Override
    public void onDestroy() {
        Log.v(PACKAGE_NAME, "onDes");
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(0);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).removePrimaryClipChangedListener(listener);
        onListened = false;
        super.onDestroy();
    }

    public void bindJobScheduler() {
        // JobScheduler for auto clean sqlite
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(this, SyncJobService.class))
                .setRequiresCharging(true)
                .setPeriodic(480000)
                .setPersisted(true)
                .build();
        JobScheduler jobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        jobScheduler.schedule(job);
    }

    private void performClipboardCheck() {
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb.hasPrimaryClip()) {
            ClipData cd = cb.getPrimaryClip();
            if (cd.getDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                CharSequence thisClip = cd.getItemAt(0).getText();
                if (thisClip != null) {
                    db.modifyClip(null, thisClip.toString());
                }
            }
        }
    }

    public List<ClipObject> getClips() {
        if (db == null) {
            db = new Storage(this.getBaseContext());
        }
        return db.getClipHistory(NUMBER_OF_CLIPS);
    }

    private void showNotification() {

        List<String> thisClipText = new ArrayList<String>();
        List<ClipObject> thisClips = getClips();
        for (ClipObject thisClip : thisClips) {
            thisClipText.add(thisClip.getText());
        }
        int length = thisClipText.size();
        if (length <= 1) {
            showSingleNotification();
            return;
        }
        length = (length > (NUMBER_OF_CLIPS + 1)) ? (NUMBER_OF_CLIPS + 1) : length;

        Intent resultIntent = new Intent(this, ActivityMain.class);
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

        SharedPreferences preference = PreferenceManager.getDefaultSharedPreferences(this);
        boolean pinOnTop = preference.getBoolean(ActivitySetting.NOTIFICATION_ALWAYS_SHOW, false);

        Notification.Builder preBuildNotification = new Notification.Builder(this)
                .setContentTitle(getString(R.string.clip_notification_title) + thisClipText.get(0)) //title
                .setContentText(getString(R.string.clip_notification_text))
                .setSmallIcon(R.drawable.ic_action_copy_black)
                .setPriority(Notification.PRIORITY_MIN)
                .setContentIntent(resultPendingIntent)
                .setOngoing(pinOnTop)
                .setAutoCancel(!pinOnTop);

        NotificationClipListAdapter bigView = new NotificationClipListAdapter(this.getBaseContext(), thisClipText.get(0));

        for (int i = 1; i < length; i++) {
            bigView.addClips(thisClipText.get(i));
        }

        Notification n = preBuildNotification.build();

        n.bigContentView = bigView.build();

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(0);
        notificationManager.notify(0, n);
    }

    private void showSingleNotification() {
        String currentClip = "Clipboard is empty.";
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb.hasPrimaryClip()) {
            ClipData cd = cb.getPrimaryClip();
            if (cd.getDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                CharSequence thisClip = cd.getItemAt(0).getText();
                if (thisClip != null) {
                    currentClip = thisClip.toString();
                }
            }
        }

        SharedPreferences preference = PreferenceManager.getDefaultSharedPreferences(this);
        boolean pinOnTop = preference.getBoolean(ActivitySetting.NOTIFICATION_ALWAYS_SHOW, false);

        Intent resultIntent = new Intent(this, ActivityMain.class);
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

        Notification n = new Notification.Builder(this)
                .setContentTitle(getString(R.string.clip_notification_title) + currentClip)
                .setContentText(getString(R.string.clip_notification_single_text))
                .setSmallIcon(R.drawable.ic_action_copy_black)
                .setPriority(Notification.PRIORITY_MIN)
                .setContentIntent(resultPendingIntent)
                .setOngoing(pinOnTop)
                .setAutoCancel(!pinOnTop)
                .build();

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(0);
        notificationManager.notify(0, n);
    }

    public static void startCBService(Context context, boolean forceShowNotification) {
        startCBService(context, forceShowNotification, 0);
    }

    public static void startCBService(Context context, boolean forceShowNotification, int myActivitiesOnForegroundMessage) {
        Intent intent = new Intent(context, CBWatcherService.class)
                .putExtra(INTENT_EXTRA_FORCE_SHOW_NOTIFICATION, forceShowNotification)
                .putExtra(INTENT_EXTRA_MY_ACTIVITY_ON_FORGROUND_MESSAGE, myActivitiesOnForegroundMessage);
        context.startService(intent);
    }

    private class NotificationClipListAdapter {

        private int buttonNumber = 0;

        private RemoteViews expandedView;
        private Context c;
        int id = 0;

        public NotificationClipListAdapter(Context context, String currentClip) {
            c = context;
            currentClip = currentClip.trim();
            expandedView = new RemoteViews(c.getPackageName(), R.layout.notification_clip_list);
            expandedView.setTextViewText(R.id.current_clip, currentClip);
            //add pIntent for share
            Intent openShareIntent = new Intent(c, StringActionIntentService.class);
            openShareIntent.putExtra(StringActionIntentService.CLIPBOARD_STRING, currentClip);
            openShareIntent.putExtra(StringActionIntentService.CLIPBOARD_ACTION, StringActionIntentService.ACTION_SHARE);
            PendingIntent pOpenShareIntent = PendingIntent.getService(c,
                    buttonNumber++,
                    openShareIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            expandedView.setOnClickPendingIntent(R.id.clip_share_button, pOpenShareIntent);
        }

        public NotificationClipListAdapter addClips(String s) {
            id += 1;
            s = s.trim();
            //Log.v(PACKAGE_NAME,"ID "+id);
            //Log.v(PACKAGE_NAME,s);
            //add view
            RemoteViews theClipView = new RemoteViews(c.getPackageName(), R.layout.notification_clip_card_view);
            theClipView.setTextViewText(R.id.clip_text, s);

            //add pIntent for copy

            Intent openCopyIntent = new Intent(c, StringActionIntentService.class)
                    .putExtra(StringActionIntentService.CLIPBOARD_STRING, s)
                    .putExtra(StringActionIntentService.CLIPBOARD_ACTION, StringActionIntentService.ACTION_COPY);
            PendingIntent pOpenCopyIntent = PendingIntent.getService(c,
                    buttonNumber++,
                    openCopyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            theClipView.setOnClickPendingIntent(R.id.clip_copy_button, pOpenCopyIntent);

            //add pIntent for edit

            Intent openEditIntent = new Intent(c, StringActionIntentService.class)
                    .putExtra(StringActionIntentService.CLIPBOARD_STRING, s)
                    .putExtra(StringActionIntentService.CLIPBOARD_ACTION, StringActionIntentService.ACTION_EDIT);
            PendingIntent pOpenEditIntent = PendingIntent.getService(
                    c,
                    buttonNumber++,
                    openEditIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            theClipView.setOnClickPendingIntent(R.id.clip_text, pOpenEditIntent);


            expandedView.addView(R.id.main_view, theClipView);
            return this;
        }

        public RemoteViews build() {
            //expandedView.setTextViewText(R.id.text, "Hello World!");
            return expandedView;
        }
    }
}