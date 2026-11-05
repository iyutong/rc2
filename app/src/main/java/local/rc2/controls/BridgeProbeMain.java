package local.rc2.controls;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.OutputStreamWriter;
import java.io.Writer;

public final class BridgeProbeMain {
    public static void main(String[] args) throws Exception {
        LocalSocket s = new LocalSocket();
        s.connect(new LocalSocketAddress("rc2.xbox.uhid.v1", LocalSocketAddress.Namespace.ABSTRACT));
        Writer w = new OutputStreamWriter(s.getOutputStream(), "UTF-8");
        w.write("{\"active\":true,\"lx\":0.5}\n");
        w.flush();
        Thread.sleep(100L);
        w.write("{\"active\":false}\n");
        w.flush();
        s.close();
        System.out.println("Bounded axis probe sent and neutralized");
    }
}
