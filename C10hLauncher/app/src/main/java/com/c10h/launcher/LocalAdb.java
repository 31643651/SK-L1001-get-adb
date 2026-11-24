package com.c10h.launcher;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 设备内自连 adbd（“自己 adb 自己”），不依赖 PC/Armbian。
 *
 * 本机 adbd 监听 5555 且免认证（实测 CNXN 直接返回），
 * 应用用 ADB 协议连接 127.0.0.1:5555，执行 shell 命令。
 * 卸载就是 shell:pm uninstall --user 0 <pkg>，以 adbd 的 root/shell 身份执行。
 */
public final class LocalAdb {

    private static final int CNXN = 0x4E584E43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4E45504F;
    private static final int OKAY = 0x59414B4F;
    private static final int WRTE = 0x45545257;
    private static final int CLSE = 0x45534C43;

    private static final String[] HOSTS = {"127.0.0.1", "::1"};
    private static final int PORT = 5555;

    private LocalAdb() {
    }

    /** 执行 shell 命令，返回标准输出；连不上/需认证返回 null。 */
    public static String shell(String command) {
        for (String host : HOSTS) {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, PORT), 1500);
                socket.setSoTimeout(8000);
                String out = exec(socket, command);
                if (out != null) {
                    return out;
                }
            } catch (Throwable ignored) {
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static String exec(Socket socket, String command) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        DataInputStream in = new DataInputStream(socket.getInputStream());

        send(out, CNXN, 0x01000000, 4096, "host::\0".getBytes("UTF-8"));
        Msg first = recv(in);
        if (first == null || first.cmd != CNXN) {
            return null;
        }

        send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        while (true) {
            Msg m = recv(in);
            if (m == null) {
                break;
            }
            if (m.cmd == WRTE) {
                sb.append(new String(m.data, "UTF-8"));
                send(out, OKAY, 1, 0, new byte[0]);
            } else if (m.cmd == OKAY) {
                // 流已建立
            } else if (m.cmd == CLSE) {
                break;
            } else if (m.cmd == AUTH) {
                return null;
            } else {
                break;
            }
        }
        return sb.toString();
    }

    /** 卸载指定包；成功返回 true。 */
    public static boolean uninstall(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) {
            return false;
        }
        String out = shell("pm uninstall --user 0 " + pkg);
        android.util.Log.i("C10HLocalAdb", "uninstall " + pkg + " -> " + out);
        return out != null && out.contains("Success");
    }

    private static void send(DataOutputStream out, int cmd, int a0, int a1,
                             byte[] data) throws IOException {
        int crc = 0;
        for (byte b : data) {
            crc += b & 0xFF;
        }
        ByteBuffer buf = ByteBuffer.allocate(24 + data.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cmd);
        buf.putInt(a0);
        buf.putInt(a1);
        buf.putInt(data.length);
        buf.putInt(crc);
        buf.putInt(cmd ^ 0xFFFFFFFF);
        buf.put(data);
        out.write(buf.array());
        out.flush();
    }

    private static Msg recv(DataInputStream in) throws IOException {
        byte[] hdr = new byte[24];
        in.readFully(hdr);
        ByteBuffer b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.a0 = b.getInt();
        m.a1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // crc
        b.getInt(); // magic
        if (len < 0 || len > 1024 * 1024) {
            return null;
        }
        m.data = new byte[len];
        if (len > 0) {
            in.readFully(m.data);
        }
        return m;
    }

    private static final class Msg {
        int cmd;
        int a0;
        int a1;
        byte[] data;
    }
}
