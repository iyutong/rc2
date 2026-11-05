package local.rc2.controls;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 进程内日志环形缓冲：写 logcat 的同时留一份给仪表盘显示。
 * 只保存最近 {@link #CAPACITY} 条，避免占内存。
 */
public final class UiLog {
    public static final int CAPACITY = 200;
    public static volatile long revision;

    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>(CAPACITY);

    public static final class Entry {
        public final char level;
        public final String line;

        Entry(char level, String tag, String msg, long at) {
            this.level = level;
            this.line = String.format(Locale.US, "%1$tH:%1$tM:%1$tS", new Date(at))
                    + " " + level + " " + tag + " " + msg;
        }
    }

    private UiLog() {
    }

    public static void i(String tag, String msg) {
        add('I', tag, msg);
    }

    public static void w(String tag, String msg) {
        add('W', tag, msg);
    }

    public static void e(String tag, String msg) {
        add('E', tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        add('E', tag, msg + " · " + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage()));
    }

    private static void add(char level, String tag, String msg) {
        if (level == 'E') {
            Log.e(tag, msg);
        } else if (level == 'W') {
            Log.w(tag, msg);
        } else {
            Log.i(tag, msg);
        }
        synchronized (ENTRIES) {
            if (ENTRIES.size() >= CAPACITY) {
                ENTRIES.removeFirst();
            }
            ENTRIES.addLast(new Entry(level, tag, msg, System.currentTimeMillis()));
        }
        revision++;
    }

    public static List<Entry> snapshot() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }
}
