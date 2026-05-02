// Navinci — app.js
// Basis: ADD-E Dashboard (vauwe-digital/softopus), adaptiert für Navinci
// ADD-E Akkuanbindung entfernt; BLE-Geschwindigkeit (CSC Radsensor) + GPS-Fallback neu

// ── Texte DE / EN ─────────────────────────────────────────────────────────────
const LABELS = {
    de: {
        spd:'Geschwindigkeit', dist:'Distanz', cad:'Trittfrequenz', time:'Fahrzeit',
        avgspd:'Ø Geschwindigkeit', totalkm:'Gesamtkilometer', maxspd:'Geschw.-Maximum',
        clock:'Uhrzeit',
        conn:'Verbunden', disc:'Getrennt', scan:'Suche…',
        btnCscConn:'Sensor verbinden', btnCscDisc:'Sensor trennen',
        noPermission:'Berechtigung fehlt',
        live:'▶ Live', stats:'∑ Stats', hist:'≡ Verlauf',
        today:'Heute', noData:'Keine Fahrten',
        rides:'Fahrten', distance:'Distanz', duration:'Fahrzeit',
        avgSpd:'Ø Geschw.', maxSpd:'Max Geschw.', avgCad:'Ø Trittf.',
        start:'Start', pause:'Pause', stop:'Stop', resume:'Weiter',
        stopTitle:'Fahrt beenden', stopText:'Fahrt wirklich beenden und speichern?',
        stopOk:'Ja', stopCancel:'Nein',
        stopMinDist:'Mindestdistanz 0.05 km nicht erreicht',
        statusSaved:'Fahrt gespeichert',
        simBtn:'⚙ Testdaten erstellen', simTitle:'Testdaten erstellen',
        simText:'Fahrten 01.01.2025 – heute werden generiert. Vorhandene Daten werden überschrieben.',
        simOk:'Erstellen', simCancel:'Abbrechen',
        clearTitle:'Daten löschen', clearText:'Alle Fahrtdaten werden unwiderruflich gelöscht.',
        clearOk:'Löschen', clearCancel:'Abbrechen', saved:'Fahrten gespeichert',
        importTitle:'CSV importieren',
        importText:'Die CSV-Datei wird mit vorhandenen Daten zusammengeführt. Duplikate werden übersprungen.',
        importOk:'Importieren', importCancel:'Abbrechen',
        btHint:'Bluetooth eingeschaltet?', cscNotFound:'Kein Sensor gefunden',
        bleRetryHint:'BLE benötigt evtl. mehrere Versuche',
        wheelLabel:'Radumfang (m)',
        wheelHint:'z.B. 2.096 (700c×23) · 2.105 (700c×25) · 2.026 (26×2.0)',
        wheelSave:'Speichern', wheelSaved:'✓ Radumfang gespeichert',
        srcSensor:'⚡ Sensor', srcGps:'📡 GPS',
        alt:'Höhe', gain:'Höhenmeter ↑', cad:'Trittfrequenz',
        gainUnit:'m', noSensor:'Kein Barometer – GPS-Höhe',
    },
    en: {
        spd:'Speed', dist:'Distance', cad:'Cadence', time:'Ride Time',
        avgspd:'Avg Speed', totalkm:'Total Distance', maxspd:'Max Speed', clock:'Time',
        conn:'Connected', disc:'Disconnected', scan:'Scanning…',
        btnCscConn:'Connect Sensor', btnCscDisc:'Disconnect Sensor',
        noPermission:'Permission denied',
        live:'▶ Live', stats:'∑ Stats', hist:'≡ History',
        today:'Today', noData:'No rides',
        rides:'Rides', distance:'Distance', duration:'Duration',
        avgSpd:'Avg Speed', maxSpd:'Max Speed', avgCad:'Avg Cadence',
        start:'Start', pause:'Pause', stop:'Stop', resume:'Resume',
        stopTitle:'End ride', stopText:'Really end and save this ride?',
        stopOk:'Yes', stopCancel:'No',
        stopMinDist:'Minimum distance 0.05 km not reached',
        statusSaved:'Ride saved',
        simBtn:'⚙ Create test data', simTitle:'Create test data',
        simText:'Rides from 01.01.2025 to today will be generated. Existing data will be overwritten.',
        simOk:'Create', simCancel:'Cancel',
        clearTitle:'Delete data', clearText:'All ride data will be permanently deleted.',
        clearOk:'Delete', clearCancel:'Cancel', saved:'rides saved',
        importTitle:'Import CSV',
        importText:'The CSV file will be merged with existing data. Duplicates will be skipped.',
        importOk:'Import', importCancel:'Cancel',
        btHint:'Bluetooth turned on?', cscNotFound:'No sensor found',
        bleRetryHint:'BLE may need several attempts',
        wheelLabel:'Wheel Circumference (m)',
        wheelHint:'e.g. 2.096 (700c×23) · 2.105 (700c×25) · 2.026 (26×2.0)',
        wheelSave:'Save', wheelSaved:'✓ Wheel circumference saved',
        srcSensor:'⚡ Sensor', srcGps:'📡 GPS',
        alt:'Altitude', gain:'Elevation ↑', cad:'Cadence',
        gainUnit:'m', noSensor:'No barometer – GPS altitude',
    }
};

function getLang() { return localStorage.getItem('navinci_lang') || 'de'; }

