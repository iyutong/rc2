package local.rc2.controls;

import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

final class McuReader {
    interface Listener {
        void values(int[] channels);
        void buttons(byte[] payload);
        void error(String message);
    }

    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("rc2-mcu-reader");
    private final Handler worker;
    private IBinder service;
    private volatile boolean running = true;
    private volatile boolean waiting;
    private long sentAt;

    private final Binder pushes = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return onPush(code, data, reply);
        }
    };

    /** 追加的通配订阅（cmdSet 6 下任意 cmd），只为观察半按是否走了别的包号。 */
    private final Binder wide = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return onPush(code, data, reply);
        }
    };

    /** 枚举时用来试探订阅的接收端：只确认服务是否接受，不处理任何数据。 */
    private final Binder sink = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString("com.dji.protocol.IPackListener");
                }
                return true;
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface("com.dji.protocol.IPackListener");
                try {
                    if (data.readInt() != 0) {
                        for (int i = 0; i < 15; i++) {
                            data.readInt();
                        }
                        data.readInt();
                        data.createByteArray();
                    }
                } catch (Exception ignored) {
                }
            }
            return true;
        }
    };

    private boolean onPush(int code, Parcel data, Parcel reply) throws RemoteException {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            if (reply != null) {
                reply.writeString("com.dji.protocol.IPackListener");
            }
            return true;
        }
        if (code == IBinder.FIRST_CALL_TRANSACTION) {
            data.enforceInterface("com.dji.protocol.IPackListener");
            try {
                if (data.readInt() != 0) {
                    int[] hdr = new int[15];
                    for (int i = 0; i < 15; i++) {
                        hdr[i] = data.readInt();
                    }
                    byte[] raw = data.readInt() > 0 ? data.createByteArray() : null;
                    if (running && raw != null) {
                        InputMonitor.probe(hdr, raw);
                        if (hdr[13] == 6 && hdr[14] == 174 && hdr[5] == 2) {
                            listener.buttons(raw);
                        }
                    }
                }
            } catch (Exception e) {
                UiLog.e("MCU", "按键推送解码失败", e);
            }
        }
        return true;
    }

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString("com.dji.protocol.IPackListener");
                }
                return true;
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION || code == 2) {
                data.enforceInterface("com.dji.protocol.IPackListener");
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
                            listener.values(ch);
                        }
                    } else {
                        listener.error("MCU 查询失败");
                    }
                } catch (Exception e) {
                    listener.error(e.toString());
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    McuReader(Listener listener) {
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> init());
    }

    private void init() {
        try {
            service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "protocol");
            if (service == null) {
                throw new Exception("protocol service unavailable");
            }
            subscribe();
            subscribeWide();
            poll();
        } catch (Exception e) {
            listener.error("MCU 接口不可用：" + e);
        }
    }

    private void poll() {
        if (!running) {
            return;
        }
        try {
            if (!waiting || SystemClock.uptimeMillis() - sentAt > 700) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.dji.protocol.IProtocolManager");
                    data.writeInt(1);
                    int[] pack = new int[]{0x55, 1, 0, 0, 4, 2, 0, 6, -1, 0, 2, 0, 0, 6, 4, 0, 0, 0, 300, 0};
                    for (int v : pack) {
                        data.writeInt(v);
                    }
                    data.writeStrongBinder(callback);
                    waiting = true;
                    sentAt = SystemClock.uptimeMillis();
                    if (!service.transact(4, data, reply, 0)) {
                        throw new Exception("query transaction rejected");
                    }
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        } catch (SecurityException e) {
            listener.error(e.toString());
            running = false;
            return;
        } catch (Exception e) {
            waiting = false;
            listener.error(e.toString());
        }
        worker.postDelayed(() -> poll(), 40);
    }

    private void subscribe() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.dji.protocol.IProtocolManager");
            data.writeInt(1);
            data.writeInt(1);
            int[] filter = new int[]{4, 2, 0, 6, 6, 174};
            for (int v : filter) {
                data.writeInt(v);
            }
            data.writeStrongBinder(pushes);
            service.transact(2, data, reply, 0);
            reply.readException();
            UiLog.i("MCU", "按键订阅就绪（6/174）");
        } catch (Exception e) {
            UiLog.e("MCU", "按键订阅失败", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 追加一个 cmdSet=6、cmd 不限的订阅。若服务端把 -1 当通配，
     * 半按若走的是另一个 cmd 就能在探针页看到；不支持则只留一条警告，不影响原订阅。
     */
    private void subscribeWide() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.dji.protocol.IProtocolManager");
            data.writeInt(1);
            data.writeInt(1);
            int[] filter = new int[]{4, 2, 0, 6, 6, -1};
            for (int v : filter) {
                data.writeInt(v);
            }
            data.writeStrongBinder(wide);
            service.transact(2, data, reply, 0);
            reply.readException();
            UiLog.i("MCU", "追加通配订阅（6/-1）");
        } catch (Exception e) {
            UiLog.w("MCU", "通配订阅未生效：" + e.getClass().getSimpleName());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 全量订阅：cmdSet 与 cmdId 都传 -1。服务端若支持通配，会把这个遥控器上报的
     * 所有协议包都推过来；若不支持，本调用会抛异常或无数据，界面会显示 0 包。
     */
    /**
     * 枚举 cmdSet 下 [from, to] 的 cmdId：逐个精确订阅，服务端接受（不抛异常）
     * 即视为该 cmd 存在，立刻退订再试下一个。
     * 必须跑在独立线程：几千次 Binder 调用若占用 worker，会把摇杆轮询卡住。
     */
    void startCmdProbe(int cmdSet, int from, int to) {
        Thread t = new Thread(() -> {
            if (service == null) {
                InputMonitor.cmdState = "MCU 不可用";
                InputMonitor.cmdProbeRunning = false;
                InputMonitor.cmdRevision++;
                return;
            }
            long begin = SystemClock.uptimeMillis();
            for (int id = from; id <= to; id++) {
                if (!InputMonitor.cmdProbeRunning || !running) {
                    break;
                }
                if (trySubscribe(cmdSet, id)) {
                    InputMonitor.cmdHit(id);
                    dropSink();
                }
                InputMonitor.cmdCursor = id;
                if ((id & 0x3F) == 0) {
                    InputMonitor.cmdElapsed = SystemClock.uptimeMillis() - begin;
                    InputMonitor.cmdRevision++;
                }
            }
            InputMonitor.cmdElapsed = SystemClock.uptimeMillis() - begin;
            InputMonitor.cmdProbeRunning = false;
            InputMonitor.cmdState = InputMonitor.cmdCursor >= to ? "已完成" : "已中止";
            InputMonitor.cmdRevision++;
            UiLog.i("Cmd", "枚举结束，耗时 " + InputMonitor.cmdElapsed + " ms，命中 "
                    + InputMonitor.cmdHits().size());
        }, "rc2-cmd-probe");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** 循环靠 cmdProbeRunning 标志退出，这里只负责让界面立刻反映"已中止"。 */
    void stopCmdProbe() {
        UiLog.w("Cmd", "请求中止枚举");
    }

    private boolean trySubscribe(int cmdSet, int cmdId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.dji.protocol.IProtocolManager");
            data.writeInt(1);
            data.writeInt(1);
            int[] filter = new int[]{4, 2, 0, cmdSet, cmdSet, cmdId};
            for (int v : filter) {
                data.writeInt(v);
            }
            data.writeStrongBinder(sink);
            service.transact(2, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void dropSink() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.dji.protocol.IProtocolManager");
            data.writeStrongBinder(sink);
            service.transact(5, data, reply, 0);
            reply.readException();
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    void stop() {
        running = false;
        InputMonitor.cmdProbeRunning = false;
        worker.removeCallbacksAndMessages(null);
        if (service != null) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.dji.protocol.IProtocolManager");
                data.writeStrongBinder(pushes);
                service.transact(5, data, reply, 0);
                reply.readException();
            } catch (Exception ignored) {
            } finally {
                data.recycle();
                reply.recycle();
            }
            Parcel d2 = Parcel.obtain();
            Parcel r2 = Parcel.obtain();
            try {
                d2.writeInterfaceToken("com.dji.protocol.IProtocolManager");
                d2.writeStrongBinder(wide);
                service.transact(5, d2, r2, 0);
                r2.readException();
            } catch (Exception ignored) {
            } finally {
                d2.recycle();
                r2.recycle();
            }
        }
        thread.quitSafely();
    }
}
