package app.soundpad;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Работает в процессе с uid shell (через Shizuku). */
public class Injector extends IInjector.Stub {
    private static final String TAG = "SoundpadInjector";
    private static final int FRAME_BYTES = 960;
    private static final int[] PRESETS = {0, 1, 5, 6, 7, 9, 10};
    private static final String SHELL_PKG = "com.android.shell";

    private final Context base;
    private Object policy;
    private AudioManager audio;
    private AudioTrack track;
    private Looper policyLooper;
    private ParcelFileDescriptor pcmFd;
    private volatile boolean running;
    private int session;

    public Injector() {
        this(null);
    }

    public Injector(Context context) {
        base = context;
    }

    @Override
    public void destroy() {
        stop();
        System.exit(0);
    }

    @Override
    public synchronized String start(final ParcelFileDescriptor pcm, final int rate, final int excludeUid) {
        stopInternal();
        final String[] res = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                Looper.prepare();
                policyLooper = Looper.myLooper();
                res[0] = setup(pcm, rate, excludeUid);
            } catch (Throwable e) {
                res[0] = "ошибка: " + trace(e);
            }
            latch.countDown();
            if (res[0] != null && res[0].startsWith("ok")) {
                Looper.loop();
            }
        }, "inj-policy");
        t.start();
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        String r = res[0] == null ? "таймаут настройки" : res[0];
        if (!r.startsWith("ok")) {
            stopInternal();
            try {
                pcm.close();
            } catch (IOException ignored) {
            }
        }
        return r;
    }

    @Override
    public synchronized void stop() {
        stopInternal();
    }

    @Override
    public String probe() {
        int res = -1;
        try {
            Context c = shellContext();
            res = c.checkPermission("android.permission.MODIFY_AUDIO_ROUTING", Process.myPid(), Process.myUid());
        } catch (Throwable ignored) {
        }
        return "uid=" + Process.myUid() + " sdk=" + Build.VERSION.SDK_INT
                + " " + Build.MANUFACTURER + " " + Build.MODEL
                + " MODIFY_AUDIO_ROUTING=" + (res == PackageManager.PERMISSION_GRANTED ? "есть" : "нет");
    }

    private String setup(ParcelFileDescriptor pcm, int rate, int excludeUid) throws Exception {
        Context ctx = shellContext();
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        Throwable last = null;
        int[] masks = {AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO};
        for (int mask : masks) {
            boolean excluded = false;
            try {
                Class<?> cRule = Class.forName("android.media.audiopolicy.AudioMixingRule");
                Class<?> cRuleB = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
                Class<?> cMix = Class.forName("android.media.audiopolicy.AudioMix");
                Class<?> cMixB = Class.forName("android.media.audiopolicy.AudioMix$Builder");
                Class<?> cPol = Class.forName("android.media.audiopolicy.AudioPolicy");
                Class<?> cPolB = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

                int roleInjector = cRule.getField("MIX_ROLE_INJECTOR").getInt(null);
                int matchPreset = cRule.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null);
                int matchUid = cRule.getField("RULE_MATCH_UID").getInt(null);
                int loopBack = cMix.getField("ROUTE_FLAG_LOOP_BACK").getInt(null);

                Object rb = cRuleB.getConstructor().newInstance();
                cRuleB.getMethod("setTargetMixRole", int.class).invoke(rb, roleInjector);
                Method setPreset = AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class);
                Method addRule = cRuleB.getMethod("addMixRule", int.class, Object.class);
                int added = 0;
                for (int p : PRESETS) {
                    try {
                        AudioAttributes.Builder ab = new AudioAttributes.Builder();
                        setPreset.invoke(ab, p);
                        addRule.invoke(rb, matchPreset, ab.build());
                        added++;
                    } catch (Throwable ignored) {
                    }
                }
                if (added == 0) {
                    return "ошибка: ни один пресет записи не принят";
                }
                try {
                    cRuleB.getMethod("excludeMixRule", int.class, Object.class)
                            .invoke(rb, matchUid, Integer.valueOf(excludeUid));
                    excluded = true;
                } catch (Throwable e) {
                    Log.w(TAG, "exclude uid", e);
                }
                Object rule = cRuleB.getMethod("build").invoke(rb);

                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build();
                Object mb = cMixB.getConstructor(cRule).newInstance(rule);
                cMixB.getMethod("setFormat", AudioFormat.class).invoke(mb, fmt);
                cMixB.getMethod("setRouteFlags", int.class).invoke(mb, loopBack);
                Object mix = cMixB.getMethod("build").invoke(mb);

                Object pb = cPolB.getConstructor(Context.class).newInstance(ctx);
                cPolB.getMethod("addMix", cMix).invoke(pb, mix);
                try {
                    cPolB.getMethod("setLooper", Looper.class).invoke(pb, policyLooper);
                } catch (Throwable ignored) {
                }
                policy = cPolB.getMethod("build").invoke(pb);

                int r = (Integer) AudioManager.class.getMethod("registerAudioPolicy", cPol).invoke(audio, policy);
                if (r != 0) {
                    unregister();
                    return "ошибка: registerAudioPolicy вернул " + r;
                }
                track = (AudioTrack) cPol.getMethod("createAudioTrackSource", cMix).invoke(policy, mix);
                track.play();
                pcmFd = pcm;
                startPump(pcm, track);
                return excluded ? "ok" : "ok:noexclude";
            } catch (Throwable e) {
                last = e;
                Log.w(TAG, "setup mask=" + mask, e);
                if (track != null) {
                    try {
                        track.release();
                    } catch (Throwable ignored) {
                    }
                    track = null;
                }
                unregister();
            }
        }
        return "ошибка: " + trace(last);
    }

    private void startPump(final ParcelFileDescriptor pcm, final AudioTrack t) {
        running = true;
        final int id = ++session;
        Thread th = new Thread(() -> {
            final int ch = Math.max(1, t.getChannelCount());
            final byte[] raw = new byte[FRAME_BYTES];
            final short[] mono = new short[FRAME_BYTES / 2];
            final short[] out = ch == 1 ? mono : new short[mono.length * ch];
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pcm)) {
                while (running) {
                    readFully(in, raw);
                    int backlog = in.available();
                    while (backlog >= FRAME_BYTES * 4) {
                        long s = in.skip(FRAME_BYTES);
                        if (s <= 0) break;
                        backlog -= (int) s;
                    }
                    for (int i = 0; i < mono.length; i++) {
                        mono[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
                    }
                    if (ch > 1) {
                        for (int i = 0; i < mono.length; i++) {
                            for (int c = 0; c < ch; c++) out[i * ch + c] = mono[i];
                        }
                    }
                    int off = 0;
                    while (off < out.length && running) {
                        int n = t.write(out, off, out.length - off);
                        if (n < 0) throw new IOException("AudioTrack.write " + n);
                        off += n;
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "pump", e);
            } finally {
                new Thread(() -> {
                    synchronized (Injector.this) {
                        if (id == session) stopInternal();
                    }
                }).start();
            }
        }, "inj-pump");
        th.start();
    }

    private void stopInternal() {
        running = false;
        session++;
        if (pcmFd != null) {
            try {
                pcmFd.close();
            } catch (Throwable ignored) {
            }
            pcmFd = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
            track = null;
        }
        unregister();
        if (policyLooper != null) {
            policyLooper.quit();
            policyLooper = null;
        }
    }

    private void unregister() {
        if (policy == null || audio == null) return;
        try {
            Class<?> cPol = Class.forName("android.media.audiopolicy.AudioPolicy");
            try {
                AudioManager.class.getMethod("unregisterAudioPolicy", cPol).invoke(audio, policy);
            } catch (NoSuchMethodException e) {
                AudioManager.class.getMethod("unregisterAudioPolicyAsync", cPol).invoke(audio, policy);
            }
        } catch (Throwable e) {
            Log.w(TAG, "unregister", e);
        }
        policy = null;
    }

    private Context shellContext() throws Exception {
        Context c = base;
        if (c == null) {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object t = at.getMethod("systemMain").invoke(null);
            c = (Context) at.getMethod("getSystemContext").invoke(t);
        }
        return new ShellContext(c);
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) {
            int n = in.read(b, o, b.length - o);
            if (n < 0) throw new IOException("eof");
            o += n;
        }
    }

    private static String trace(Throwable e) {
        if (e == null) return "неизвестно";
        Throwable c = e;
        while (c.getCause() != null && c != c.getCause()) c = c.getCause();
        StringWriter sw = new StringWriter();
        c.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 1500 ? s.substring(0, 1500) : s;
    }

    private static class ShellContext extends ContextWrapper {
        ShellContext(Context b) {
            super(b);
        }

        @Override
        public String getPackageName() {
            return SHELL_PKG;
        }

        public String getOpPackageName() {
            return SHELL_PKG;
        }

        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(2000).setPackageName(SHELL_PKG).build();
        }
    }
}