function applyLabels() {
    const L = LABELS[getLang()];
    const set = (id, txt) => { const el = document.getElementById(id); if (el) el.textContent = txt; };
    set('l-spd', L.spd); set('l-dist', L.dist); set('l-cad', L.cad); set('l-time', L.time);
    set('l-avgspd', L.avgspd); set('l-totalkm', L.totalkm); set('l-maxspd', L.maxspd);
    set('l-clock', L.clock);
    set('nb-live', L.live); set('nb-stats', L.stats); set('nb-hist', L.hist);
    set('hnav-today', L.today);
    set('l-start', L.start); set('l-pause', L.pause); set('l-stop', L.stop);
    set('l-wheel', L.wheelLabel); set('wheel-hint', L.wheelHint); set('btn-save-wheel', L.wheelSave);
    set('l-alt',  L.alt);
    set('l-gain', L.gain);
    set('l-cad',  L.cad);
    const cscbtn = document.getElementById('cscbtn');
    if (cscbtn) cscbtn.textContent = cscbtn.dataset.status === 'connected' ? L.btnCscDisc : L.btnCscConn;
    const lbtn = document.getElementById('lbtn');
    if (lbtn) lbtn.textContent = getLang() === 'de' ? 'EN' : 'DE';
    updateSpeedSrcBadge(); updateRideUI(); renderHistContent();
}

window.toggleLang = function() {
    localStorage.setItem('navinci_lang', getLang() === 'de' ? 'en' : 'de');
    applyLabels();
};

// ── Geschwindigkeitsquelle ────────────────────────────────────────────────────
let _speedSource = 'gps';   // 'gps' | 'ble'

function updateSpeedSrcBadge() {
    const el = document.getElementById('speed-src');
    if (!el) return;
    const L = LABELS[getLang()];
    el.textContent = _speedSource === 'ble' ? L.srcSensor : L.srcGps;
}

// ── Fahrt-Steuerung ───────────────────────────────────────────────────────────
let _rideState    = 'idle';
let _rideSeconds  = 0;
let _rideDist     = 0.0;
let _rideMaxSpd   = 0.0;
let _rideAvgSpd   = 0.0;
let _rideCadences = [];
let _rideGainM    = 0;        // Höhenmeter gesamt dieser Fahrt
let _rideStartTime= null;
window._rideTimer   = null;
window._lastSvcDist = null;

// GPS-Höhe mit Kalman-Filter (Fallback wenn kein Barometer)
// 1D-Kalman: glättet GPS-Rauschen (±15–30 m) auf ±3–5 m
let _kAlt     = null;   // Kalman-Schätzung der Höhe [m]
let _kP       = 500;    // Schätzfehler-Kovarianz (hoch = anfangs unsicher)
const _kQ     = 0.5;    // Prozessrauschen (wie stark ändert sich Höhe pro Update)
const _kR     = 225;    // Messrauschen GPS (15 m Std.abw. → 15² = 225)

let _gpsLastAlt   = null;     // geglättete Referenzhöhe für Gain-Delta
let _gpsGain      = 0;        // akkumulierte Aufstiegsmeter via GPS
let _hasBaro      = false;    // wird beim ersten updateFromService gesetzt
const GPS_GAIN_THRESH = 5;    // Schwellenwert nach Kalman (deutlich kleiner möglich)

function updateRideUI() {
    const L        = LABELS[getLang()];
    const btnStart = document.getElementById('btn-start');
    const btnPause = document.getElementById('btn-pause');
    const btnStop  = document.getElementById('btn-stop');
    const startLbl = document.getElementById('l-start');
    const pauseLbl = document.getElementById('l-pause');
    if (!btnStart) return;
    if (_rideState === 'idle') {
        btnStart.disabled = false; btnPause.disabled = true; btnStop.disabled = true;
        if (startLbl) startLbl.textContent = L.start;
        if (pauseLbl) pauseLbl.textContent = L.pause;
    } else if (_rideState === 'running') {
        btnStart.disabled = true; btnPause.disabled = false; btnStop.disabled = false;
    } else if (_rideState === 'paused') {
        btnStart.disabled = false; btnPause.disabled = true; btnStop.disabled = false;
        if (startLbl) startLbl.textContent = L.resume;
    }
}

window.rideStart = function() {
    stopRideTimer();
    if (_rideState === 'idle') {
        _rideSeconds = 0; _rideDist = 0.0; _rideMaxSpd = 0.0; _rideAvgSpd = 0.0;
        _rideCadences = []; _rideGainM = 0; _rideStartTime = new Date(); window._lastSvcDist = null;
        _gpsLastAlt = _kAlt; _gpsGain = 0;  // GPS-Gain-Reset
        const noteEl = document.getElementById('ride-note');
        if (noteEl) noteEl.value = '';
        localStorage.removeItem('navinci_current_note');
        const s = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
        s('v-spd','0.0'); s('v-dist','0.00'); s('v-time','00:00');
        s('v-maxspd','0.0'); s('v-avgspd','0.0'); s('v-cad','0');
    } else if (_rideState === 'paused') {
        window._lastSvcDist = null;   // Delta-Reset nach Pause
    }
    _rideState = 'running';
    startRideTimer(); updateRideUI();
};

window.ridePause = function() {
    if (_rideState !== 'running') return;
    _rideState = 'paused'; window._lastSvcDist = null;
    stopRideTimer(); updateRideUI();
};

