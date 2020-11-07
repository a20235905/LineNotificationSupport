package com.mysticwind.linenotificationsupport.notification;

import com.mysticwind.linenotificationsupport.model.LineNotification;

public enum NullNotificationPublisher implements NotificationPublisher {

    INSTANCE;

    @Override
    public void publishNotification(LineNotification lineNotification, int notificationId) {
        // do nothing
    }

    @Override
    public void updateNotificationDismissed() {
        // do nothing
    }

}
