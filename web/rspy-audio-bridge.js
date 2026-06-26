/*
 * rspy-audio-bridge.js
 *
 * Browser side of the RsPy web-client MIDI music output. CheerpJ has no
 * javax.sound output line, so Signlink.playMidi() under CheerpJ routes the
 * raw Standard MIDI File bytes here instead, through CheerpJ native methods
 * (Java_Signlink_rspy*). Bytes cross the native boundary as Base64 strings,
 * matching the rspy-ws-bridge.js convention.
 *
 * The actual synthesis is done by js-synthesizer (libfluidsynth compiled to
 * WebAssembly), loaded from the jsDelivr CDN by index.html:
 *   externals/libfluidsynth-2.4.6.js   (the WASM + glue)
 *   dist/js-synthesizer.js             (the JSSynth API)
 * A General MIDI SoundFont (TimGM6mb.sf2) is served same-origin at /gm.sf2.
 *
 * Exposed to Java (via index.html natives):
 *   window.rspyPlayMidi(base64Bytes, loop)
 *   window.rspyStopMidi()
 *   window.rspySetMidiVolume(level)   // 0..4 (client music-volume level)
 *
 * Autoplay policy: browsers start the AudioContext suspended until a user
 * gesture. We arm one-time pointerdown/keydown listeners that resume the
 * context; any song requested before that (e.g. the title jingle on load) is
 * queued and played the moment audio is unlocked.
 */
