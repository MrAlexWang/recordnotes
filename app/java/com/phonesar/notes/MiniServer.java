package com.phonesar.notes;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简本地 HTTP 服务：给 WebView 提供界面资源与录音文件（支持 Range，用于音频拖动）。
 * 只监听 127.0.0.1，端口随机。
 */
public class MiniServer {

    private static final String TAG = "MiniServer";
    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("m4a", "audio/mp4");
        MIME.put("mp4", "audio/mp4");
        MIME.put("aac", "audio/aac");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("wav", "audio/wav");
        MIME.put("flac", "audio/flac");
        MIME.put("ogg", "audio/ogg");
        MIME.put("amr", "audio/amr");
        MIME.put("txt", "text/plain; charset=utf-8");
    }

    private final ServerSocket server;
    private final AssetManager assets;
    private final File audioDir;
    private final Context ctx;
    private volatile boolean running = true;

    /** 记录 id → 原始音频 Uri（导入的音频只做链接，不复制文件）。 */
    public interface SrcResolver {
        android.net.Uri resolve(String id);
    }

    private SrcResolver srcResolver;

    public void setSrcResolver(SrcResolver r) {
        this.srcResolver = r;
    }

    public MiniServer(Context ctx, File audioDir) throws IOException {
        this.ctx = ctx.getApplicationContext();
        this.assets = ctx.getAssets();
        this.audioDir = audioDir;
        this.server = new ServerSocket(0, 60, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::loop, "mini-http");
        t.setDaemon(true);
        t.start();
        Log.w(TAG, "listening on " + port());
    }

    public int port() {
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            server.close();
        } catch (Exception ignored) {
        }
    }

    private void loop() {
        while (running) {
            try {
                Socket s = server.accept();
                new Thread(() -> handle(s), "http-conn").start();
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String range = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && "range".equalsIgnoreCase(line.substring(0, c).trim())) {
                    range = line.substring(c + 1).trim();
                }
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            path = java.net.URLDecoder.decode(path, "UTF-8");

            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);

            if (path.startsWith("/www/")) {
                serveAsset(out, path.substring(5));
            } else if (path.startsWith("/audio/")) {
                serveFile(out, new File(audioDir, path.substring(7)), range);
            } else if (path.startsWith("/src/")) {
                serveSrc(out, path.substring(5), range);
            } else if ("/".equals(path)) {
                writeRedirect(out, "/www/index.html");
            } else {
                writeText(out, 404, "not found");
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "handle error: " + e.getMessage());
        }
    }

    private void serveAsset(OutputStream out, String rel) {
        try (InputStream in = assets.open("www/" + rel)) {
            byte[] data = readAll(in);
            String mime = mimeOf(rel);
            String head = "HTTP/1.1 200 OK\r\nContent-Type: " + mime
                    + "\r\nContent-Length: " + data.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(data);
        } catch (Exception e) {
            writeText(out, 404, "asset not found: " + rel);
        }
    }

    private void serveFile(OutputStream out, File f, String rangeHeader) {
        if (!f.exists() || !f.isFile()) {
            writeText(out, 404, "audio not found");
            return;
        }
        try {
            long total = f.length();
            long start = 0, end = total - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String spec = rangeHeader.substring(6);
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (!a.isEmpty()) start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Long.parseLong(b);
                    if (end >= total) end = total - 1;
                    if (start <= end) partial = true;
                }
            }
            long len = end - start + 1;
            String mime = mimeOf(f.getName());
            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(mime).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Content-Length: ").append(len).append("\r\n");
            if (partial) {
                head.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(total).append("\r\n");
            }
            head.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));

            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int r = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r <= 0) break;
                    out.write(buf, 0, r);
                    left -= r;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "serveFile: " + e.getMessage());
        }
    }

    /** 播放导入的音频：直接代理原始 content Uri，不复制文件；尽量支持 Range 以便拖动。 */
    private void serveSrc(OutputStream out, String id, String rangeHeader) {
        if (srcResolver == null) {
            writeText(out, 404, "no resolver");
            return;
        }
        android.net.Uri uri = srcResolver.resolve(id);
        if (uri == null) {
            writeText(out, 404, "src not found");
            return;
        }
        android.os.ParcelFileDescriptor pfd = null;
        java.io.FileInputStream fis = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) {
                writeText(out, 404, "src unreadable");
                return;
            }
            long total = pfd.getStatSize();
            long start = 0;
            long end = total > 0 ? total - 1 : -1;
            boolean partial = false;
            if (total > 0 && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String spec = rangeHeader.substring(6);
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    try {
                        if (!a.isEmpty()) start = Long.parseLong(a);
                        if (!b.isEmpty()) end = Long.parseLong(b);
                    } catch (NumberFormatException ignored) {
                    }
                    if (start < 0) start = 0;
                    if (end < 0 || end >= total) end = total - 1;
                    if (start > end) start = 0;
                    partial = true;
                }
            }

            fis = new java.io.FileInputStream(pfd.getFileDescriptor());
            java.nio.channels.FileChannel ch = fis.getChannel();
            if (start > 0) {
                try {
                    ch.position(start);
                } catch (Exception e) {
                    long skipped = 0;
                    while (skipped < start) {
                        long n = fis.skip(start - skipped);
                        if (n <= 0) break;
                        skipped += n;
                    }
                }
            }

            long len = total > 0 ? (end - start + 1) : -1;
            String mime = ctx.getContentResolver().getType(uri);
            if (mime == null) mime = "audio/mpeg";

            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(mime).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            if (total > 0) {
                if (partial) {
                    head.append("Content-Range: bytes ").append(start).append('-')
                            .append(end).append('/').append(total).append("\r\n");
                }
                head.append("Content-Length: ").append(len).append("\r\n");
            }
            head.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));

            byte[] buf = new byte[64 * 1024];
            long remain = len;
            while (remain != 0) {
                int want = remain > 0 ? (int) Math.min(buf.length, remain) : buf.length;
                int r = fis.read(buf, 0, want);
                if (r <= 0) break;
                out.write(buf, 0, r);
                if (remain > 0) remain -= r;
            }
        } catch (Exception e) {
            Log.w(TAG, "serveSrc failed: " + e.getMessage());
        } finally {
            try {
                if (fis != null) fis.close();
            } catch (Exception ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void writeText(OutputStream out, int code, String text) {
        try {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " " + (code == 404 ? "Not Found" : "OK")
                    + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
        } catch (Exception ignored) {
        }
    }

    private void writeRedirect(OutputStream out, String to) {
        try {
            String head = "HTTP/1.1 302 Found\r\nLocation: " + to + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase();
        String m = MIME.get(ext);
        return m == null ? "application/octet-stream" : m;
    }
}
