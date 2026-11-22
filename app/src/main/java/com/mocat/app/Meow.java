package com.mocat.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** 用合成的方式“喵”一声，不需要任何音频文件。 */
final class Meow {
    private static final int SR = 22050;
    private static final float[] PITCH = {1f, 1.16f, 0.86f};
    private final short[][] cache = new short[PITCH.length][];
    boolean enabled = true;

    void play(int variant) {
        if (!enabled) return;
        final int v = Math.max(0, Math.min(PITCH.length - 1, variant));
        new Thread(() -> {
            AudioTrack t = null;
            try {
                short[] pcm = get(v);
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SR)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .build();
                t.write(pcm, 0, pcm.length);
                t.play();
                Thread.sleep(pcm.length * 1000L / SR + 120);
            } catch (Exception ignored) {
            } finally {
                if (t != null) t.release();
            }
        }).start();
    }

    private synchronized short[] get(int v) {
        if (cache[v] == null) cache[v] = synth(PITCH[v]);
        return cache[v];
    }

    private static short[] synth(float pitch) {
        int n = (int) (SR * 0.55);
        short[] out = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f = t < 0.12 ? 520 + 380 * t / 0.12 : 900 - 300 * Math.min(1, (t - 0.12) / 0.34);
            f *= pitch;
            double fc = t < 0.15 ? 900 + 1000 * t / 0.15 : 1900 - 900 * Math.min(1, (t - 0.15) / 0.31);
            ph += 2 * Math.PI * f / SR;
            double s = 0;
            for (int k = 1; k <= 12; k++) {
                double fk = k * f;
                if (fk > SR / 2.0) break;
                double a = Math.exp(-Math.pow((fk - fc) / 520.0, 2)) + 0.12 / k;
                s += a * Math.sin(k * ph);
            }
            double env = t < 0.05 ? t / 0.05 : Math.exp(-(t - 0.05) * 6.5);
            double vib = 1 + 0.04 * Math.sin(2 * Math.PI * 7 * t);
            out[i] = (short) (Math.max(-1, Math.min(1, s * 0.2 * env * vib)) * 32767);
        }
        return out;
    }
}
