package com.phonesar.notes;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** 把任意音频文件解码成 16kHz 单声道 PCM（short[]）。 */
public class AudioDecode {

    private static final String TAG = "AudioDecode";
    public static final int TARGET_RATE = 16000;

    /** 解码结果。 */
    public static class Pcm {
        public final short[] samples;
        public final int rate;

        Pcm(short[] samples, int rate) {
            this.samples = samples;
            this.rate = rate;
        }

        public int durationSec() {
            return samples.length / rate;
        }

        public float[] slice(int startSample, int endSample) {
            int s = Math.max(0, startSample);
            int e = Math.min(samples.length, endSample);
            if (e <= s) return new float[0];
            float[] out = new float[e - s];
            for (int i = s; i < e; i++) out[i - s] = samples[i] / 32768f;
            return out;
        }
    }

    public static Pcm decode(File file) throws IOException {
        if (isWav(file)) {
            short[] s = readWav(file);
            if (s != null) return new Pcm(s, TARGET_RATE);
        }
        return decodeWithCodec(file);
    }

    /** 判断本机是否能解出音轨（用于导入时过滤不支持的格式）。 */
    public static boolean canDecode(File file) {
        if (isWav(file)) return true;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {
            }
        }
    }

    /* ---------------------------- WAV ---------------------------- */

    private static boolean isWav(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;
            return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取 PCM/float WAV，返回 16k 单声道。 */
    private static short[] readWav(File file) {
        try (FileChannel ch = new FileInputStream(file).getChannel()) {
            ByteBuffer head = ByteBuffer.allocate((int) Math.min(ch.size(), 4096)).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(head);
            head.flip();
            head.position(12);
            int fmt = 1, channels = 1, rate = 16000, bits = 16, dataOffset = -1, dataSize = 0;
            while (head.remaining() >= 8) {
                byte[] idb = new byte[4];
                head.get(idb);
                int size = head.getInt();
                String id = new String(idb, java.nio.charset.StandardCharsets.US_ASCII);
                if ("fmt ".equals(id)) {
                    fmt = head.getShort() & 0xffff;
                    channels = head.getShort() & 0xffff;
                    rate = head.getInt();
                    head.getInt();
                    head.getShort();
                    bits = head.getShort() & 0xffff;
                    head.position(head.position() + Math.max(0, size - 16));
                } else if ("data".equals(id)) {
                    dataOffset = head.position();
                    dataSize = size;
                    break;
                } else {
                    head.position(head.position() + size + (size & 1));
                }
            }
            if (dataOffset <= 0 || dataSize <= 0) return null;

            int bytesPerSample = Math.max(1, bits / 8);
            int frames = dataSize / (bytesPerSample * channels);
            short[] mono = new short[frames];
            ByteBuffer data = ByteBuffer.allocate(Math.min(dataSize, 1 << 20)).order(ByteOrder.LITTLE_ENDIAN);
            ch.position(dataOffset);
            int frame = 0;
            byte[] tail = new byte[0];
            while (frame < frames) {
                data.clear();
                int read = ch.read(data);
                if (read <= 0) break;
                data.flip();
                if (tail.length > 0) {
                    ByteBuffer merged = ByteBuffer.allocate(tail.length + read).order(ByteOrder.LITTLE_ENDIAN);
                    merged.put(tail).put(data).flip();
                    data = merged;
                }
                int frameBytes = bytesPerSample * channels;
                while (data.remaining() >= frameBytes && frame < frames) {
                    float acc = 0;
                    for (int c = 0; c < channels; c++) {
                        if (bits == 16) acc += data.getShort() / 32768f;
                        else if (bits == 8) acc += (data.get() & 0xff) / 128f - 1f;
                        else if (bits == 24) {
                            int b0 = data.get() & 0xff, b1 = data.get() & 0xff, b2 = data.get();
                            int v = (b2 << 16) | (b1 << 8) | b0;
                            acc += v / 8388608f;
                        } else if (bits == 32) acc += data.getFloat();
                        else data.get();
                    }
                    acc /= channels;
                    mono[frame++] = (short) Math.max(-32768, Math.min(32767, acc * 32767f));
                }
                tail = new byte[data.remaining()];
                data.get(tail);
            }
            if (frame < frames) {
                short[] trimmed = new short[frame];
                System.arraycopy(mono, 0, trimmed, 0, frame);
                mono = trimmed;
            }
            return resampleTo16k(mono, rate);
        } catch (Exception e) {
            Log.e(TAG, "readWav failed", e);
            return null;
        }
    }

    /* ------------------------- MediaCodec ------------------------- */

    private static Pcm decodeWithCodec(File file) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) throw new IOException("no audio track");
            ex.selectTrack(track);

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int srcChannels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            boolean isFloat = false;
            int channels = srcChannels;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(inIdx);
                        int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        ob.get(chunk);
                        raw.write(chunk, 0, chunk.length);
                    }
                    dec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = dec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (out.containsKey("pcm-encoding")) isFloat = out.getInteger("pcm-encoding") == 4;
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && info.size == 0) {
                        // 防止死循环
                    }
                }
            }

            byte[] all = raw.toByteArray();
            int frames;
            short[] mono;
            if (isFloat) {
                frames = all.length / (4 * Math.max(1, channels));
                mono = new short[frames];
                ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < frames; i++) {
                    float acc = 0;
                    for (int c = 0; c < channels; c++) acc += bb.getFloat();
                    acc /= Math.max(1, channels);
                    mono[i] = (short) Math.max(-32768, Math.min(32767, acc * 32767f));
                }
            } else {
                frames = all.length / (2 * Math.max(1, channels));
                mono = new short[frames];
                ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < frames; i++) {
                    int acc = 0;
                    for (int c = 0; c < channels; c++) acc += bb.getShort();
                    mono[i] = (short) (acc / Math.max(1, channels));
                }
            }
            return new Pcm(resampleTo16k(mono, srcRate), TARGET_RATE);
        } catch (Exception e) {
            throw new IOException("decode failed: " + e.getMessage(), e);
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {
            }
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                try {
                    dec.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /* --------------------------- 工具 --------------------------- */

    private static short[] resampleTo16k(short[] in, int inRate) {
        if (inRate == TARGET_RATE || inRate <= 0) return in;
        double ratio = (double) TARGET_RATE / inRate;
        int outLen = (int) (in.length * ratio);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = i / ratio;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = src - i0;
            out[i] = (short) (in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }

    /** 波形包络：buckets 个 0-100 的整数，用于界面画波形。 */
    public static int[] envelope(short[] pcm, int buckets) {
        int[] env = new int[buckets];
        if (pcm.length == 0) return env;
        double max = 1e-6;
        double[] rms = new double[buckets];
        int per = Math.max(1, pcm.length / buckets);
        for (int b = 0; b < buckets; b++) {
            int start = b * per;
            int end = Math.min(pcm.length, start + per);
            double acc = 0;
            for (int i = start; i < end; i++) {
                double v = pcm[i] / 32768.0;
                acc += v * v;
            }
            rms[b] = Math.sqrt(acc / Math.max(1, end - start));
            max = Math.max(max, rms[b]);
        }
        for (int b = 0; b < buckets; b++) {
            env[b] = (int) Math.round(Math.min(1.0, rms[b] / max) * 100);
        }
        return env;
    }
}
