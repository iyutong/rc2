package local.rc2.controls;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class LocalAdbStarter {
    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int WRTE = 0x45545257;
    private static final int CLSE = 0x45534c43;

    private static final class Packet {
        int cmd;
        int a;
        int b;
        byte[] data;
    }

    static String start(Context ctx) {
        if (!"rc331".equals(Build.DEVICE)) {
            return "仅支持已验证的 RC2";
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 800);
            s.setSoTimeout(1500);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            send(out, CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            Packet p = read(in);
            if (p.cmd == AUTH) {
                return "本机调试要求授权，请使用电脑激活脚本";
            }
            if (p.cmd != CNXN) {
                throw new IOException("Unexpected local ADB response");
            }
            String apk = ctx.getApplicationInfo().sourceDir;
            String quoted = "'" + apk.replace("'", "'\"'\"'") + "'";
            String cmd = "p=$(cat /data/local/tmp/rc2-xbox.pid 2>/dev/null); case \"$p\" in ''|*[!0-9]*) ;; *) c=$(tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null); case \"$c\" in *local.rc2.controls.GamepadBridgeMain*) kill \"$p\";; esac;; esac; trap '' HUP; while read -r directive name value; do [ \"$directive\" = export ] || continue; case \"$name\" in ANDROID_*|BOOTCLASSPATH|DEX2OATBOOTCLASSPATH|SYSTEMSERVERCLASSPATH) export \"$name=$value\";; esac; done < /init.environ.rc; unset LD_LIBRARY_PATH; CLASSPATH="
                    + quoted
                    + " /system/bin/setsid /system/bin/app_process32 /system/bin local.rc2.controls.GamepadBridgeMain "
                    + Process.myUid()
                    + " </dev/null >/data/local/tmp/rc2-xbox.log 2>&1 & sleep 1";
            send(out, OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 20; i++) {
                Packet r = read(in);
                if (r.cmd == WRTE) {
                    send(out, OKAY, 1, r.a, new byte[0]);
                } else if (r.cmd == CLSE) {
                    send(out, CLSE, 1, r.a, new byte[0]);
                    return "本机辅助进程启动请求已发送";
                } else if (r.cmd != OKAY) {
                    throw new IOException("Unexpected shell response");
                }
            }
            return "本机辅助进程启动中";
        } catch (Exception e) {
            return "本机激活暂不可用：" + e.getClass().getSimpleName();
        }
    }

    private static void send(OutputStream out, int cmd, int a, int b, byte[] data) throws IOException {
        int sum = 0;
        for (byte x : data) {
            sum += x & 0xff;
        }
        ByteBuffer hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(cmd).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(hdr.array());
        out.write(data);
        out.flush();
    }

    private static byte[] full(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
        }
        return buf;
    }

    private static Packet read(InputStream in) throws IOException {
        ByteBuffer hdr = ByteBuffer.wrap(full(in, 24)).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = hdr.getInt();
        p.a = hdr.getInt();
        p.b = hdr.getInt();
        int len = hdr.getInt();
        int sum = hdr.getInt();
        int magic = hdr.getInt();
        if (len < 0 || len > 65536 || magic != ~p.cmd) {
            throw new IOException("Invalid local ADB frame");
        }
        p.data = full(in, len);
        int calc = 0;
        for (byte x : p.data) {
            calc += x & 0xff;
        }
        if (sum != calc && sum != 0) {
            throw new IOException("Local ADB checksum mismatch");
        }
        return p;
    }
}
