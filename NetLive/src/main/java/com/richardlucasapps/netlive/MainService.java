package com.richardlucasapps.netlive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.TrafficStats;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.preference.PreferenceManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.RemoteViews;

public class MainService extends Service {

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    private Long bytesSentSinceBoot;
    private Long bytesReceivedSinceBoot;

    private Long previousBytesSentSinceBoot;
    private Long previousBytesReceivedSinceBoot;

    private Long bytesSentPerSecond;
    private Long bytesReceivedPerSecond;

    private String sentString;
    private String receivedString;

    private String activeApp = "";
    List<AppDataUsage> appDataUsageList;
    int appMonitorCounter;

    int mId;

    NotificationCompat.Builder mBuilder;
    NotificationManager mNotifyMgr;
    Notification notification;
    SharedPreferences sharedPref;

    ScheduledFuture updateHandler;
    Intent resultIntent;

    Context context;
    ComponentName name;

    UnitConverter converter;
    long pollRate;

    String displayValuesText = "";
    String unitMeasurement;
    boolean showActiveApp;
    String contentTitleText = "";


    PowerManager pm;
    boolean notificationEnabled;

    List<UnitConverter> widgetUnitMeasurementConverters;
    List<RemoteViews> widgetRemoteViews;

    int[] ids;
    AppWidgetManager manager;
    int N;

    long correctedPollRate;

    boolean eitherNotificationOrWidgetRequestsActiveApp;
    boolean showTotalValueNotification;
    boolean hideNotification;


    boolean widgetRequestsActiveApp;


    //TODO show total value as an option

    //TODO allow no notification to be displayed

