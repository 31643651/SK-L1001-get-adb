package com.c10h.launcher.block;

import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * TUN 读循环（补充文档 §4）：IPv4 UDP dst:53 → DnsProxy；其余一律丢。
 * 应答包重建：交换 IP/端口、重算 IPv4 头校验和（UDP 校验 0 合法）。
 */
final class TunReader implements Runnable {

    private final BlockVpnService svc;
    private final FileInputStream in;
    private final FileOutputStream out;
    private volatile boolean running = true;

    TunReader(BlockVpnService s, FileInputStream i, FileOutputStream o) {
        this.svc = s;
        this.in = i;
        this.out = o;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        byte[] buf = new byte[32767];
        while (running) {
            try {
                int n = in.read(buf);
                if (n > 0) {
                    handle(buf, n);
                }
            } catch (Throwable t) {
                break;
            }
        }
    }

    private void handle(byte[] b, int n) {
        if (n < 28 || ((b[0] & 0xF0) >> 4) != 4) {
            return;
        }
        int ihl = (b[0] & 0x0F) * 4;
        if (ihl < 20 || n < ihl + 8 || b[9] != 17) {
            return;
        }
        int dstPort = ((b[ihl + 2] & 0xFF) << 8) | (b[ihl + 3] & 0xFF);
        if (dstPort != 53) {
            return;
        }
        int dnsLen = n - ihl - 8;
        byte[] q = new byte[dnsLen];
        System.arraycopy(b, ihl + 8, q, 0, dnsLen);
        String host = DnsProxy.qname(q, 0, dnsLen);
        byte[] resp;
        if (host != null && RuleStore.blocked(svc, host)) {
            resp = DnsProxy.blockedResponse(q, dnsLen);
        } else {
            resp = DnsProxy.passthrough(svc, svc, q, dnsLen);
        }
        reply(b, ihl, resp);
    }

    private void reply(byte[] req, int ihl, byte[] dns) {
        if (dns == null || dns.length == 0) {
            return;
        }
        try {
            int udpLen = 8 + dns.length;
            int total = ihl + udpLen;
            byte[] p = new byte[total];
            System.arraycopy(req, 0, p, 0, ihl);
            System.arraycopy(req, 16, p, 12, 4);
            System.arraycopy(req, 12, p, 16, 4);
            p[2] = (byte) (total >> 8);
            p[3] = (byte) total;
            p[8] = 64;
            int u = ihl;
            p[u] = req[u + 2];
            p[u + 1] = req[u + 3];
            p[u + 2] = req[u];
            p[u + 3] = req[u + 1];
            p[u + 4] = (byte) (udpLen >> 8);
            p[u + 5] = (byte) udpLen;
            p[u + 6] = 0;
            p[u + 7] = 0;
            System.arraycopy(dns, 0, p, u + 8, dns.length);
            p[10] = 0;
            p[11] = 0;
            int sum = 0;
            for (int k = 0; k < ihl; k += 2) {
                sum += ((p[k] & 0xFF) << 8) | (p[k + 1] & 0xFF);
            }
            while ((sum >> 16) != 0) {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
            sum = ~sum & 0xFFFF;
            p[10] = (byte) (sum >> 8);
            p[11] = (byte) sum;
            out.write(p);
            out.flush();
        } catch (Throwable ignored) {
        }
    }
}
