// Decompiled by Jad v1.5.8f. Copyright 2001 Pavel Kouznetsov.
// Jad home page: http://www.kpdus.com/jad.html
// Decompiler options: packimports(3)
// Source File Name:   signlink.java

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.*;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.Receiver;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import javax.sound.sampled.FloatControl;

public class Signlink implements Runnable {

    public static final RandomAccessFile[] cache_idx = new RandomAccessFile[5];
    public static int uid;
    public static int storeid = 32;
    public static RandomAccessFile cache_dat = null;
    public static boolean active;
    public static int threadliveid;
    public static String dnsreq = null;
    public static String dns = null;
    public static int savelen;
    public static String savereq = null;
    public static byte[] savebuf = null;
    public static boolean midiplay;
    public static int midipos;
    public static String midi = null;
    public static int midivol;
    public static int midifade;
    public static boolean waveplay;
    public static int wavepos;
    public static String wave = null;
    public static int wavevol;
    public static boolean reporterror = true;

    private static Sequencer midiSequencer = null;
    private static Clip waveClip = null;
    private static Synthesizer midiSynthesizer = null;
    private static Transmitter midiTransmitter = null;
    private static Receiver midiReceiver = null;
    private static int midiVolumeLevel = 4;
    private static int waveVolumeLevel = 4;

    // Last MIDI bytes handed to midisave(). Kept verbatim so the browser Web
    // Audio bridge can play the song straight from memory (CheerpJ has no
    // javax.sound output line). playMidi() only receives a path, so we thread
    // the bytes through here instead.
    private static byte[] lastMidiBytes = null;
    private static int lastMidiLen = 0;

    /*
     * CheerpJ native bridge for browser Web Audio MIDI playback. Implemented in
     * JS as Java_Signlink_<name> and registered via cheerpjInit({ natives: { ... } }).
     * These are ONLY invoked when RspyClientSocketFactory.isWebClientMode() is
     * true; the desktop build never touches them.
     */
    private static native void rspyPlayMidiN(String b64, boolean loop);
    private static native void rspyStopMidiN();
    private static native void rspySetMidiVolumeN(int level);

    public static synchronized void setMidiVolumeLegacy(int legacyVolume) {
        midivol = legacyVolume;
        setMidiVolumeLevel(legacyVolumeToLevel(legacyVolume));
    }

    public static synchronized void setWaveVolumeLegacy(int legacyVolume) {
        wavevol = legacyVolume;
        setWaveVolumeLevel(legacyVolumeToLevel(legacyVolume));
    }

    private static int legacyVolumeToLevel(int legacyVolume) {
        if (legacyVolume <= -1200) return 1;
        if (legacyVolume <= -800) return 2;
        if (legacyVolume <= -400) return 3;
        return 4;
    }

    private static int clampVolumeLevel(int level) {
        if (level < 0) return 0;
        if (level > 4) return 4;
        return level;
    }

    public static int getMidiVolumeLevel() {
        return midiVolumeLevel;
    }

    public static int getWaveVolumeLevel() {
        return waveVolumeLevel;
    }

    private static int midiVolumeLevelToLegacy(int level) {
        level = clampVolumeLevel(level);
        if (level <= 0) return -1200;
        return -400 * (4 - level);
    }

    private static int midiControllerVolume(int level) {
        level = clampVolumeLevel(level);
        if (level <= 0) return 0;
        return Math.max(1, Math.min(127, (int) Math.round(127.0D * ((double) level / 4.0D))));
    }

    // Send the in-memory MIDI bytes to the browser Web Audio synth. Used in
    // web mode in place of the javax.sound sequencer, which has no audio line
    // under CheerpJ.
    private static void webPlayMidi(boolean loop) {
        if (lastMidiBytes == null || lastMidiLen <= 0) {
            System.out.println("[MIDI] no midi bytes available for web playback");
            return;
        }

        try {
            byte[] payload;
            if (lastMidiLen == lastMidiBytes.length) {
                payload = lastMidiBytes;
            } else {
                payload = new byte[lastMidiLen];
                System.arraycopy(lastMidiBytes, 0, payload, 0, lastMidiLen);
            }

            String b64 = Base64.getEncoder().encodeToString(payload);
            rspyPlayMidiN(b64, loop);
            System.out.println("[MIDI] web playback requested len=" + lastMidiLen
                    + " loop=" + loop + " volumeLevel=" + midiVolumeLevel);
        } catch (Throwable t) {
            System.out.println("[MIDI] web audio bridge unavailable: " + t);
        }
    }