window.rideStop = function() {
    if (_rideState === 'idle') return;
    const L = LABELS[getLang()];
    if (_rideDist < 0.05) {
        showToast(L.stopMinDist); _rideState = 'idle'; stopRideTimer(); updateRideUI(); return;
    }
    showDialog(L.stopTitle, L.stopText, L.stopOk, '#E24B4A', L.stopCancel, () => {
        stopRideTimer(); saveCurrentRide();
        _rideState = 'idle'; _rideSeconds = 0; _rideDist = 0.0;
        _rideMaxSpd = 0.0; _rideAvgSpd = 0.0; _rideCadences = []; _rideGainM = 0;
        _gpsGain = 0; _gpsLastAlt = null; window._lastSvcDist = null;
        if (typeof NativeBridge !== 'undefined') NativeBridge.resetService();
        const s = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
        s('v-spd','0.0'); s('v-dist','0.00'); s('v-time','00:00');
        s('v-maxspd','0.0'); s('v-avgspd','0.0'); s('v-cad','0');
        const sl = document.getElementById('l-start'); if (sl) sl.textContent = L.start;
        updateRideUI(); showToast('✓ ' + L.statusSaved); renderHistContent();
        setTimeout(() => {
            if (_rideState === 'idle') {
                const t = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
                t('v-time','00:00'); t('v-dist','0.00'); t('v-spd','0.0');
            }
        }, 1500);
    });
};

function saveCurrentRide() {
    if (!_rideStartTime) return;
    const avgCad = _rideCadences.length ? _rideCadences.reduce((a,v) => a+v,0)/_rideCadences.length : 0;
    const note   = localStorage.getItem('navinci_current_note') || '';
    const ride   = {
        id: _rideStartTime.getTime(),
        date: _rideStartTime.toISOString().slice(0,10),
        startTime: String(_rideStartTime.getHours()).padStart(2,'0')+':'+String(_rideStartTime.getMinutes()).padStart(2,'0'),
        duration: _rideSeconds, distance: parseFloat(_rideDist.toFixed(2)),
        avgSpeed: parseFloat(_rideAvgSpd.toFixed(1)), maxSpeed: parseFloat(_rideMaxSpd.toFixed(1)),
        avgCadence: parseFloat(avgCad.toFixed(0)), elevGain: _rideGainM, note: note.trim()
    };
    const rides = getRides();
    rides.push(ride); rides.sort((a,b) => a.date.localeCompare(b.date));
    localStorage.setItem('navinci_rides', JSON.stringify(rides));
    localStorage.removeItem('navinci_current_note');
}

// ── GPS-Callback ──────────────────────────────────────────────────────────────
window.updateGps = function(data) {
    const d = typeof data === 'string' ? JSON.parse(data) : data;
    if (_speedSource === 'gps') {
        const el = document.getElementById('v-spd');
        if (el) el.textContent = d.speed.toFixed(1);
    }

    // GPS-Höhe immer verarbeiten (Kalman-Filter + Anzeige)
    if (d.altitude !== undefined && d.altitude !== null && d.altitude !== 0) {
        const z = parseFloat(d.altitude);   // Messung

        // ── 1D Kalman-Filter ─────────────────────────────────────────────
        if (_kAlt === null) {
            // Initialisierung: ersten Messwert direkt übernehmen
            _kAlt = z;
            _kP   = _kR;
        } else {
            // Prädiktionsschritt
            const pPred = _kP + _kQ;
            // Updateschritt
            const K = pPred / (pPred + _kR);    // Kalman-Gain  0…1
            _kAlt = _kAlt + K * (z - _kAlt);    // neue Schätzung
            _kP   = (1 - K) * pPred;            // neue Kovarianz
        }
        // ─────────────────────────────────────────────────────────────────

        // Anzeige (immer, unabhängig von Barometer)
        const altEl = document.getElementById('v-alt');
        if (altEl && !_hasBaro) altEl.textContent = Math.round(_kAlt);

        // GPS-Gain nur wenn kein Barometer
        if (!_hasBaro && _rideState === 'running') {
            if (_gpsLastAlt === null) {
                _gpsLastAlt = _kAlt;
            } else {
                const delta = _kAlt - _gpsLastAlt;
                if (delta >= GPS_GAIN_THRESH) {
                    _gpsGain   += delta;
                    _gpsLastAlt = _kAlt;
                    _rideGainM  = Math.round(_gpsGain);
                } else if (delta <= -GPS_GAIN_THRESH) {
                    _gpsLastAlt = _kAlt;
                }
            }
            const gnEl = document.getElementById('v-gain');
            if (gnEl) gnEl.textContent = Math.round(_gpsGain);
        }
    }
};

// ── Barometer-Callback ────────────────────────────────────────────────────────
// Wird über updateFromService geliefert (TrackingService läuft im Hintergrund)
// Separater window.updateBaro wird nicht mehr benötigt.
// speed === null → Sensor getrennt, GPS-Fallback aktiv
window.updateCscSpeed = function(data) {
    const d = typeof data === 'string' ? JSON.parse(data) : data;
    if (d.speed === null || d.speed === undefined) {
        _speedSource = 'gps';
    } else {
        _speedSource = 'ble';
        const el = document.getElementById('v-spd');
        if (el) el.textContent = d.speed.toFixed(1);
        if (_rideState === 'running' && d.speed > _rideMaxSpd) {
            _rideMaxSpd = d.speed;
            const mEl = document.getElementById('v-maxspd'); if (mEl) mEl.textContent = _rideMaxSpd.toFixed(1);
        }
    }
    updateSpeedSrcBadge();
};

