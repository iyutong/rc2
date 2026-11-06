package local.rc2.core;

/**
 * 日志出口。核心库不直接依赖宿主工程的日志系统，需要落到界面时由宿主实现本接口。
 *
 * <pre>
 * Rc2Controller.create(Rc2Config.builder()
 *         .logger((level, tag, msg) -&gt; myUiLog.write(level, tag, msg))
 *         .build());
 * </pre>
 */
public interface Rc2Log {

    /** @param level 'I' / 'W' / 'E' */
    void log(char level, String tag, String message);

    /** 默认实现：写 logcat。 */
    Rc2Log ANDROID = new Rc2Log() {
        @Override
        public void log(char level, String tag, String message) {
            if (level == 'E') {
                android.util.Log.e(tag, message);
            } else if (level == 'W') {
                android.util.Log.w(tag, message);
            } else {
                android.util.Log.i(tag, message);
            }
        }
    };
}
