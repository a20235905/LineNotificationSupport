package com.mysticwind.linenotificationsupport;

import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.mysticwind.linenotificationsupport.model.LineNotification;
import com.mysticwind.linenotificationsupport.utils.GroupIdResolver;
import com.mysticwind.linenotificationsupport.utils.ImageNotificationPublisherAsyncTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static androidx.core.app.NotificationCompat.EXTRA_TEXT;

public class MainActivity extends AppCompatActivity {

    public static final String CHANNEL_ID = "converted-jp.naver.line.android.notification.NewMessages";
    private static final GroupIdResolver GROUP_ID_RESOLVER = new GroupIdResolver(1);

    private Dialog grantPermissionDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
                sendNotification("MessageGroup", "Message: " + Instant.now().toString());
            }
        });

        fab.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                sendNotification("MessageGroup2", "Group 2 Message: " + Instant.now().toString());
                return true;
            }
        });

        if (grantPermissionDialog == null) {
            grantPermissionDialog = createGrantPermissionDialog();
        }
    }

    private void sendNotification(String groupKey, String message) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        boolean shouldShowGroupNotification = false;
        List<CharSequence> currentNotificationMessages = new ArrayList<>();
        currentNotificationMessages.add(message);
        final int groupId = GROUP_ID_RESOLVER.resolveGroupId(groupKey);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (StatusBarNotification sbn : notificationManager.getActiveNotifications()) {
                if (sbn.getId() != groupId && groupKey.equalsIgnoreCase(sbn.getNotification().getGroup())) {
                    CharSequence text = sbn.getNotification().extras.getCharSequence(EXTRA_TEXT);
                    currentNotificationMessages.add(text);
                    shouldShowGroupNotification = true;
                    break;
                }
            }
        }

        showSingleNotification(groupKey, message, shouldShowGroupNotification, currentNotificationMessages);
        if (shouldShowGroupNotification) {
            showGroupNotification(groupKey, currentNotificationMessages);
        }
    }

    private void showSingleNotification(String groupKey,
                                        String message,
                                        boolean shouldShowGroupNotification,
                                        List<CharSequence> currentNotificationMessages) {
        int notificationId = (int) (System.currentTimeMillis() / 1000);

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        final Person sender = new Person.Builder().setName("sender").build();

        if (true) {
            final String url = "https://stickershop.line-scdn.net/products/0/0/9/1917/android/stickers/37789.png";
            LineNotification lineNotification = LineNotification.builder()
                    .title("Title")
                    .message(message)
                    .lineStickerUrl(url)
                    .chatId(groupKey)
                    .sender(sender)
                    .build();
            new ImageNotificationPublisherAsyncTask(this, getPackageName(), lineNotification,
                    notificationId, GROUP_ID_RESOLVER).execute();
        } else {
            Notification singleNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Title")
                    .setContentText(message)
                    .setGroup(groupKey)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .build();

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            notificationManager.notify(notificationId, singleNotification);
        }
    }

    private void showGroupNotification(String groupKey, List<CharSequence> previousNotificationsTexts) {
        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
        for (CharSequence text: previousNotificationsTexts) {
            style.addLine(text);
        }
        int groupCount = previousNotificationsTexts.size() + 1;
        style.setSummaryText(groupCount + " new notifications");

        Notification groupNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setStyle(style)
                .setContentTitle("Group Title")
                .setContentText("Group Text")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setGroup(groupKey)
                .setGroupSummary(true)
                .build();

        final int groupId = GROUP_ID_RESOLVER.resolveGroupId(groupKey);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(groupId, groupNotification);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (hasNotificationAccess()) {
            if (grantPermissionDialog.isShowing()) {
                grantPermissionDialog.dismiss();
            }
        } else {
            grantPermissionDialog.show();
        }
    }

    private Dialog createGrantPermissionDialog() {
        return new AlertDialog.Builder(this)
                // TODO localization
                .setMessage("You'll need to grant permissions for this app to access Line notifications.")
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        redirectToNotificationSettingsPage();
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setCancelable(false)
                .create();
    }

    private boolean hasNotificationAccess() {
        final ContentResolver contentResolver = getContentResolver();
        String enabledNotificationListeners =
                Settings.Secure.getString(contentResolver, "enabled_notification_listeners");
        String packageName = getPackageName();
        return enabledNotificationListeners != null && enabledNotificationListeners.contains(packageName);
    }

    private void redirectToNotificationSettingsPage() {
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}