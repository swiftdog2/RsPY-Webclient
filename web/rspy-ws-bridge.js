/*
 * rspy-ws-bridge.js
 *
 * Browser side of the RsPy web client transport. Exposes window.rspyWs* helpers
 * that WebSocketSocket.java reaches through CheerpJ native methods
 * (Java_WebSocketSocket_*). Bytes are exchanged as Base64 strings because the
 * CheerpJ native boundary marshals strings cleanly.
 *
 * Socket state codes returned to Java (match WebSocketSocket.java):
 *   0 connecting, 1 open, 3 closed, 4 error
 */
(function () {
    "use strict";

    var sockets = {};
    var nextId = 1;

    function b64encode(bytes) {
        var bin = "";
        for (var i = 0; i < bytes.length; i++) {
            bin += String.fromCharCode(bytes[i]);
        }
        return btoa(bin);
    }

    function b64decode(str) {
        var bin = atob(str);
        var out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) {
            out[i] = bin.charCodeAt(i) & 0xff;
        }
        return out;
    }

    // Open a binary WebSocket. window.RSPY_WS_URL (set by index.html) overrides
    // whatever URL the jar was compiled with, so the same jar works anywhere.
    window.rspyWsOpen = function (url) {
        var finalUrl = window.RSPY_WS_URL || url;
        var id = nextId++;
        var s = {
            ws: null,
            url: finalUrl,
            state: 0,
            error: "",
            chunks: [],   // queue of Uint8Array
            avail: 0
        };

        try {
            var ws = new WebSocket(finalUrl);
            ws.binaryType = "arraybuffer";

            ws.onopen = function () { s.state = 1; };

            ws.onmessage = function (ev) {
                var arr;
                if (ev.data instanceof ArrayBuffer) {
                    arr = new Uint8Array(ev.data);
                } else if (typeof ev.data === "string") {
                    arr = new Uint8Array(ev.data.length);
                    for (var i = 0; i < ev.data.length; i++) {
                        arr[i] = ev.data.charCodeAt(i) & 0xff;
                    }
                } else {
                    return;
                }
                if (arr.length) {
                    s.chunks.push(arr);
                    s.avail += arr.length;
                }
            };

            ws.onerror = function () {
                if (!s.error) s.error = "websocket error";
                if (s.state !== 1) s.state = 4;
            };

            ws.onclose = function () {
                s.state = (s.state === 4) ? 4 : 3;
            };

            s.ws = ws;
        } catch (e) {
            s.state = 4;
            s.error = String(e);
        }

        sockets[id] = s;
        return id;
    };

    window.rspyWsState = function (id) {
        var s = sockets[id];
        return s ? s.state : 3;
    };

    window.rspyWsError = function (id) {
        var s = sockets[id];
        return s ? (s.error || "") : "no such socket";
    };

    window.rspyWsAvailable = function (id) {
        var s = sockets[id];
        return s ? s.avail : 0;
    };

    // Consume and return up to `len` buffered bytes, Base64-encoded.
    window.rspyWsRead = function (id, len) {
        var s = sockets[id];
        if (!s || s.avail === 0 || len <= 0) {
            return "";
        }

        var out = new Uint8Array(Math.min(len, s.avail));
        var off = 0;

        while (off < out.length && s.chunks.length) {
            var head = s.chunks[0];
            var need = out.length - off;
            if (head.length <= need) {
                out.set(head, off);
                off += head.length;
                s.chunks.shift();
            } else {
                out.set(head.subarray(0, need), off);
                s.chunks[0] = head.subarray(need);
                off += need;
            }
        }

        s.avail -= out.length;
        return b64encode(out);
    };

    window.rspyWsSend = function (id, b64) {
        var s = sockets[id];
        if (!s || !s.ws || s.ws.readyState !== 1) {
            return false;
        }
        try {
            s.ws.send(b64decode(b64));
            return true;
        } catch (e) {
            s.error = String(e);
            return false;
        }
    };

    window.rspyWsClose = function (id) {
        var s = sockets[id];
        if (!s) return;
        try {
            if (s.ws) s.ws.close();
        } catch (e) { /* ignore */ }
        s.state = 3;
        delete sockets[id];
    };
})();
