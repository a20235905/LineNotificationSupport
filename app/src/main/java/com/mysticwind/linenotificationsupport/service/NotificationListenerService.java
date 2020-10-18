package com.mysticwind.linenotificationsupport.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.mysticwind.linenotificationsupport.MainActivity;
import com.mysticwind.linenotificationsupport.R;
import com.mysticwind.linenotificationsupport.utils.ImageNotificationPublisherAsyncTask;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static androidx.core.app.NotificationCompat.EXTRA_TEXT;

public class NotificationListenerService
        extends android.service.notification.NotificationListenerService {

    private static final String TAG = "LINE_NOTIFICATION_SUPPORT";
    private static final int GROUP_ID_START = 0x4000;
    private static final int MESSAGE_ID_START = 0x1000;

    private static final Map<String, Integer> chatIdToGroupIdMap = new HashMap<>();
    private static int lastGroupId = GROUP_ID_START;
    private static int lastMessageId = MESSAGE_ID_START;

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification statusBarNotification) {
        // ignore messages from ourselves
        if (statusBarNotification.getPackageName().startsWith(getPackageName())) {
            return;
        }

        final String packageName = statusBarNotification.getPackageName();

        // let's just focus on Line notifications for now
        if (!packageName.equals("jp.naver.line.android")) {
            return;
        }

        // ignore summaries
        if (isSummary(statusBarNotification)) {
            return;
        }

        final String stringifiedNotification = MoreObjects.toStringHelper(statusBarNotification)
                .add("packageName", statusBarNotification.getPackageName())
                .add("groupKey", statusBarNotification.getGroupKey())
                .add("key", statusBarNotification.getKey())
                .add("id", statusBarNotification.getId())
                .add("tag", statusBarNotification.getTag())
                .add("user", statusBarNotification.getUser().toString())
                .add("overrideGroupKey", statusBarNotification.getOverrideGroupKey())
                .add("notification", ToStringBuilder.reflectionToString(statusBarNotification.getNotification()))
                .toString();
        Log.i(TAG, String.format("Notification (%s): %s",
                statusBarNotification.getPackageName(),
                stringifiedNotification)
        );

        sendNotification(statusBarNotification);
    }

    private boolean isSummary(final StatusBarNotification statusBarNotification) {
        final String summaryText = statusBarNotification.getNotification().extras
                .getString("android.summaryText");
        return StringUtils.isNotBlank(summaryText);
    }

    private void sendNotification(StatusBarNotification notificationFromLine) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        final String chatId = getChatId(notificationFromLine);
        boolean shouldShowGroupNotification = false;
        List<CharSequence> currentNotificationMessages = new ArrayList<>();
        currentNotificationMessages.add(notificationFromLine.getNotification().extras.getCharSequence(EXTRA_TEXT));
        final int groupId = getGroupId(chatId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (StatusBarNotification statusBarNotification : notificationManager.getActiveNotifications()) {
                if (statusBarNotification.getId() != groupId && chatId.equalsIgnoreCase(statusBarNotification.getNotification().getGroup())) {
                    CharSequence text = statusBarNotification.getNotification().extras.getCharSequence(EXTRA_TEXT);
                    currentNotificationMessages.add(text);
                    shouldShowGroupNotification = true;
                    break;
                }
            }
        }

        // individual: android.title is the sender
        // group chat: android.title is "group title：sender", android.conversationTitle is group title
        // chat with multi-folks: android.title is also the sender, no way to differentiate between individual and multi-folks :(

        // TODO build a generic notification model class
        final String title;
        final String sender;
        if (isChatGroup(notificationFromLine)) {
            title = getGroupChatTitle(notificationFromLine);
            final String androidTitle = getAndroidTitle(notificationFromLine);
            sender = androidTitle.replace(title + "：", "");
        } else {
            title = getAndroidTitle(notificationFromLine);
            sender = getAndroidTitle(notificationFromLine);
        }

        final Icon largeIcon = notificationFromLine.getNotification().getLargeIcon();
        final Bitmap largeIconBitmap = convertDrawableToBitmap(largeIcon.loadDrawable(this));

        final String message = notificationFromLine.getNotification().extras.getString("android.text");
        final String lineStickerUrl = getLineStickerUrl(notificationFromLine);
        // TODO fix this spaghetti code
        if (StringUtils.isNotBlank(lineStickerUrl)) {
            new ImageNotificationPublisherAsyncTask(this, title, message,
                    lineStickerUrl, chatId, ++lastMessageId, shouldShowGroupNotification, currentNotificationMessages,
                    getGroupId(chatId)).execute();
        } else {
            showSingleNotification(chatId, title, sender, largeIconBitmap, message, notificationFromLine);
            if (shouldShowGroupNotification) {
                showGroupNotification(chatId, title, sender, largeIconBitmap, currentNotificationMessages);
            }
        }
    }

    private boolean isChatGroup(final StatusBarNotification statusBarNotification) {
        final String title = statusBarNotification.getNotification().extras.getString("android.conversationTitle");
        return StringUtils.isNotBlank(title);
    }

    private String getGroupChatTitle(final StatusBarNotification statusBarNotification) {
        // chat groups will have a conversationTitle (but not groups of people)
        return statusBarNotification.getNotification().extras.getString("android.conversationTitle");
    }

    private String getAndroidTitle(final StatusBarNotification statusBarNotification) {
        return statusBarNotification.getNotification().extras.getString("android.title");
    }

    private String getChatId(final StatusBarNotification statusBarNotification) {
        return statusBarNotification.getNotification().extras.getString("line.chat.id");
    }

    private synchronized int getGroupId(final String chatId) {
        return chatIdToGroupIdMap.computeIfAbsent(chatId, chatIdWithoutGroupId -> {
            return lastGroupId++;
        });
    }

    private void showSingleNotification(final String chatId, final String title, final String sender,
                                        final Bitmap largeIcon, final String message, final StatusBarNotification notificationFromLine) {
        final String myName = notificationFromLine.getNotification().extras
                .getString("android.selfDisplayName");
        final long timestamp = notificationFromLine.getPostTime();
        // TODO this should be thread safe
        int notificationId = ++lastMessageId;

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        final Person senderPerson = new Person.Builder()
                .setName(sender)
                .setIcon(IconCompat.createWithBitmap(largeIcon))
                .build();

        final NotificationCompat.Style messageStyle = buildMessageStyle(senderPerson, title, message, timestamp, notificationFromLine);

        Notification singleNotification = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
                .setStyle(messageStyle)
                .setContentTitle(title)
                .setContentText(message)
                .setGroup(chatId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(largeIcon)
                .setContentIntent(pendingIntent)
                .build();

        final Notification.Action replyAction = extractReplyAction(notificationFromLine);
        addActionInNotification(singleNotification, replyAction);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(notificationId, singleNotification);
    }

    private Bitmap convertDrawableToBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        final Bitmap bitmap;
        if(drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private NotificationCompat.Style buildMessageStyle(Person senderPerson, String title, String message, long timestamp, StatusBarNotification notificationFromLine) {

        final String lineStickerUrl = getLineStickerUrl(notificationFromLine);
        final Bitmap lineStickerBitmap;
        if (StringUtils.isNotBlank(lineStickerUrl)) {
            lineStickerBitmap = downloadImage(lineStickerUrl);
        } else {
            lineStickerBitmap = null;
        }

        if (lineStickerBitmap != null) {
            return new NotificationCompat.BigPictureStyle()
                    .bigPicture(lineStickerBitmap)
                    .setSummaryText(message);
        } else {
            return new NotificationCompat.MessagingStyle(senderPerson)
                    .setConversationTitle(title)
                    .addMessage(message, timestamp, senderPerson);
        }
    }

    private String getLineStickerUrl(final StatusBarNotification statusBarNotification) {
        return statusBarNotification.getNotification().extras.getString("line.sticker.url");
    }

    protected Bitmap downloadImage(String imageUrl) {
        InputStream inputStream;
        try {
            final URL url = new URL(imageUrl);
            final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            inputStream = connection.getInputStream();
            return BitmapFactory.decodeStream(inputStream);
        } catch (final Exception e) {
            Log.e(TAG, String.format("Failed to download image %s: %s", imageUrl, e.getMessage()), e);
            return null;
        }
    }

    private void addActionInNotification(Notification notification, Notification.Action action) {
        if (ArrayUtils.isEmpty(notification.actions)) {
            notification.actions = new Notification.Action[] { action };
        } else {
            List<Notification.Action> actions = Lists.newArrayList(notification.actions);
            actions.add(action);
            notification.actions = (Notification.Action[]) actions.toArray();
        }
    }

    private Notification.Action extractReplyAction(StatusBarNotification notificationFromLine) {
        if (notificationFromLine.getNotification().actions.length < 2) {
            return null;
        }
        Notification.Action secondAction = notificationFromLine.getNotification().actions[1];
        // TODO what about other languages? should extract from Line apk?
        if ("回覆".equals(secondAction.title)) {
            return secondAction;
        }
        return null;
    }

    private void showGroupNotification(String chatId, String title, String sender, Bitmap largeIcon, List<CharSequence> previousNotificationsTexts) {
        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
        for (CharSequence text: previousNotificationsTexts) {
            style.addLine(text);
        }
        int groupCount = previousNotificationsTexts.size() + 1;
        style.setSummaryText(groupCount + " new notifications");

        Notification groupNotification = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
                .setStyle(style)
                .setContentTitle(title)
                .setContentText(previousNotificationsTexts.get(0))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(largeIcon)
                .setGroup(chatId)
                .setGroupSummary(true)
                .build();

        final int groupId = getGroupId(chatId);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(groupId, groupNotification);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
    }
}
