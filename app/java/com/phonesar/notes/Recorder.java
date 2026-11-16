package com.phonesar.notes;

import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** 麦克风录音：AAC 44.1kHz 单声道写入 m4a，支持暂停/继续。 */
public class Recorder {

    private static final String TAG = "Recorder";
    private final File dir;
    private MediaRecorder recorder;
    private File output;
    private long segmentStart;
    private long accumulated;
    private boolean paused;

    public Recorder(File dir) {
        this.dir = dir;
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "mkdirs failed");
    }

    public boolean isRecording() {
        return recorder != null;
    }

    public boolean isPaused() {
        return paused;
    }

    public File output() {
        return output;
    }

    /** 已录时长（不含暂停期间）。 */
    public long elapsedMs() {
        if (recorder == null) return accumulated;
        return accumulated + (paused ? 0 : System.currentTimeMillis() - segmentStart);
    }

    public File start(String baseName) throws IOException {
        if (recorder != null) throw new IllegalStateException("already recording");
        output = new File(dir, baseName + ".m4a");
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioChannels(1);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setOutputFile(output.getAbsolutePath());
        recorder.prepare();
        recorder.start();
        segmentStart = System.currentTimeMillis();
        accumulated = 0;
        paused = false;
        return output;
    }

    /** 暂停；机型不支持时返回 false。 */
    public boolean pause() {
        if (recorder == null || paused) return false;
        try {
            recorder.pause();
        } catch (Exception e) {
            Log.e(TAG, "pause unsupported", e);
            return false;
        }
        accumulated += System.currentTimeMillis() - segmentStart;
        paused = true;
        return true;
    }

    /** 继续；机型不支持时返回 false。 */
    public boolean resume() {
        if (recorder == null || !paused) return false;
        try {
            recorder.resume();
        } catch (Exception e) {
            Log.e(TAG, "resume unsupported", e);
            return false;
        }
        segmentStart = System.currentTimeMillis();
        paused = false;
        return true;
    }

    /** 停止录音，返回有效时长（毫秒，不含暂停）。 */
    public long stop() {
        if (recorder == null) return accumulated;
        long dur = elapsedMs();
        try {
            recorder.stop();
        } catch (Exception e) {
            Log.e(TAG, "stop failed", e);
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        recorder = null;
        paused = false;
        accumulated = dur;
        return dur;
    }

    public void cancel() {
        stop();
        if (output != null && output.exists() && !output.delete()) Log.w(TAG, "delete failed");
    }
}