    boolean widgetExist;
    @Override
    public void onCreate() {
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        notificationEnabled = !(sharedPref.getBoolean("pref_key_auto_start", false));
        widgetExist = sharedPref.getBoolean("widget_exists", false);

        if (!notificationEnabled && !widgetExist) {
            this.onDestroy();
            return;
        }


        unitMeasurement = sharedPref.getString("pref_key_measurement_unit", "Mbps");
        showTotalValueNotification = sharedPref.getBoolean("pref_key_show_total_value", false);
        pollRate = Long.parseLong(sharedPref.getString("pref_key_poll_rate", "1"));
        showActiveApp = sharedPref.getBoolean("pref_key_active_app", true);
        hideNotification = sharedPref.getBoolean("pref_key_hide_notification", false);

        converter = getUnitConverter(unitMeasurement);


        context = getApplicationContext();
        widgetRequestsActiveApp = false;
        if (widgetExist) {
            setupWidgets();
        }
        if(showActiveApp || widgetRequestsActiveApp){
            eitherNotificationOrWidgetRequestsActiveApp = true;
        }



        appMonitorCounter = 0;

        previousBytesSentSinceBoot = TrafficStats.getTotalTxBytes();//i dont initialize these to 0, because if i do, when app first reports, the rate will be crazy high
        previousBytesReceivedSinceBoot = TrafficStats.getTotalRxBytes();
        appDataUsageList = new ArrayList<AppDataUsage>();

        loadAllAppsIntoAppDataUsageList();


        if (notificationEnabled) {
            mNotifyMgr =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            mId = 1;
            mBuilder = new NotificationCompat.Builder(this)
                    .setSmallIcon(R.drawable.idle)
                    .setContentTitle("")
                    .setContentText("")
                    .setOngoing(true);

            if(Integer.valueOf(android.os.Build.VERSION.SDK_INT)>15){//not sure if this is needed, but Notification.PRIORITY_HIGH is only compatible with version above ice cream sandwich
                if(hideNotification){
                    mBuilder.setPriority(Notification.PRIORITY_MIN);
                }else{
                    mBuilder.setPriority(Notification.PRIORITY_HIGH);
                }
            }



            resultIntent = new Intent(this, MainActivity.class);
            TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
            stackBuilder.addParentStack(MainActivity.class);
            stackBuilder.addNextIntent(resultIntent);
            PendingIntent resultPendingIntent =
                    stackBuilder.getPendingIntent(
                            0,
                            PendingIntent.FLAG_UPDATE_CURRENT
                    );
            mBuilder.setContentIntent(resultPendingIntent);


            notification = mBuilder.build();


            mNotifyMgr.notify(
                    mId,
                    notification);

            startForeground(mId, notification);
        }

        startUpdateService(pollRate);


        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    @Override
    public void onDestroy() {
        try {
            updateHandler.cancel(true);
        } catch (NullPointerException e) {
            //The only way there will be a null pointer, is if the disabled preference is checked.  Because if it is, onDestory() is called right away, without creating the updateHandler
        }
        this.stopSelf();
        super.onDestroy();

    }


    public String getActiveAppWithTrafficApi() {

        long maxDelta = 0L;
        long delta = 0L;
        String appLabel = "";

        for (AppDataUsage currentApp : appDataUsageList) {
            delta = currentApp.getRateWithTrafficStatsAPI();
            if (delta > maxDelta) {
                appLabel = currentApp.getAppName();
                maxDelta = delta;
            }
        }
        if (appLabel.equals("")) {
            return "(" + "..." + ")";
        }
        return "(" + appLabel + ")";

    }






    private UnitConverter getUnitConverter(String unitMeasurement) {

        if (unitMeasurement.equals("bps")) {
            return (new UnitConverter() {
                @Override
                public double convert(long bytesPerSecond) {
                    return (bytesPerSecond * 8.0);
                }
            });
        }
        if (unitMeasurement.equals("Kbps")) {
            return (new UnitConverter() {
                @Override
                public double convert(long bytesPerSecond) {
                    return (bytesPerSecond * 8.0) / 1000.0;
                }
            });
        }
        if (unitMeasurement.equals("Mbps")) {
            return (new UnitConverter() {
                @Override
                public double convert(long bytesPerSecond) {
                    return (bytesPerSecond * 8.0) / 1000000.0;
                }
            });
        }
        if (unitMeasurement.equals("Gbps")) {
            return (new UnitConverter() {
                @Override
                public double convert(long bytesPerSecond) {
                    return (bytesPerSecond * 8.0) / 1000000000.0;
                }
            });
        }
        if (unitMeasurement.equals("Bps")) {
            return (new UnitConverter() {
                @Override
                public double convert(long bytesPerSecond) {
                    return bytesPerSecond;
                }
            });
        }
        if (unitMeasurement.equals("KBps")) {
            return (new UnitConverter() {
                @Override
                public double convert(long bytesPerSecond) {
                    return bytesPerSecond / 1000.0;
                }
            });
        }
        if (unitMeasurement.equals("MBps")) {
            return (new UnitConverter() {
                @Override
                public double convert(long bytesPerSecond) {
                    return bytesPerSecond / 1000000.0;
                }
            });
        }
        if (unitMeasurement.equals("GBps")) {
            return (new UnitConverter() {
                @Override
                public double convert(long bytesPerSecond) {
                    return bytesPerSecond / 1000000000.0;
                }
            });
        }

        return (new UnitConverter() {
            @Override
            public double convert(long bytesPerSecond) {
                return (bytesPerSecond * 8.0) / 1000000.0;
            }
        });


    }


    public void startUpdateService(long pollRate) {
        final Runnable beeper = new Runnable() {
            public void run() {
                update();
            }
        };
        updateHandler =
                scheduler.scheduleAtFixedRate(beeper, 1, pollRate, TimeUnit.SECONDS);
    }



    private void update() {

        prepareUpdate();

        if(notificationEnabled){
            updateNotification();

        }
        if(widgetExist){
            updateWidgets();

        }


    }


    private void updateNotification() {

        sentString = String.format("%.3f", converter.convert(bytesSentPerSecond) / correctedPollRate);
        receivedString = String.format("%.3f", converter.convert(bytesReceivedPerSecond) / correctedPollRate);

        previousBytesSentSinceBoot = bytesSentSinceBoot;
        previousBytesReceivedSinceBoot = bytesReceivedSinceBoot;

        if(showTotalValueNotification){
            double total = (converter.convert(bytesSentPerSecond) + converter.convert(bytesReceivedPerSecond)) / correctedPollRate;
            String totalString = String.format("%.3f", total);
            displayValuesText = "Total: " + totalString;
        }

        displayValuesText += " Up: " + sentString + " Down: " + receivedString;
        contentTitleText = unitMeasurement;

        if(showActiveApp){
            contentTitleText+= " " + activeApp;
        }

        mBuilder.setContentText(displayValuesText);
        mBuilder.setContentTitle(contentTitleText);
        displayValuesText = "";

        //TODO Report issue to AOSP where if the notification is set to minimum priority, and you update it after having called setWhen(), it will reissue it like a new notification, wont just update it


        if (!hideNotification) {

            if (bytesSentPerSecond / correctedPollRate < 13107 && bytesReceivedPerSecond / correctedPollRate < 13107) {
                mBuilder.setSmallIcon(R.drawable.idle);
                mNotifyMgr.notify(mId, mBuilder.build());
                return;
            }

            if (!(bytesSentPerSecond / correctedPollRate > 13107) && bytesReceivedPerSecond / correctedPollRate > 13107) {
                mBuilder.setSmallIcon(R.drawable.download);
                mNotifyMgr.notify(mId, mBuilder.build());
                return;
            }

            if (bytesSentPerSecond / correctedPollRate > 13107 && bytesReceivedPerSecond / correctedPollRate < 13107) {
                mBuilder.setSmallIcon(R.drawable.upload);
                mNotifyMgr.notify(mId, mBuilder.build());
                return;
            }

            if (bytesSentPerSecond / correctedPollRate > 13107 && bytesReceivedPerSecond / correctedPollRate > 13107) {//1307 bytes is equal to .1Mbit
                mBuilder.setSmallIcon(R.drawable.both);
                mNotifyMgr.notify(mId, mBuilder.build());
            }
        }
        mNotifyMgr.notify(mId, mBuilder.build());
    }

    private void setupWidgets() {

        name = new ComponentName(context, NetworkSpeedWidget.class);
        ids = AppWidgetManager.getInstance(context).getAppWidgetIds(name);
        manager = AppWidgetManager.getInstance(this);


        widgetUnitMeasurementConverters = new ArrayList<UnitConverter>();
        widgetRemoteViews = new ArrayList<RemoteViews>();

        N = ids.length;

        for (int i = 0; i < N; i++) {
            int awID = ids[i];

            String colorOfFont = sharedPref.getString("pref_key_widget_font_color" + awID, "Black");
            String sizeOfFont = sharedPref.getString("pref_key_widget_font_size" + awID, "12.0");
            String measurementUnit = sharedPref.getString("pref_key_widget_measurement_unit" + awID, "Mbps");
            boolean displayActiveApp = sharedPref.getBoolean("pref_key_widget_active_app" + awID, true);


            if(displayActiveApp){
                widgetRequestsActiveApp = true;
            }

            int widgetColor;
            widgetColor = Color.parseColor(colorOfFont);


            RemoteViews v = new RemoteViews(getPackageName(), R.layout.widget);
            v.setTextColor(R.id.widgetTextViewLineOne, widgetColor);
            Float tempFloat = Float.parseFloat(sizeOfFont);
            v.setFloat(R.id.widgetTextViewLineOne, "setTextSize", tempFloat);

            widgetRemoteViews.add(v);
            UnitConverter converter = getUnitConverter(measurementUnit);
            widgetUnitMeasurementConverters.add(converter);


        }
    }


    private void loadAllAppsIntoAppDataUsageList() {
        PackageManager packageManager = this.getPackageManager();
        List<ApplicationInfo> appList = packageManager.getInstalledApplications(0);

        for (ApplicationInfo appInfo : appList) {
            String appLabel = (String) packageManager.getApplicationLabel(appInfo);
            int uid = appInfo.uid;


            AppDataUsage app = new AppDataUsage(appLabel, uid);
            appDataUsageList.add(app);

        }
    }


    private void updateWidgets() {

        for (int i = 0; i < N; i++) {
            int awID = ids[i];

            boolean displayActiveApp = sharedPref.getBoolean("pref_key_widget_active_app" + awID, true);
            boolean displayTotalValue = sharedPref.getBoolean("pref_key_widget_show_total" + awID, false);


            String widgetTextViewLineOneText = "";

            if (displayActiveApp) {
                widgetTextViewLineOneText = activeApp + "\n";
            }

            UnitConverter c = widgetUnitMeasurementConverters.get(i);

            String sentString = String.format("%.3f", c.convert(bytesSentPerSecond) / correctedPollRate);
            String receivedString = String.format("%.3f", c.convert(bytesReceivedPerSecond) / correctedPollRate);

            widgetTextViewLineOneText += unitMeasurement + "\n";
            if(displayTotalValue){
                double total = (converter.convert(bytesSentPerSecond) + converter.convert(bytesReceivedPerSecond)) / correctedPollRate;
                String totalString = String.format("%.3f", total);
                displayValuesText = "Total: " + totalString;
                widgetTextViewLineOneText += "Total: " + totalString + "\n";
            }
            widgetTextViewLineOneText += "Up: " + sentString + "\n";
            widgetTextViewLineOneText += "Down: " + receivedString + "\n";

            RemoteViews v = widgetRemoteViews.get(i);
            v.setTextViewText(R.id.widgetTextViewLineOne, widgetTextViewLineOneText);
            manager.updateAppWidget(awID, v);

        }

    }

    private void prepareUpdate(){

        correctedPollRate = pollRate;


        bytesSentSinceBoot = TrafficStats.getTotalTxBytes();
        bytesReceivedSinceBoot = TrafficStats.getTotalRxBytes();

        bytesSentPerSecond = bytesSentSinceBoot - previousBytesSentSinceBoot;
        bytesReceivedPerSecond = bytesReceivedSinceBoot - previousBytesReceivedSinceBoot;

        previousBytesSentSinceBoot = bytesSentSinceBoot;
        previousBytesReceivedSinceBoot = bytesReceivedSinceBoot;


        if (eitherNotificationOrWidgetRequestsActiveApp) {
                activeApp = getActiveAppWithTrafficApi();


            appMonitorCounter += 1;
            if (appMonitorCounter >= (500 / pollRate)) {//divide by pollRate so that if you have a pollRate of 10, that will end up being 500 seconds, not 5000
                loadAllAppsIntoAppDataUsageList();
                appMonitorCounter = 0;
            }
        }

    }

}