// ── Service-Sync (TrackingService, 1×/s) ──────────────────────────────────────
window.updateFromService = function(data) {
    const d   = typeof data === 'string' ? JSON.parse(data) : data;
    const set = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };

    if (_speedSource === 'gps') set('v-spd', d.speed.toFixed(1));

    // Barometer-Status merken (einmalig beim ersten Sync gesetzt)
    if (d.baroAvailable !== undefined) _hasBaro = d.baroAvailable === true;

    // Höhe + Gain nur vom Barometer übernehmen — GPS-Fallback läuft in updateGps
    if (_hasBaro) {
        const altVal  = (d.altitude !== undefined && d.altitude !== null) ? Math.round(d.altitude) : 0;
        const gainVal = (d.gain     !== undefined && d.gain     !== null) ? Math.round(d.gain)     : 0;
        const altEl = document.getElementById('v-alt');
        const gnEl  = document.getElementById('v-gain');
        if (altEl) altEl.textContent = altVal;
        if (gnEl)  gnEl.textContent  = gainVal;
        if (_rideState === 'running') _rideGainM = gainVal;
    }

    if (_rideState !== 'running') return;

    if (window._lastSvcDist == null) window._lastSvcDist = d.distance;
    const delta = Math.max(0, d.distance - window._lastSvcDist);
    window._lastSvcDist = d.distance;
    _rideDist += delta;
    set('v-dist', _rideDist.toFixed(2));

    if (d.seconds > _rideSeconds) { _rideSeconds = d.seconds; set('v-time', fmtTime(_rideSeconds)); }

    if (_speedSource === 'gps' && d.speed > _rideMaxSpd) {
        _rideMaxSpd = d.speed; set('v-maxspd', _rideMaxSpd.toFixed(1));
    }
    if (_rideSeconds > 0 && _rideDist > 0) {
        _rideAvgSpd = (_rideDist / _rideSeconds) * 3600; set('v-avgspd', _rideAvgSpd.toFixed(1));
    }
};

// ── Cadence ───────────────────────────────────────────────────────────────────
window.updateCadence = function(data) {
    const d  = typeof data === 'string' ? JSON.parse(data) : data;
    const el = document.getElementById('v-cad');
    if (el) el.textContent = d.cadence;
    if (_rideState === 'running' && d.cadence > 0) _rideCadences.push(d.cadence);
};

// ── CSC-Status ────────────────────────────────────────────────────────────────
window.updateCscStatus = function(status) {
    const L = LABELS[getLang()];
    const cscbtn = document.getElementById('cscbtn');
    const dot    = document.getElementById('dot');
    const stxt   = document.getElementById('stxt');
    if (!cscbtn) return;
    cscbtn.classList.remove('scanning');
    if (status === 'connected') {
        cscbtn.dataset.status = 'connected'; cscbtn.textContent = L.btnCscDisc;
        if (dot) dot.className = 'dot'; if (stxt) stxt.textContent = L.conn;
    } else if (status === 'timeout') {
        cscbtn.dataset.status = 'disconnected'; cscbtn.textContent = L.btnCscConn;
        if (dot) dot.className = 'dot dis'; if (stxt) stxt.textContent = L.disc;
        const el = document.getElementById('v-cad'); if (el) el.textContent = '0';
        showToast(L.cscNotFound);
    } else {
        cscbtn.dataset.status = 'disconnected'; cscbtn.textContent = L.btnCscConn;
        if (dot) dot.className = 'dot dis'; if (stxt) stxt.textContent = L.disc;
        const el = document.getElementById('v-cad'); if (el) el.textContent = '0';
    }
    updateSpeedSrcBadge();
};

window.onPermissionDenied = function() {
    const el = document.getElementById('stxt');
    if (el) el.textContent = LABELS[getLang()].noPermission;
};

// ── Wake Lock ─────────────────────────────────────────────────────────────────
window._wakeLockOn = false;
window.toggleWakeLock = function() {
    window._wakeLockOn = !window._wakeLockOn;
    const btn = document.getElementById('wakelock-btn');
    if (btn) {
        btn.style.opacity    = window._wakeLockOn ? '1'           : '0.35';
        btn.style.filter     = window._wakeLockOn ? 'none'        : 'grayscale(1)';
        btn.style.textShadow = window._wakeLockOn ? '0 0 8px #FFD600' : 'none';
        btn.title = window._wakeLockOn
            ? (getLang()==='de' ? 'Display: aktiv'     : 'Screen: on')
            : (getLang()==='de' ? 'Display: Ruhemodus' : 'Screen: sleep');
    }
    if (typeof NativeBridge !== 'undefined') NativeBridge.setWakeLock(window._wakeLockOn);
    showToast(window._wakeLockOn
        ? (getLang()==='de' ? '☀ Display bleibt aktiv' : '☀ Screen stays on')
        : (getLang()==='de' ? '💤 Display: Ruhemodus'  : '💤 Screen: sleep mode'));
};

// ── Scan-Animation ────────────────────────────────────────────────────────────
let _scanDotTimer = null, _scanHintTimer = null;
function startScanAnimation() {
    let dots = 0, el = document.getElementById('stxt');
    _scanDotTimer  = setInterval(() => { dots=(dots+1)%4; if(el) el.textContent='Suche'+'.'.repeat(dots); }, 400);
    _scanHintTimer = setTimeout(() => showToast(LABELS[getLang()].bleRetryHint), 5000);
}
function stopScanAnimation() {
    clearInterval(_scanDotTimer); clearTimeout(_scanHintTimer);
    _scanDotTimer = null; _scanHintTimer = null;
}

