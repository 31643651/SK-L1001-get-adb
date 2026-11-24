package com.c10h.launcher.block;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

/**
 * DNS 代理（补充文档 §4）：命中后缀表 → 合成 0.0.0.0 / :: 应答；
 * 未命中 → protect() 后向系统真实 DNS 透传；解析失败返回 null（丢包不影响其他流量）。
 */
final class DnsProxy {

    private DnsProxy() {
    }

    static String qname(byte[] q, int off, int len) {
        try {
            int p = off + 12;
            StringBuilder sb = new StringBuilder();
            int guard = 0;
            while (p < off + len && guard++ < 128) {
                int l = q[p] & 0xFF;
                if (l == 0) {
                    break;
                }
                if (l > 63) {
                    return null;
                }
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(new String(q, p + 1, l, "ISO-8859-1"));
                p += l + 1;
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static byte[] blockedResponse(byte[] q, int qLen) {
        try {
            int qEnd = 12;
            while (qEnd < qLen && q[qEnd] != 0) {
                qEnd += (q[qEnd] & 0xFF) + 1;
            }
            qEnd += 1 + 4;
            int qtype = ((q[qEnd - 4] & 0xFF) << 8) | (q[qEnd - 3] & 0xFF);
            boolean aaaa = qtype == 28;
            int rdlen = aaaa ? 16 : 4;
            byte[] r = new byte[qEnd + 16];
            System.arraycopy(q, 0, r, 0, qEnd);
            r[2] = (byte) 0x81;
            r[3] = (byte) 0x80;
            r[6] = 0;
            r[7] = 1;
            int o = qEnd;
            r[o++] = (byte) 0xC0;
            r[o++] = 0x0C;
            r[o++] = 0;
            r[o++] = (byte) (aaaa ? 28 : 1);
            r[o++] = 0;
            r[o++] = 1;
            r[o++] = 0;
            r[o++] = 0;
            r[o++] = 0;
            r[o++] = 60;
            r[o++] = 0;
            r[o++] = (byte) rdlen;
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    static byte[] passthrough(android.net.VpnService svc, Context c, byte[] q, int qLen) {
        DatagramSocket s = null;
        try {
            InetAddress dns = upstream(c);
            if (dns == null) {
                return null;
            }
            s = new DatagramSocket();
            if (!svc.protect(s)) {
                return null;
            }
            s.setSoTimeout(2000);
            s.send(new DatagramPacket(q, qLen, dns, 53));
            byte[] buf = new byte[4096];
            DatagramPacket rp = new DatagramPacket(buf, buf.length);
            s.receive(rp);
            byte[] out = new byte[rp.getLength()];
            System.arraycopy(buf, 0, out, 0, rp.getLength());
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    private static InetAddress upstream(Context c) {
        // VPN 开启时 getActiveNetwork() 可能是 VPN 自己，其 DNS 是 10.7.0.1，
        // 直接拿来透传会形成自环（用自己的假 DNS 解析自己）。这里只取物理网络
        // （Ethernet/WiFi/蜂窝）的 DNS，并排除 10.7.0.1。
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (android.net.Network n : cm.getAllNetworks()) {
                    try {
                        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        if (caps != null && caps.hasTransport(
                                android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            continue;
                        }
                        LinkProperties lp = cm.getLinkProperties(n);
                        if (lp == null) {
                            continue;
                        }
                        List<InetAddress> ds = lp.getDnsServers();
                        if (ds == null) {
                            continue;
                        }
                        for (InetAddress a : ds) {
                            if (a != null && !isFakeDns(a)) {
                                return a;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return InetAddress.getByName("223.5.5.5");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isFakeDns(InetAddress a) {
        try {
            return a.isLoopbackAddress()
                    || "10.7.0.1".equals(a.getHostAddress())
                    || a.getHostAddress().startsWith("10.7.0.");
        } catch (Throwable t) {
            return true;
        }
    }
}