(function () {
    "use strict";

    // Same-origin GM SoundFont (copied into the nginx html dir by web/Dockerfile).
    var SF2_URL = "/gm.sf2";

    // js-synthesizer / fluidsynth player loop counts.
    var LOOP_INFINITE = -1; // songs loop forever
    var LOOP_ONCE = 1;      // jingles play once

    // ScriptProcessorNode buffer size (frames). Larger = fewer glitches, more
    // latency; 8192 is the value used in the js-synthesizer examples.
    var AUDIO_BUFFER_FRAMES = 8192;

    var audioCtx = null;     // Web Audio context (starts suspended)
    var gainNode = null;     // master volume, sits between synth and output
    var synthNode = null;    // ScriptProcessorNode produced by the synth
    var synth = null;        // JSSynth.Synthesizer
    var sfontPromise = null; // Promise<ArrayBuffer> of the soundfont
    var readyPromise = null; // Promise resolved once synth + soundfont are ready
    var pending = null;      // { bytes: Uint8Array, loop: bool } queued for unlock
    var gestureHooked = false;
    var currentGain = gainForLevel(4); // default to full music volume

    function log(msg) {
        try { console.log("[MIDI-JS] " + msg); } catch (e) { /* ignore */ }
    }

    function b64decode(str) {
        var bin = atob(str);
        var out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) {
            out[i] = bin.charCodeAt(i) & 0xff;
        }
        return out;
    }

    // Map the client's music-volume level (0..4) to a Web Audio gain. fluidsynth
    // output is already fairly hot, so keep the ceiling modest to avoid clipping.
    function gainForLevel(level) {
        level = Number(level);
        if (isNaN(level) || level <= 0) return 0;
        if (level > 4) level = 4;
        return (level / 4) * 0.5; // 0.125 / 0.25 / 0.375 / 0.5
    }

    function ensureContext() {
        if (audioCtx) return audioCtx;
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) {
            log("Web Audio not supported in this browser");
            return null;
        }
        audioCtx = new AC();
        gainNode = audioCtx.createGain();
        gainNode.gain.value = currentGain;
        gainNode.connect(audioCtx.destination);
        return audioCtx;
    }

    function fetchSoundfont() {
        if (sfontPromise) return sfontPromise;
        sfontPromise = fetch(SF2_URL).then(function (resp) {
            if (!resp.ok) {
                throw new Error("soundfont HTTP " + resp.status + " for " + SF2_URL);
            }
            return resp.arrayBuffer();
        });
        return sfontPromise;
    }

    // Lazily build the synth graph and load the soundfont. Safe to call before a
    // user gesture: the AudioContext is simply created suspended.
    function ensureSynth() {
        if (readyPromise) return readyPromise;

        if (!window.JSSynth || typeof window.JSSynth.waitForReady !== "function") {
            readyPromise = Promise.reject(new Error("js-synthesizer (JSSynth) not loaded"));
            return readyPromise;
        }

        readyPromise = Promise.all([
            window.JSSynth.waitForReady(),
            fetchSoundfont()
        ]).then(function (results) {
            var sfontBuffer = results[1];
            var ctx = ensureContext();
            if (!ctx) throw new Error("no AudioContext");

            synth = new window.JSSynth.Synthesizer();
            synth.init(ctx.sampleRate);

            synthNode = synth.createAudioNode(ctx, AUDIO_BUFFER_FRAMES);
            synthNode.connect(gainNode);

            return synth.loadSFont(sfontBuffer);
        }).then(function (sfontId) {
            log("synth ready, soundfont loaded id=" + sfontId);
        });

        return readyPromise;
    }

    function doPlay(bytes, loop) {
        return ensureSynth().then(function () {
            if (audioCtx && audioCtx.state === "suspended") {
                return audioCtx.resume();
            }
        }).then(function () {
            synth.stopPlayer();
            return synth.resetPlayer();
        }).then(function () {
            return synth.addSMFDataToPlayer(bytes.buffer);
        }).then(function () {
            synth.setPlayerLoop(loop ? LOOP_INFINITE : LOOP_ONCE);
            return synth.playPlayer();
        }).then(function () {
            log("playing midi len=" + bytes.length + " loop=" + loop);
        });
    }

    function flushPending() {
        if (!pending) return;
        if (audioCtx && audioCtx.state === "suspended") return; // still locked
        var req = pending;
        pending = null;
        doPlay(req.bytes, req.loop).catch(function (e) {
            log("queued play failed: " + e);
        });
    }

    // Arm gesture listeners that resume the AudioContext (browser autoplay
    // policy). Kept attached and idempotent: resuming an already-running context
    // is a no-op, and this guarantees recovery even if the first gesture races
    // the synth warm-up.
    function hookGesture() {
        if (gestureHooked) return;
        gestureHooked = true;

        var handler = function () {
            var ctx = ensureContext();
            if (!ctx) return;
            ctx.resume().then(function () {
                if (ctx.state === "running") {
                    log("AudioContext resumed by user gesture");
                    flushPending();
                }
            }).catch(function (e) {
                log("resume failed: " + e);
            });
        };

        window.addEventListener("pointerdown", handler, { passive: true });
        window.addEventListener("keydown", handler, { passive: true });
    }

    // ---- API reached from Java via the CheerpJ natives in index.html ----

    window.rspyPlayMidi = function (b64, loop) {
        try {
            var bytes = b64decode(b64);
            var req = { bytes: bytes, loop: !!loop };

            hookGesture();
            var ctx = ensureContext();
            // Warm up the synth/soundfont regardless so playback is instant once
            // audio is unlocked.
            ensureSynth().catch(function (e) { log("synth init failed: " + e); });

            if (!ctx || ctx.state === "suspended") {
                pending = req;
                log("queued midi until user gesture (audio suspended)");
                if (ctx) ctx.resume().then(flushPending).catch(function () { /* needs gesture */ });
                return;
            }

            pending = null;
            doPlay(req.bytes, req.loop).catch(function (e) { log("play failed: " + e); });
        } catch (e) {
            log("rspyPlayMidi error: " + e);
        }
    };

    window.rspyStopMidi = function () {
        pending = null;
        try {
            if (synth) synth.stopPlayer();
            log("stopped");
        } catch (e) {
            log("stop error: " + e);
        }
    };

    window.rspySetMidiVolume = function (level) {
        currentGain = gainForLevel(level);
        if (gainNode) {
            try { gainNode.gain.value = currentGain; } catch (e) { /* ignore */ }
        }
        log("volume level=" + level + " gain=" + currentGain);
    };

    // Arm the gesture hook and prefetch the soundfont immediately, so the very
    // first click anywhere unlocks audio and the title jingle is ready to play.
    hookGesture();
    try { fetchSoundfont().catch(function (e) { log("soundfont prefetch failed: " + e); }); }
    catch (e) { /* ignore */ }
})();