    private static synchronized void applyMidiVolume() {
        if (RspyClientSocketFactory.isWebClientMode()) {
            try {
                rspySetMidiVolumeN(midiVolumeLevel);
            } catch (Throwable t) {
                System.out.println("[MIDI] web volume bridge unavailable: " + t);
            }
            return;
        }

        if (midiSynthesizer == null) {
            return;
        }

        try {
            int midiVolume = midiControllerVolume(midiVolumeLevel);
            MidiChannel[] channels = midiSynthesizer.getChannels();

            if (channels == null) {
                return;
            }

            for (MidiChannel channel : channels) {
                if (channel != null) {
                    channel.controlChange(7, midiVolume);
                    channel.controlChange(39, 0);
                }
            }
        } catch (Exception e) {
            System.out.println("[MIDI] failed to apply volume");
            e.printStackTrace();
        }
    }

    private static void applyClipGain(Clip clip) {
        if (clip == null) {
            return;
        }

        try {
            if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                return;
            }

            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float value;

            if (waveVolumeLevel <= 0) {
                value = gain.getMinimum();
            } else {
                value = (float) (20.0D * Math.log10((double) waveVolumeLevel / 4.0D));
            }

            if (value < gain.getMinimum()) value = gain.getMinimum();
            if (value > gain.getMaximum()) value = gain.getMaximum();

            gain.setValue(value);
        } catch (Exception e) {
            System.out.println("[WAVE] failed to apply volume");
            e.printStackTrace();
        }
    }

    public static synchronized void setMidiVolumeLevel(int level) {
        midiVolumeLevel = clampVolumeLevel(level);
        midivol = midiVolumeLevelToLegacy(midiVolumeLevel);

        if (midiVolumeLevel <= 0) {
            applyMidiVolume();
            stopMidi();
            return;
        }

        applyMidiVolume();
    }

    public static synchronized void setWaveVolumeLevel(int level) {
        waveVolumeLevel = clampVolumeLevel(level);
        wavevol = midiVolumeLevelToLegacy(waveVolumeLevel);

        if (waveVolumeLevel <= 0) {
            stopWave();
            return;
        }

        if (waveClip != null) {
            applyClipGain(waveClip);
        }
    }

    public static void startpriv() {
        threadliveid = (int) (Math.random() * 99999999D);

        if (active) {
            try {
                Thread.sleep(500L);
            } catch (Exception ignored) {
            }
            active = false;
        }

        dnsreq = null;
        savereq = null;
        Thread thread = new Thread(new Signlink());
        thread.setDaemon(true);
        thread.start();

        while (!active) {
            try {
                Thread.sleep(50L);
            } catch (Exception ignored) {
            }
        }
    }

        public static String findcachedir() {
        if ((storeid < 32) || (storeid > 34)) {
            storeid = 32;
        }

        /*
         * CheerpJ runs inside the browser and cannot write to a real local
         * C:/ or user-home path. Its /files/ mount is a persistent,
         * browser-backed filesystem. That is where the RuneScape cache
         * should live when running through CheerpJ.
         *
         * Keep the old desktop fallback so the client can still run normally
         * outside the browser.
         */
        String runtimeInfo = (
                System.getProperty("java.vm.name", "") + " " +
                System.getProperty("java.vendor", "") + " " +
                System.getProperty("java.runtime.name", "")
        ).toLowerCase();

        if (runtimeInfo.contains("cheerp")) {
            String path = "/files/.file_store_" + storeid + "/";
            try {
                File dir = new File(path);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                if (dir.exists() && dir.isDirectory()) {
                    return path;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Last resort inside CheerpJ. Do not use /files/downloads/
            // for live cache, because that triggers browser downloads.
            return "/files/";
        }

        String[] as = {
                "c:/windows/",
                "c:/winnt/",
                "d:/windows/",
                "d:/winnt/",
                "e:/windows/",
                "e:/winnt/",
                "f:/windows/",
                "f:/winnt/",
                "c:/",
                System.getProperty("user.home") + "/",
                "/tmp/",
                "",
                "c:/rscache",
                "/rscache"
        };

        String s = ".file_store_" + storeid;

        for (String a : as) {
            try {
                if (a.length() > 0) {
                    File file = new File(a);
                    if (!file.exists()) {
                        continue;
                    }
                }

                File file1 = new File(a + s);
                if (file1.exists() || file1.mkdir()) {
                    return a + s + "/";
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }


    public static int getuid(String s) {
        Path path = Paths.get(s + "uid.dat");

        try {
            File file = new File(s + "uid.dat");

            if (!file.exists() || (file.length() < 4L)) {
                DataOutputStream out = new DataOutputStream(Files.newOutputStream(path));
                out.writeInt((int) (Math.random() * 99999999D));
                out.close();
            }
        } catch (Exception ignored) {
        }

        try {
            DataInputStream in = new DataInputStream(Files.newInputStream(path));
            int uid = in.readInt();
            in.close();
            return uid + 1;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static synchronized void dnslookup(String dns) {
        Signlink.dns = dns;
        dnsreq = dns;
    }

    public static synchronized boolean wavesave(byte[] src, int len) {
        if (len > 2000000) {
            return false;
        }

        if (savereq != null) {
            return false;
        }

        wavepos = (wavepos + 1) % 5;
        savelen = len;
        savebuf = src;
        waveplay = true;
        savereq = "sound" + wavepos + ".wav";
        return true;
    }

    public static synchronized boolean wavereplay() {
        if (savereq != null) {
            return false;
        }

        savebuf = null;
        waveplay = true;
        savereq = "sound" + wavepos + ".wav";
        return true;
    }

    public static synchronized void midisave(byte[] data, int len) {
        if (len > 0x1e8480) {
            System.out.println("[MIDI] rejected oversized midi len=" + len);
            return;
        }

        if (savereq == null) {
            midipos = (midipos + 1) % 5;
            savelen = len;
            savebuf = data;
            lastMidiBytes = data;
            lastMidiLen = len;
            midiplay = true;
            savereq = "jingle" + midipos + ".mid";

            System.out.println("[MIDI] queued midi save len=" + len + " file=" + savereq);
        } else {
            System.out.println("[MIDI] save request busy, dropped midi len=" + len);
        }
    }

        public static synchronized void stopMidi() {
        if (RspyClientSocketFactory.isWebClientMode()) {
            try {
                rspyStopMidiN();
            } catch (Throwable t) {
                System.out.println("[MIDI] web stop bridge unavailable: " + t);
            }
            return;
        }

        try {
            if (midiSequencer != null) {
                midiSequencer.stop();
                midiSequencer.close();
                midiSequencer = null;
            }

            if (midiTransmitter != null) {
                midiTransmitter.close();
                midiTransmitter = null;
            }

            if (midiReceiver != null) {
                midiReceiver.close();
                midiReceiver = null;
            }

            if (midiSynthesizer != null) {
                midiSynthesizer.close();
                midiSynthesizer = null;
            }
        } catch (Exception e) {
            System.out.println("[MIDI] failed to stop midi");
            e.printStackTrace();
        }
    }

        public static synchronized void playMidi(String path) {
        if (RspyClientSocketFactory.isWebClientMode()) {
            // CheerpJ cannot open a javax.sound output line. Route the MIDI
            // bytes to the browser Web Audio synth instead. Songs (midifade==1)
            // loop; jingles (midifade==0) play once, mirroring the desktop
            // sequencer's behaviour.
            if (midiVolumeLevel <= 0) {
                System.out.println("[MIDI] suppressed because music volume is Off");
                return;
            }
            webPlayMidi(midifade != 0);
            return;
        }

        try {
            stopMidi();

            if (midiVolumeLevel <= 0) {
                System.out.println("[MIDI] suppressed because music volume is Off");
                return;
            }

            File midiFile = new File(path);
            if (!midiFile.exists()) {
                System.out.println("[MIDI] file does not exist: " + path);
                return;
            }

            if (midiFile.length() <= 0) {
                System.out.println("[MIDI] file is empty: " + path);
                return;
            }

            Sequence sequence = MidiSystem.getSequence(midiFile);

            try {
                midiSequencer = MidiSystem.getSequencer(false);
                midiSynthesizer = MidiSystem.getSynthesizer();

                midiSynthesizer.open();
                midiReceiver = midiSynthesizer.getReceiver();

                midiSequencer.open();
                midiTransmitter = midiSequencer.getTransmitter();
                midiTransmitter.setReceiver(midiReceiver);
            } catch (Exception synthException) {
                System.out.println("[MIDI] custom synth path failed; using default sequencer");

                if (midiTransmitter != null) {
                    midiTransmitter.close();
                    midiTransmitter = null;
                }

                if (midiReceiver != null) {
                    midiReceiver.close();
                    midiReceiver = null;
                }

                if (midiSynthesizer != null) {
                    midiSynthesizer.close();
                    midiSynthesizer = null;
                }

                midiSequencer = MidiSystem.getSequencer();

                if (midiSequencer == null) {
                    System.out.println("[MIDI] no MIDI sequencer available");
                    return;
                }

                midiSequencer.open();
            }

            midiSequencer.setSequence(sequence);
            midiSequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
            applyMidiVolume();
            midiSequencer.start();

            System.out.println("[MIDI] playing midi: " + path + " size=" + midiFile.length() + " volumeLevel=" + midiVolumeLevel);
        } catch (Exception e) {
            System.out.println("[MIDI] failed to play midi: " + path);
            e.printStackTrace();
        }
    }

    public static synchronized void stopWave() {
        try {
            if (waveClip != null) {
                waveClip.stop();
                waveClip.close();
                waveClip = null;
            }
        } catch (Exception e) {
            System.out.println("[WAVE] failed to stop wave");
            e.printStackTrace();
        }
    }

        public static synchronized void playWave(String path) {
        if (RspyClientSocketFactory.isWebClientMode()) {
            // No javax.sound output line under CheerpJ. Sound effects are not
            // yet routed to Web Audio; no-op so it never throws "Can not open
            // line". MIDI music is the priority.
            return;
        }

        try {
            stopWave();

            if (waveVolumeLevel <= 0) {
                System.out.println("[WAVE] suppressed because sound effect volume is Off");
                return;
            }

            File waveFile = new File(path);
            if (!waveFile.exists()) {
                System.out.println("[WAVE] file does not exist: " + path);
                return;
            }

            if (waveFile.length() <= 0) {
                System.out.println("[WAVE] file is empty: " + path);
                return;
            }

            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(waveFile);
            waveClip = AudioSystem.getClip();
            waveClip.open(audioInputStream);
            applyClipGain(waveClip);
            waveClip.start();

            System.out.println("[WAVE] playing wave: " + path + " size=" + waveFile.length() + " volumeLevel=" + waveVolumeLevel);
        } catch (Exception e) {
            System.out.println("[WAVE] failed to play wave: " + path);
            e.printStackTrace();
        }
    }

    public static void reporterror(String s) {
        if (!reporterror) {
            return;
        }

        if (!active) {
            return;
        }

        System.out.println("Error: " + s);
    }

    public Signlink() {
    }

    @Override
    public void run() {
        active = true;

        String cachedir = findcachedir();
        System.out.println(cachedir);
        uid = getuid(cachedir);

        try {
            cache_dat = new RandomAccessFile(cachedir + "main_file_cache.dat", "rw");
            for (int j = 0; j < 5; j++) {
                cache_idx[j] = new RandomAccessFile(cachedir + "main_file_cache.idx" + j, "rw");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int i = threadliveid; threadliveid == i; ) {
            if (dnsreq != null) {
                try {
                    dns = InetAddress.getByName(dnsreq).getHostName();
                } catch (Exception _ex) {
                    dns = "unknown";
                }

                dnsreq = null;

            } else if (savereq != null) {
                String savedPath = cachedir + savereq;

                if (savebuf != null) {
                    try {
                        FileOutputStream fileoutputstream = new FileOutputStream(savedPath);
                        fileoutputstream.write(savebuf, 0, savelen);
                        fileoutputstream.close();

                        System.out.println("[MEDIA] saved file: " + savedPath + " len=" + savelen);
                    } catch (Exception e) {
                        System.out.println("[MEDIA] failed saving file: " + savedPath);
                        e.printStackTrace();
                    }
                }

                if (waveplay) {
                    wave = savedPath;
                    waveplay = false;
                    playWave(wave);
                }

                if (midiplay) {
                    midi = savedPath;
                    midiplay = false;
                    playMidi(midi);
                }

                savereq = null;
            }

            try {
                Thread.sleep(50L);
            } catch (Exception ignored) {
            }
        }

        stopMidi();
        stopWave();
    }
}