// ── Sensor verbinden / trennen ────────────────────────────────────────────────
window.onBtnCscConnect = function() {
    const L = LABELS[getLang()];
    const cscbtn = document.getElementById('cscbtn');
    const dot    = document.getElementById('dot');
    const stxt   = document.getElementById('stxt');
    if (typeof NativeBridge === 'undefined') return;
    if (cscbtn.dataset.status === 'connected') {
        NativeBridge.stopCsc();
        cscbtn.dataset.status = 'disconnected'; cscbtn.textContent = L.btnCscConn;
        cscbtn.classList.remove('scanning');
        if (dot) dot.className = 'dot dis'; if (stxt) stxt.textContent = L.disc;
        stopScanAnimation();
        const el = document.getElementById('v-cad'); if (el) el.textContent = '0';
    } else if (cscbtn.dataset.status === 'scanning') {
        showToast(getLang()==='de' ? 'Suche läuft…' : 'Scanning…');
    } else {
        showToast(L.btHint);
        cscbtn.dataset.status = 'scanning'; cscbtn.classList.add('scanning');
        if (dot) dot.className = 'dot scan';
        startScanAnimation();
        NativeBridge.startCsc();
        setTimeout(() => {
            if (cscbtn.dataset.status !== 'connected') {
                cscbtn.dataset.status = 'disconnected'; cscbtn.classList.remove('scanning');
                cscbtn.textContent = L.btnCscConn;
                if (dot) dot.className = 'dot dis'; if (stxt) stxt.textContent = L.disc;
                stopScanAnimation(); showToast(L.cscNotFound);
            }
        }, 31000);
    }
};

// ── OsmAnd ────────────────────────────────────────────────────────────────────
window.onBtnOsmAnd = function() {
    if (typeof NativeBridge !== 'undefined') NativeBridge.launchOsmAnd();
};

// ── Radumfang ─────────────────────────────────────────────────────────────────
window.saveWheelCircumference = function() {
    const L = LABELS[getLang()];
    const v = parseFloat(document.getElementById('input-wheel').value);
    if (!isFinite(v) || v < 0.5 || v > 4.0) { showToast('Ungültiger Wert (0.5 – 4.0 m)'); return; }
    localStorage.setItem('navinci_wheel_m', v.toFixed(4));
    if (typeof NativeBridge !== 'undefined') NativeBridge.setWheelCircumference(v);
    showToast(L.wheelSaved);
};

// ── Screen-Navigation ─────────────────────────────────────────────────────────
window.showScr = function(name, btn) {
    ['live','stats','hist'].forEach(n => {
        document.getElementById('scr-'+n).style.display = n===name ? 'block' : 'none';
    });
    document.querySelectorAll('.nbtn').forEach(b => b.classList.remove('on'));
    btn.classList.add('on');
    if (name === 'hist') renderHistContent();
};

// ── Fahrtimer ─────────────────────────────────────────────────────────────────
function startRideTimer() {
    if (window._rideTimer) return;
    window._rideTimer = setInterval(() => {
        if (_rideState !== 'running') return;
        _rideSeconds++;
        const el = document.getElementById('v-time'); if (el) el.textContent = fmtTime(_rideSeconds);
    }, 1000);
}
function stopRideTimer() { clearInterval(window._rideTimer); window._rideTimer = null; }

function fmtTime(s) {
    const h=Math.floor(s/3600), m=Math.floor((s%3600)/60), sec=s%60;
    return (h>0 ? String(h).padStart(2,'0')+':' : '') + String(m).padStart(2,'0')+':'+String(sec).padStart(2,'0');
}
function fmtDuration(s) {
    const h=Math.floor(s/3600), m=Math.floor((s%3600)/60);
    return h>0 ? `${h}h ${m}min` : `${m}min`;
}

// ── Uhrzeit ───────────────────────────────────────────────────────────────────
function updateClock() {
    const n=new Date(), el=document.getElementById('v-clock');
    if (el) el.textContent = String(n.getHours()).padStart(2,'0')+':'+String(n.getMinutes()).padStart(2,'0');
}
updateClock(); setInterval(updateClock, 1000);

// ── Farbthemen ────────────────────────────────────────────────────────────────
const THEMES = {
    teal:  { a:'#1D9E75', bg:'#E1F5EE', mid:'#9FE1CB', tx:'#085041' },
    amber: { a:'#EF9F27', bg:'#FAEEDA', mid:'#FAC775', tx:'#633806' },
    coral: { a:'#D85A30', bg:'#FAECE7', mid:'#F5C4B3', tx:'#4A1B0C' }
};
function applyTheme(name) {
    const th = THEMES[name] || THEMES.teal, r = document.documentElement;
    r.style.setProperty('--accent', th.a); r.style.setProperty('--accent-bg', th.bg);
    r.style.setProperty('--accent-mid', th.mid); r.style.setProperty('--accent-text', th.tx);
    document.body.style.background = th.bg;
    localStorage.setItem('navinci_theme', name);
    document.querySelectorAll('.tbtn').forEach(b => b.classList.toggle('on', b.dataset.theme === name));
}
window.setTheme = function(name) { applyTheme(name); };

