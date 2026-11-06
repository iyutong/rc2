package local.rc2.core.internal;

import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

/**
 * 与 DJI {@code protocol} Binder 服务的唯一接触点。内部实现，宿主不需要直接使用。
 *
 * <p>只做两件事：
 * <ol>
 *   <li>transact(2) 订阅 cmdSet 6 / cmd 174 的按键上报；</li>
 *   <li>transact(4) 按固定间隔查询摇杆 6 通道。</li>
 * </ol>
 * 报文解析放在上层，这里只负责取字节。
 */
public final class DjiProtocol {

    /** 上层接收点。全部在内部线程回调。 */
    public interface Sink {
        /** 摇杆 6 通道原始值。 */
        void onSticks(int[] channels);

        /** 一条推送包：15 个 int 的头部 + 负载。 */
        void onPush(int[] header, byte[] payload);

        /** 服务可用性。{@code ready == false} 时控制器不应再发查询。 */
        void onReady(boolean ready, String detail);

        /** 单次调用失败，控制器会继续重试。 */
        void onError(String message);
    }

    private static final String SERVICE_NAME = "protocol";
    private static final String DESCRIPTOR = "com.dji.protocol.IProtocolManager";
    private static final String LISTENER = "com.dji.protocol.IPackListener";

    /** 订阅：{@code writeInt(1), writeInt(1), filter[6], IBinder}。 */
    private static final int TX_SUBSCRIBE = 2;
    /** 查询取值：{@code writeInt(1), pack[20], IBinder}。 */
    private static final int TX_QUERY = 4;
    /** 退订：{@code writeStrongBinder}。 */
    private static final int TX_UNSUBSCRIBE = 5;

    /** 查询包：cmdSet 6 / cmd 4（摇杆通道），超时 300。 */
    private static final int[] QUERY_PACK =
            {0x55, 1, 0, 0, 4, 2, 0, 6, -1, 0, 2, 0, 0, 6, 4, 0, 0, 0, 300, 0};
    /** 订阅过滤器：type 2、cmdSet 6、cmd 174。 */
    private static final int[] SUBSCRIBE_FILTER = {4, 2, 0, 6, 6, 174};

    private final Sink sink;
    private final int pollIntervalMs;
    private final HandlerThread thread = new HandlerThread("rc2-protocol");
    private final Handler worker;

    private IBinder service;
    private volatile boolean running;
    private volatile boolean waiting;
    private long sentAt;

    private final Binder pushes = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(LISTENER);
                }
                return true;
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface(LISTENER);
                try {
                    if (data.readInt() != 0) {
                        int[] hdr = new int[15];
                        for (int i = 0; i < 15; i++) {
                            hdr[i] = data.readInt();
                        }
                        byte[] raw = data.readInt() > 0 ? data.createByteArray() : null;
                        if (running && raw != null) {
                            sink.onPush(hdr, raw);
                        }
                    }
                } catch (Exception e) {
                    sink.onError("按键报文解码失败：" + e);
                }
            }
            return true;
        }
    };

    private final Binder queryCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(LISTENER);
                }
                return true;
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION || code == 2) {
                data.enforceInterface(LISTENER);
                waiting = false;
                try {
                    if (code == IBinder.FIRST_CALL_TRANSACTION && data.readInt() != 0) {
                        for (int i = 0; i < 15; i++) {
                            data.readInt();
                        }
                        byte[] raw = data.readInt() > 0 ? data.createByteArray() : null;
                        if (raw != null && raw.length >= 12) {
                            int[] ch = new int[6];
                            for (int i = 0; i < 6; i++) {
                                ch[i] = (raw[i * 2] & 0xff) | ((raw[i * 2 + 1] & 0xff) << 8);
                            }
                            sink.onSticks(ch);
                        }
                    } else {
                        sink.onError("摇杆查询空返回");
                    }
                } catch (Exception e) {
                    sink.onError("摇杆查询失败：" + e);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    public DjiProtocol(Sink sink, int pollIntervalMs) {
        this.sink = sink;
        this.pollIntervalMs = Math.max(16, pollIntervalMs);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    /** 幂等启动。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker.post(this::bind);
    }

    /** 幂等停止：退订并结束线程。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        worker.removeCallbacksAndMessages(null);
        if (service != null) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(pushes);
                service.transact(TX_UNSUBSCRIBE, data, reply, 0);
                reply.readException();
            } catch (Exception ignored) {
                // 服务已退出时退订失败是正常的
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        thread.quitSafely();
    }

    public boolean isRunning() {
        return running;
    }

    private void bind() {
        try {
            service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, SERVICE_NAME);
        } catch (Exception e) {
            service = null;
        }
        if (service == null) {
            sink.onReady(false, "protocol 服务不可用（非遥控器环境？）");
            return;
        }
        if (!subscribe()) {
            sink.onReady(false, "按键订阅被拒绝");
            return;
        }
        sink.onReady(true, "已连接 · 按键订阅 6/174");
        poll();
    }

    private boolean subscribe() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(1);
            data.writeInt(1);
            for (int v : SUBSCRIBE_FILTER) {
                data.writeInt(v);
            }
            data.writeStrongBinder(pushes);
            service.transact(TX_SUBSCRIBE, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            sink.onError("按键订阅失败：" + e.getClass().getSimpleName());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void poll() {
        if (!running) {
            return;
        }
        boolean ok = true;
        try {
            if (!waiting || SystemClock.uptimeMillis() - sentAt > 700) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(1);
                    for (int v : QUERY_PACK) {
                        data.writeInt(v);
                    }
                    data.writeStrongBinder(queryCallback);
                    waiting = true;
                    sentAt = SystemClock.uptimeMillis();
                    if (!service.transact(TX_QUERY, data, reply, 0)) {
                        throw new Exception("查询事务被拒绝");
                    }
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        } catch (SecurityException e) {
            sink.onError(e.toString());
            running = false;
            return;
        } catch (Exception e) {
            waiting = false;
            ok = running;
            sink.onError("摇杆查询异常：" + e);
        }
        if (ok) {
            worker.postDelayed(this::poll, pollIntervalMs);
        }
    }
}
