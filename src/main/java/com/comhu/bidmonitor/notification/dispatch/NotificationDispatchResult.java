package com.comhu.bidmonitor.notification.dispatch;

/** 한 번의 dispatcher 실행 결과다. */
public record NotificationDispatchResult(
        boolean disabled,
        int pendingCount,
        int sentCount,
        int failedCount
) {

    public static NotificationDispatchResult disabledResult() {
        return new NotificationDispatchResult(true, 0, 0, 0);
    }
}