// ── Dialog + Toast ────────────────────────────────────────────────────────────
function showDialog(title, text, okLabel, okColor, cancelLabel, onOk) {
    const ov = document.createElement('div');
    ov.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:999;display:flex;align-items:center;justify-content:center;';
    ov.innerHTML = `<div style="background:#fff;border-radius:12px;padding:24px;margin:20px;max-width:320px;width:100%;box-shadow:0 8px 32px rgba(0,0,0,0.2);">
        <div style="font-size:15px;font-weight:600;margin-bottom:8px;color:#111;">${title}</div>
        <div style="font-size:13px;color:#666;margin-bottom:20px;line-height:1.5;">${text}</div>
        <div style="display:flex;gap:10px;">
          <button id="dlg-cancel" style="flex:1;padding:10px;border-radius:8px;border:1px solid #ccc;background:#f5f5f5;font-size:14px;cursor:pointer;font-family:inherit;color:#333;">${cancelLabel}</button>
          <button id="dlg-ok" style="flex:1;padding:10px;border-radius:8px;border:none;background:${okColor};color:#fff;font-size:14px;font-weight:600;cursor:pointer;font-family:inherit;">${okLabel}</button>
        </div></div>`;
    document.body.appendChild(ov);
    document.getElementById('dlg-cancel').onclick = () => document.body.removeChild(ov);
    document.getElementById('dlg-ok').onclick     = () => { document.body.removeChild(ov); onOk(); };
}
window.showToast = function(msg) {
    const t = document.createElement('div');
    t.style.cssText = 'position:fixed;bottom:30px;left:50%;transform:translateX(-50%);background:#1D9E75;color:#fff;padding:10px 20px;border-radius:20px;font-size:13px;font-weight:600;z-index:999;white-space:nowrap;';
    t.textContent = msg; document.body.appendChild(t);
    setTimeout(() => { if (t.parentNode) t.parentNode.removeChild(t); }, 3000);
};

// ── Notiz ─────────────────────────────────────────────────────────────────────
window.saveNote = function() {
    const el = document.getElementById('ride-note');
    if (el) localStorage.setItem('navinci_current_note', el.value);
};

// ── Fahrtdaten (localStorage) ─────────────────────────────────────────────────
function getRides() {
    try { return JSON.parse(localStorage.getItem('navinci_rides') || '[]'); } catch { return []; }
}

// ── Simulation ────────────────────────────────────────────────────────────────
window.histSimulate = function() {
    const L = LABELS[getLang()];
    showDialog(L.simTitle, L.simText, L.simOk, '#1D9E75', L.simCancel, runSimulation);
};
function runSimulation() {
    const L = LABELS[getLang()];
    const rnd    = (a,b)   => Math.random()*(b-a)+a;
    const rndInt = (a,b)   => Math.floor(rnd(a,b+1));
    const rides  = [], d   = new Date('2025-01-01'), end = new Date();
    let count = 0;
    const probs = [0.20,0.25,0.40,0.55,0.70,0.80,0.85,0.80,0.70,0.55,0.30,0.20];
    while (d <= end) {
        const isWE = d.getDay()===0 || d.getDay()===6;
        if (Math.random() < Math.min(probs[d.getMonth()]*(isWE?1.4:1.0), 0.95)) {
            const dist=parseFloat(rnd(isWE?8:3,isWE?35:18).toFixed(2));
            const avg =parseFloat(rnd(16,26).toFixed(1));
            const max =parseFloat((avg+rnd(4,10)).toFixed(1));
            const dur =Math.round((dist/avg)*3600);
            const hr  =isWE?rndInt(8,16):rndInt(7,18);
            const mn  =rndInt(0,59);
            rides.push({ id:d.getTime()+count, date:d.toISOString().slice(0,10),
                startTime:String(hr).padStart(2,'0')+':'+String(mn).padStart(2,'0'),
                duration:dur, distance:dist, avgSpeed:avg, maxSpeed:max,
                avgCadence:rndInt(70,95), note:'' });
            count++;
        }
        d.setDate(d.getDate()+1);
    }
    localStorage.setItem('navinci_rides', JSON.stringify(rides));
    renderHistContent(); window.showToast(rides.length+' '+L.saved);
}

// ── CSV-Import ────────────────────────────────────────────────────────────────
window.histImport = function() {
    const L = LABELS[getLang()];
    showDialog(L.importTitle, L.importText, L.importOk, '#1D9E75', L.importCancel, () => {
        if (typeof NativeBridge !== 'undefined') NativeBridge.importCsv();
    });
};
window.importCsvData = function(csvRaw) {
    const csv   = typeof csvRaw === 'string' ? csvRaw : String(csvRaw);
    const clean = csv.charCodeAt(0)===0xFEFF ? csv.slice(1) : csv;
    const sep   = clean.includes(';') ? ';' : ',';
    const lines = clean.split('\n').filter(l => l.trim().length > 0);
    if (lines.length < 2) { window.showToast('CSV leer oder ungültig'); return; }
    const toF = s => parseFloat((s||'0').trim().replace(',','.'))||0;
    const toI = s => parseInt((s||'0').trim().replace(',','.'))||0;
    const imported = [];
    for (let i=1; i<lines.length; i++) {
        const c = lines[i].split(sep);
        if (c.length < 6) continue;
        const r = { id:new Date(c[0]?.trim()).getTime()+i, date:c[0]?.trim()||'',
            startTime:c[1]?.trim()||'', distance:toF(c[2]), duration:toI(c[3]),
            avgSpeed:toF(c[4]), maxSpeed:toF(c[5]),
            avgCadence:c[6]?toI(c[6]):null, elevGain:c[7]?toI(c[7]):null, note:c[8]?c[8].trim():'' };
        if (r.date && r.distance > 0) imported.push(r);
    }
    if (!imported.length) { window.showToast('Keine gültigen Fahrten gefunden'); return; }
    const existing = getRides(), existIds = new Set(existing.map(r => r.date+r.startTime));
    const newR     = imported.filter(r => !existIds.has(r.date+r.startTime));
    const merged   = [...existing, ...newR].sort((a,b) => a.date.localeCompare(b.date));
    localStorage.setItem('navinci_rides', JSON.stringify(merged));
    window.showToast('✓ '+newR.length+' Fahrten importiert'+
        (imported.length-newR.length > 0 ? ' ('+( imported.length-newR.length)+' Duplikate)' : ''));
    renderHistContent();
};

