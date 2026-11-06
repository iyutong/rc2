package cn.iyutong.rc2core.internal;

import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用 protocol 通道探针：可以订阅任意过滤条件、查询任意 (cmdSet, cmd)。
 *
 * <p>和 {@link DjiProtocol} 的区别：那个只认死 6/174 与摇杆 6/4，是产品代码；
 * 这个是诊断工具，用来「广撒网」找 DJI 官方 App 没用到、但确实在线上跑的命令。
 *
 * <p>所有事务都在自己的线程上跑，不阻塞调用方。任何一步失败都只回调 {@link Listener#onNote}，
 * 不抛异常——诊断工具不该把宿主搞崩。
 */
public final class RawProbe {

    /** 帧回调。全部在内部线程。 */
    public interface Listener {
        /**
         * 一帧推送或一次查询返回。
         *
         * @param header    15 个 int 的报文头，里面带着发送方/接收方/命令号
         * @param fromQuery true 表示这是查询应答，false 表示服务主动推上来的
         */
        void onFrame(int cmdSet, int cmd, int type, byte[] payload, int[] header, boolean fromQuery);

        /** 过程日志，直接上屏即可。 */
        void onNote(String tag, String msg);
    }

    public static final String SERVICE_NAME = "protocol";
    private static final String DESCRIPTOR = "com.dji.protocol.IProtocolManager";
    private static final String LISTENER = "com.dji.protocol.IPackListener";

    private static final int TX_SUBSCRIBE = 2;
    private static final int TX_QUERY = 4;
    private static final int TX_UNSUBSCRIBE = 5;

    /** 查询包模板，只改 [13] cmdSet 与 [14] cmd。索引 8 官方给的是 -1，照抄不动。 */
    private static final int[] QUERY_TEMPLATE =
            {0x55, 1, 0, 0, 4, 2, 0, 6, -1, 0, 2, 0, 0, 6, 4, 0, 0, 0, 300, 0};

    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("rc2-rawprobe");
    private final Handler worker;
    private final List<Binder> live = new ArrayList<>();

    private volatile IBinder service;
    private volatile boolean running;
    private volatile int queryErrors;

    /** 累计查询失败次数，深扫时用来判断「这条路通不通」。 */
    public int queryErrors() {
        return queryErrors;
    }

    public RawProbe(Listener listener) {
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public boolean isRunning() {
        return running;
    }

    public boolean serviceReady() {
        return service != null;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker.post(this::open);
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        // 线程保留到进程结束：HandlerThread 不能重启，界面反复进出不该重建
        worker.post(() -> {
            for (Binder b : new ArrayList<>(live)) {
                unsubscribe(b);
            }
            live.clear();
        });
    }

    private void open() {
        try {
            service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, SERVICE_NAME);
        } catch (Exception e) {
            service = null;
        }
        listener.onNote("PROBE", service == null
                ? "protocol 服务不可用"
                : "protocol 服务已连接");
    }

    /**
     * 订阅一个过滤条件。
     *
     * @param filter {@code {4, 2, 0, cmdSet, cmdSet, cmd}}，cmd 传 -1 试试通配
     * @return 用于退订的 Binder，服务不可用时返回 null
     */
    public Binder subscribe(final int[] filter) {
        if (service == null || filter == null) {
            return null;
        }
        final Binder binder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                return dispatch(code, data, reply, false);
            }
        };
        worker.post(() -> {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(1);
                data.writeInt(1);
                for (int v : filter) {
                    data.writeInt(v);
                }
                data.writeStrongBinder(binder);
                service.transact(TX_SUBSCRIBE, data, reply, 0);
                reply.readException();
                live.add(binder);
            } catch (Exception e) {
                listener.onNote("SUB", "订阅 " + describe(filter) + " 失败：" + shortName(e));
            } finally {
                data.recycle();
                reply.recycle();
            }
        });
        return binder;
    }

    public void unsubscribe(final Binder binder) {
        if (service == null || binder == null) {
            return;
        }
        worker.post(() -> {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(binder);
                service.transact(TX_UNSUBSCRIBE, data, reply, 0);
                reply.readException();
            } catch (Exception ignored) {
                // 服务已退出时正常
            } finally {
                data.recycle();
                reply.recycle();
                live.remove(binder);
            }
        });
    }

    /** 查询一条命令的当前值，结果走 {@link Listener#onFrame}。 */
    public void query(final int cmdSet, final int cmd) {
        if (service == null) {
            return;
        }
        worker.post(() -> {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(1);
                int[] pack = QUERY_TEMPLATE.clone();
                pack[7] = cmdSet;
                pack[13] = cmdSet;
                pack[14] = cmd;
                for (int v : pack) {
                    data.writeInt(v);
                }
                data.writeStrongBinder(queryCallback);
                service.transact(TX_QUERY, data, reply, 0);
                reply.readException();
            } catch (Exception e) {
                // 深扫会连续失败几十次，逐条刷日志会淹掉有用信息
                queryErrors++;
            } finally {
                data.recycle();
                reply.recycle();
            }
        });
    }

    /** 查询回调：和推送用同一套解包逻辑。 */
    private final Binder queryCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            return dispatch(code, data, reply, true);
        }
    };

    private boolean dispatch(int code, Parcel data, Parcel reply, boolean fromQuery) {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            if (reply != null) {
                reply.writeString(LISTENER);
            }
            return true;
        }
        if (code == IBinder.FIRST_CALL_TRANSACTION || code == 2) {
            data.enforceInterface(LISTENER);
            try {
                if (data.readInt() != 0) {
                    int[] hdr = new int[15];
                    for (int i = 0; i < 15; i++) {
                        hdr[i] = data.readInt();
                    }
                    byte[] raw = data.readInt() > 0 ? data.createByteArray() : null;
                    if (running && raw != null && raw.length > 0) {
                        listener.onFrame(hdr[13], hdr[14], hdr[5], raw, hdr, fromQuery);
                    }
                }
            } catch (Exception e) {
                listener.onNote("PROBE", "解包失败：" + shortName(e));
            }
            return true;
        }
        return true;
    }

    private static String describe(int[] f) {
        if (f == null || f.length < 6) {
            return "?";
        }
        return f[3] + "/" + f[5];
    }

    private static String shortName(Exception e) {
        String n = e.getClass().getSimpleName();
        String m = e.getMessage();
        return m == null ? n : n + "(" + m + ")";
    }
}
