package cn.iyutong.rc2core;

import android.content.Context;

/**
 * 兜底拿一个进程级 Context。
 *
 * <p>只为了「忘了传 Context 也别炸」：USB 通道没有 Context 就拿不到 {@code UsbManager}，
 * 但整个库不该因此抛异常。反射 {@code ActivityThread.currentApplication()} 是系统内部 API，
 * 部分机型会拒绝，所以失败时安静返回 null，由调用方在状态文案里说明。
 */
final class AppContext {

    private static volatile Context cached;
    private static volatile boolean tried;

    private AppContext() {
    }

    static Context application() {
        if (cached != null || tried) {
            return cached;
        }
        tried = true;
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object app = cls.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                cached = (Context) app;
            }
        } catch (Exception ignored) {
            // 取不到就保持 null
        }
        return cached;
    }
}