// ── CSV-Export ────────────────────────────────────────────────────────────────
window.histExport = function() {
    const rides = getRides(), L = LABELS[getLang()];
    if (!rides.length) { window.showToast(L.noData); return; }
    let filtered = [], label = '';
    if      (_histTab==='t') { const d=_histDate.toISOString().slice(0,10);  filtered=rides.filter(r=>r.date===d); label=d; }
    else if (_histTab==='w') { const m=getMonday(new Date(_histDate)),s=new Date(m); s.setDate(s.getDate()+6); filtered=rides.filter(r=>r.date>=m.toISOString().slice(0,10)&&r.date<=s.toISOString().slice(0,10)); label='KW_'+m.toISOString().slice(0,10); }
    else if (_histTab==='m') { const ym=_histDate.toISOString().slice(0,7);  filtered=rides.filter(r=>r.date.slice(0,7)===ym); label=ym; }
    else if (_histTab==='j') { const yr=String(_histDate.getFullYear());     filtered=rides.filter(r=>r.date.slice(0,4)===yr); label=yr; }
    else { filtered=rides; label='Gesamt'; }
    if (!filtered.length) { window.showToast(L.noData); return; }
    const csv = ['Datum,Uhrzeit,Distanz km,Fahrzeit s,Ø km/h,Max km/h,Ø rpm,Höhenmeter m,Notiz']
        .concat(filtered.map(r=>`${r.date},${r.startTime||''},${r.distance},${r.duration},${r.avgSpeed},${r.maxSpeed},${r.avgCadence||''},${r.elevGain||''},${(r.note||'').replace(/,/g,';')}`))
        .join('\n');
    localStorage.setItem('navinci_csv_export', csv);
    localStorage.setItem('navinci_csv_label', label);
    window.showToast('Exportiere '+filtered.length+' Fahrten ('+label+')…');
    if (typeof NativeBridge !== 'undefined') NativeBridge.exportCsv('ready');
};

// ── Daten löschen ─────────────────────────────────────────────────────────────
window.histClear = function() {
    const L = LABELS[getLang()];
    showDialog(L.clearTitle, L.clearText, L.clearOk, '#E24B4A', L.clearCancel, () => {
        localStorage.removeItem('navinci_rides'); renderHistContent();
    });
};
window.histReport = function() { histClear(); };

// ── Verlauf: Tab-Logik ────────────────────────────────────────────────────────
let _histTab = 't', _histDate = new Date();

window.setHistTab = function(tab, btn) {
    _histTab = tab; _histDate = new Date();
    document.querySelectorAll('.htab').forEach(b => b.classList.remove('on'));
    btn.classList.add('on');
    const nav = document.getElementById('hist-nav');
    if (nav) nav.style.display = tab==='g' ? 'none' : 'flex';
    updateHistNav(); renderHistContent();
};
window.histNavPrev = function() {
    if(_histTab==='t') _histDate.setDate(_histDate.getDate()-1);
    else if(_histTab==='w') _histDate.setDate(_histDate.getDate()-7);
    else if(_histTab==='m') _histDate.setMonth(_histDate.getMonth()-1);
    else if(_histTab==='j') _histDate.setFullYear(_histDate.getFullYear()-1);
    updateHistNav(); renderHistContent();
};
window.histNavNext = function() {
    if(_histTab==='t') _histDate.setDate(_histDate.getDate()+1);
    else if(_histTab==='w') _histDate.setDate(_histDate.getDate()+7);
    else if(_histTab==='m') _histDate.setMonth(_histDate.getMonth()+1);
    else if(_histTab==='j') _histDate.setFullYear(_histDate.getFullYear()+1);
    updateHistNav(); renderHistContent();
};
window.histNavToday = function() { _histDate=new Date(); updateHistNav(); renderHistContent(); };

function getMonday(d) { const dt=new Date(d),day=dt.getDay()||7; dt.setDate(dt.getDate()-day+1); return dt; }
function updateHistNav() {
    const lbl = document.getElementById('hnav-label'); if (!lbl) return;
    const days = getLang()==='de' ? ['So','Mo','Di','Mi','Do','Fr','Sa'] : ['Su','Mo','Tu','We','Th','Fr','Sa'];
    if     (_histTab==='t') lbl.textContent = _histDate.toISOString().slice(0,10);
    else if(_histTab==='w') { const m=getMonday(_histDate); lbl.textContent=days[m.getDay()]+', '+m.toISOString().slice(0,10); }
    else if(_histTab==='m') lbl.textContent = _histDate.toISOString().slice(0,7);
    else if(_histTab==='j') lbl.textContent = String(_histDate.getFullYear());
}

// ── Verlauf: Inhalt rendern ───────────────────────────────────────────────────
function renderHistContent() {
    const el = document.getElementById('hist-content'); if (!el) return;
    const L = LABELS[getLang()], rides = getRides(); let filtered = [];
    if     (_histTab==='t') { filtered=rides.filter(r=>r.date===_histDate.toISOString().slice(0,10));  el.innerHTML=filtered.length===0?emptyHtml(L):filtered.map(r=>rideCard(r,L)).join(''); }
    else if(_histTab==='w') { const m=getMonday(new Date(_histDate)),s=new Date(m); s.setDate(s.getDate()+6); filtered=rides.filter(r=>r.date>=m.toISOString().slice(0,10)&&r.date<=s.toISOString().slice(0,10)); el.innerHTML=filtered.length===0?emptyHtml(L):aggCard(filtered,L)+filtered.map(r=>rideCard(r,L)).join(''); }
    else if(_histTab==='m') { filtered=rides.filter(r=>r.date.slice(0,7)===_histDate.toISOString().slice(0,7)); el.innerHTML=filtered.length===0?emptyHtml(L):aggCard(filtered,L)+filtered.map(r=>rideCard(r,L)).join(''); }
    else if(_histTab==='j') { filtered=rides.filter(r=>r.date.slice(0,4)===String(_histDate.getFullYear())); el.innerHTML=filtered.length===0?emptyHtml(L):aggCard(filtered,L)+filtered.map(r=>rideCard(r,L)).join(''); }
    else if(_histTab==='g') { el.innerHTML=rides.length===0?emptyHtml(L):aggCard(rides,L); }
}

function emptyHtml(L) {
    return `<div style="text-align:center;padding:24px 0;color:#aaa;font-size:13px;">${L.noData}</div>
    <div style="text-align:center;margin-top:4px;"><button onclick="histSimulate()" style="font-size:12px;padding:8px 16px;border-radius:8px;border:1px solid #ccc;background:#f5f5f5;color:#555;cursor:pointer;font-family:inherit;">${L.simBtn}</button></div>`;
}
function rideCard(r, L) {
    const cad = r.avgCadence ? `${r.avgCadence} rpm` : '--';
    return `<div class="hist-row"><div class="hist-row-hdr"><div class="hist-row-date">${r.date} ${r.startTime||''}</div><div class="hist-row-dist">${r.distance.toFixed(1)} km</div></div>
      <div class="hist-row-stats">
        <div class="hist-stat"><b>${fmtDuration(r.duration)}</b>${L.duration}</div>
        <div class="hist-stat"><b>${r.avgSpeed.toFixed(1)} km/h</b>${L.avgSpd}</div>
        <div class="hist-stat"><b>${r.maxSpeed.toFixed(1)} km/h</b>${L.maxSpd}</div>
        <div class="hist-stat"><b>${cad}</b>${L.avgCad}</div>
        ${r.elevGain ? `<div class="hist-stat"><b>${r.elevGain} m</b>${L.gain}</div>` : ''}
      </div>
      ${r.note ? `<div style="margin-top:6px;font-size:11px;color:#888;background:#FFFDE7;border-radius:6px;padding:4px 8px;">✏ ${r.note}</div>` : ''}
    </div>`;
}
function aggCard(rides, L) {
    const totalKm=rides.reduce((s,r)=>s+r.distance,0);
    const totalSec=rides.reduce((s,r)=>s+r.duration,0);
    const avgSpd=rides.reduce((s,r)=>s+r.avgSpeed,0)/rides.length;
    const maxSpd=Math.max(...rides.map(r=>r.maxSpeed));
    const cadR=rides.filter(r=>r.avgCadence>0);
    const avgCad=cadR.length?cadR.reduce((s,r)=>s+r.avgCadence,0)/cadR.length:0;
    const totalGain=rides.reduce((s,r)=>s+(r.elevGain||0),0);
    return `<div class="hist-agg" style="margin-bottom:10px;"><div class="hist-agg-grid">
        <div class="hist-agg-item"><div class="hist-agg-val">${rides.length}</div><div class="hist-agg-lbl">${L.rides}</div></div>
        <div class="hist-agg-item"><div class="hist-agg-val">${totalKm.toFixed(1)}</div><div class="hist-agg-lbl">${L.distance} km</div></div>
        <div class="hist-agg-item"><div class="hist-agg-val">${fmtDuration(totalSec)}</div><div class="hist-agg-lbl">${L.duration}</div></div>
        <div class="hist-agg-item"><div class="hist-agg-val">${avgSpd.toFixed(1)}</div><div class="hist-agg-lbl">${L.avgSpd} km/h</div></div>
        <div class="hist-agg-item"><div class="hist-agg-val">${maxSpd.toFixed(1)}</div><div class="hist-agg-lbl">${L.maxSpd} km/h</div></div>
        ${avgCad>0?`<div class="hist-agg-item"><div class="hist-agg-val">${avgCad.toFixed(0)}</div><div class="hist-agg-lbl">${L.avgCad} rpm</div></div>`:''}
        ${totalGain>0?`<div class="hist-agg-item"><div class="hist-agg-val">${totalGain}</div><div class="hist-agg-lbl">${L.gain} m</div></div>`:''}
    </div></div>`;
}

window.histInfo = function() {
    const rides=getRides(), L=LABELS[getLang()];
    if (!rides.length) { window.showToast(L.noData); return; }
    const km=rides.reduce((s,r)=>s+r.distance,0), sec=rides.reduce((s,r)=>s+r.duration,0);
    const ver=typeof NativeBridge!=='undefined' ? NativeBridge.getAppVersion() : '–';
    showDialog('∑ '+L.rides,
        `${L.rides}: <b>${rides.length}</b><br>${L.distance}: <b>${km.toFixed(1)} km</b><br>${L.duration}: <b>${fmtDuration(sec)}</b><br>v${ver}`,
        'OK','#1D9E75','',()=>{});
};

// ── Init ──────────────────────────────────────────────────────────────────────
applyTheme(localStorage.getItem('navinci_theme') || 'teal');

(function initWheel() {
    const wm = parseFloat(localStorage.getItem('navinci_wheel_m') || '2.105');
    const el = document.getElementById('input-wheel');
    if (el) el.value = wm.toFixed(3);
    if (typeof NativeBridge !== 'undefined') NativeBridge.setWheelCircumference(wm);
})();

updateHistNav();
applyLabels();