#include "PadReverb.h"
#include <jni.h>

namespace padfx {

void FreeverbStyle::prepare(int sampleRate) {
    // Tuning dasar Freeverb itu di-desain buat 44100Hz - kita skalain ke
    // sampleRate device sekarang biar karakter reverb-nya (panjang delay
    // line) sama walaupun device jalan di 48000Hz atau rate lain.
    sampleRate_ = sampleRate;
    const float scale = (float) sampleRate / 44100.0f;
    // Comb ke-0 & ke-2 yang dimodulasi (lihat process()) - dipilih yang
    // ukurannya beda jauh biar shimmer-nya gak "senada"/fasa sama terus.
    // 8 comb + 4 allpass = tuning Freeverb penuh: ekor lebih rapat &
    // halus (4 comb dulu kedengeran metalik/kosong, bikin desis "s" gak
    // sempat menyebar jadi "cesss" yang lembut).
    const int combTuningL[kNumCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    const int stereoSpread = (int) std::lround(23 * scale);
    const int allpassTuningL[kNumAllpasses] = {556, 441, 341, 225};

    // Extra headroom di buffer comb yang dimodulasi, buat nampung geseran
    // baca akibat LFO (kLfoDepthSamples) tanpa index nyasar ke luar buffer.
    const int modHeadroom = (int) kLfoDepthSamples + 4;
    for (int i = 0; i < kNumCombs; ++i) {
        int sizeL = (int) std::lround(combTuningL[i] * scale);
        bool modulated = (i < kNumModulatedCombs);
        combsL_[i].setSize(sizeL + (modulated ? modHeadroom : 0));
        combsR_[i].setSize(sizeL + stereoSpread + (modulated ? modHeadroom : 0));
    }
    for (int i = 0; i < kNumAllpasses; ++i) {
        int sizeL = (int) std::lround(allpassTuningL[i] * scale);
        allpassL_[i].setSize(sizeL);
        allpassL_[i].feedback = 0.5f;
        allpassR_[i].setSize(sizeL + stereoSpread);
        allpassR_[i].feedback = 0.5f;
    }

    const int maxPreDelaySamples = (int) std::lround((kMaxPreDelayMs / 1000.0f) * sampleRate) + 4;
    preDelayBuf_.assign(maxPreDelaySamples, 0.0f);
    preDelayWrite_ = 0;
    lfoPhaseL_ = 0.0f;
    lfoPhaseR_ = 1.57f;
    airStateL_ = 0.0f;
    airStateR_ = 0.0f;
    lowCutState_ = 0.0f;
    emphState_ = 0.0f;

    setRoomSize(0.6f);
    setDamp(0.3f);
    setCess(0.6f);
    setWidth(1.0f);
    setPreDelayMs(15.0f);
}

void FreeverbStyle::setPreDelayMs(float ms) {
    ms = std::fmax(0.0f, std::fmin(kMaxPreDelayMs, ms));
    int samples = (int) std::lround((ms / 1000.0f) * sampleRate_);
    if (!preDelayBuf_.empty()) {
        samples = std::min(samples, (int) preDelayBuf_.size() - 1);
    }
    preDelaySamples_.store(std::max(0, samples));
}

void FreeverbStyle::setRoomSize(float v) {
    roomSize_.store(v);
    // Feedback comb 0.72 (ruang kecil) sampai 0.985 (ruang gede, ekor
    // panjang banget) - dinaikin dikit dari rentang klasik Freeverb biar
    // ekor/desis bisa benar-benar panjang di ujung knob.
    float feedback = v * 0.265f + 0.72f;
    for (auto &c: combsL_) c.feedback = feedback;
    for (auto &c: combsR_) c.feedback = feedback;
    // Normalisasi level: energi tangki naik ~1/sqrt(1-fb^2) saat feedback
    // membesar. Dikompensasi di input supaya ekor panjang = lebih LAMA,
    // bukan lebih KENCENG (dikalibrasi supaya level wet setara versi lama).
    const float norm = std::sqrt(std::fmax(0.01f, 1.0f - feedback * feedback));
    tankGain_.store(0.27f * norm);
}

void FreeverbStyle::setDamp(float v) {
    damp_.store(v);
    updateDamping();
}

void FreeverbStyle::setCess(float v) {
    cess_.store(std::fmax(0.0f, std::fmin(1.0f, v)));
    updateDamping();
}

// Redaman nada tinggi di dalam feedback tiap comb. Knob Damp ngatur
// "gelap-terangnya" ruang, TAPI Cess mengurangi redaman itu: di Cess penuh
// redaman tinggal ~25% dari nilai Damp, jadi nada tinggi (desis S) ikut
// berputar di feedback selama ekor reverb - itulah "ceeesss" yang panjang.
void FreeverbStyle::updateDamping() {
    const float cess = cess_.load();
    const float damp1 = damp_.load() * 0.4f * (1.0f - 0.75f * cess);
    const float damp2 = 1.0f - damp1;
    for (auto &c: combsL_) { c.damp1 = damp1; c.damp2 = damp2; }
    for (auto &c: combsR_) { c.damp1 = damp1; c.damp2 = damp2; }
}

void FreeverbStyle::setWidth(float v) {
    width_.store(v);
}

void FreeverbStyle::process(float input, float &outL, float &outR) {
    if (!enabled_.load()) {
        // Bypass: full volume, TIDAK dikalikan knob Dry. Dry cuma relevan
        // sebagai penyeimbang dry/wet WAKTU reverb nyala - begitu reverb
        // di-off-in, gak ada lagi campuran dry/wet, jadi mic harus tetap
        // kedengar penuh walau sebelumnya user udah nurunin Dry buat
        // nonjolin Wet. (Dulu: outL/outR = dry * input, bikin mic ikutan
        // pelan/nyaris hilang kalau Dry kebetulan lagi rendah.)
        // Delay line comb/allpass SENGAJA gak disentuh (gak diproses) -
        // hemat CPU dan pas di-on-in lagi gak ada "ledakan" sisa energi
        // lama yang numpuk selama off.
        outL = input;
        outR = input;
        return;
    }
    // 1) Pre-delay: input masuk buffer dulu, tangki comb/allpass makan
    // sample yang keluar dari buffer ini (yang lebih "lama" sejumlah
    // preDelaySamples_), BUKAN input mentah langsung. Sinyal dry di bagian
    // paling bawah TETAP pakai `input` asli (nol delay) - jadi cuma bagian
    // wet yang mulai belakangan, persis efek pre-delay di reverb unit
    // beneran.
    float tankInput = input;
    if (!preDelayBuf_.empty()) {
        const int size = (int) preDelayBuf_.size();
        const int delaySamples = preDelaySamples_.load();
        // FIX (DTX): TULIS dulu, BARU baca. Aslinya baca dulu baru tulis; dengan pre-delay 0 posisi baca == posisi
        // tulis, jadi yang terbaca adalah sample satu putaran buffer yang lalu (~kMaxPreDelayMs = ~100 ms), BUKAN
        // input sekarang -> ekor reverb mulai ~100 ms terlambat dan terdengar seperti delay. (Di MIXDTX tidak
        // ketahuan karena pre-delay-nya tidak pernah 0.) Untuk delaySamples > 0 hasilnya identik dengan versi lama.
        preDelayBuf_[preDelayWrite_] = input;
        int readIndex = preDelayWrite_ - delaySamples;
        if (readIndex < 0) readIndex += size;
        tankInput = preDelayBuf_[readIndex];
        if (++preDelayWrite_ >= size) preDelayWrite_ = 0;
    }

    // 2) LFO goyang halus buat comb yang termasuk kNumModulatedCombs -
    // frekuensi & depth-nya sengaja kecil (lihat komentar di header), jadi
    // efeknya "melembutkan" tail, bukan bikin efek chorus yang kedengeran
    // terpisah.
    const float lfoInc = 2.0f * (float) M_PI * kLfoRateHz / (float) sampleRate_;
    lfoPhaseL_ += lfoInc;
    if (lfoPhaseL_ > 2.0f * (float) M_PI) lfoPhaseL_ -= 2.0f * (float) M_PI;
    lfoPhaseR_ += lfoInc;
    if (lfoPhaseR_ > 2.0f * (float) M_PI) lfoPhaseR_ -= 2.0f * (float) M_PI;
    const float modOffsetL = (std::sin(lfoPhaseL_) * 0.5f + 0.5f) * kLfoDepthSamples;
    const float modOffsetR = (std::sin(lfoPhaseR_) * 0.5f + 0.5f) * kLfoDepthSamples;

    // 1b) Pengkondisian input tangki: buang bass (< ~150Hz) biar ekor gak
    // butek, lalu pre-emphasis nada tinggi (> ~4kHz) sebesar Cess - ini
    // yang "mengisi" tangki dengan energi desis S/C/T supaya meluruh
    // panjang di dalam reverb, bukan cuma lewat sekilas di jalur dry.
    const float cess = cess_.load();
    lowCutState_ += 0.02f * (tankInput - lowCutState_);
    float tankIn = tankInput - lowCutState_;
    emphState_ += 0.4f * (tankIn - emphState_);
    const float highBand = tankIn - emphState_;
    tankIn = (tankIn + cess * 1.4f * highBand) * tankGain_.load();
    denormFlip_ = -denormFlip_;
    tankInput = tankIn + denormFlip_;

    float combOutL = 0.0f, combOutR = 0.0f;
    for (int i = 0; i < kNumCombs; ++i) {
        if (i < kNumModulatedCombs) {
            combOutL += combsL_[i].processModulated(tankInput, modOffsetL);
            combOutR += combsR_[i].processModulated(tankInput, modOffsetR);
        } else {
            combOutL += combsL_[i].process(tankInput);
            combOutR += combsR_[i].process(tankInput);
        }
    }
    for (auto &a: allpassL_) combOutL = a.process(combOutL);
    for (auto &a: allpassR_) combOutR = a.process(combOutR);

    // 3) Filter "air": one-pole low-pass di ujung. Terbukanya ikut Cess
    // (lihat komentar airStateL_ di header) - dulu konstan & terlalu gelap.
    const float airCoeff = 0.55f + 0.40f * cess;
    airStateL_ += airCoeff * (combOutL - airStateL_);
    airStateR_ += airCoeff * (combOutR - airStateR_);
    combOutL = airStateL_;
    combOutR = airStateR_;

    const float width = width_.load();
    const float wet1 = width * 0.5f + 0.5f;
    const float wet2 = (1.0f - width) * 0.5f;
    const float wetL = combOutL * wet1 + combOutR * wet2;
    const float wetR = combOutR * wet1 + combOutL * wet2;

    const float wet = wet_.load();
    const float dry = dry_.load();
    outL = dry * input + wet * wetL;
    outR = dry * input + wet * wetR;
}

void FreeverbStyle::clear() {
    for (auto &x: combsL_) { std::fill(x.buffer.begin(), x.buffer.end(), 0.0f); x.index = 0; x.filterStore = 0.0f; }
    for (auto &x: combsR_) { std::fill(x.buffer.begin(), x.buffer.end(), 0.0f); x.index = 0; x.filterStore = 0.0f; }
    for (auto &x: allpassL_) { std::fill(x.buffer.begin(), x.buffer.end(), 0.0f); x.index = 0; }
    for (auto &x: allpassR_) { std::fill(x.buffer.begin(), x.buffer.end(), 0.0f); x.index = 0; }
    std::fill(preDelayBuf_.begin(), preDelayBuf_.end(), 0.0f);
    preDelayWrite_ = 0;
    airStateL_ = airStateR_ = 0.0f;
    lowCutState_ = emphState_ = 0.0f;
}

// ---------------------------------------------------------------------------
// PadReverb
// ---------------------------------------------------------------------------
namespace {
// Pad & lagu disimpan 48 kHz (lihat kMusicSampleRate di PadEngine). Buffer reverb dialokasi
// SEKALI di sini (saat library dimuat, di luar audio thread). Kalau stream ternyata jalan di
// rate lain, ekor reverb hanya sedikit lebih panjang/pendek - tidak ada alokasi di audio thread.
constexpr int kReverbRate = 48000;
// Skala level: knob penuh = wet 0.45 (dikalibrasi mirip knob WET mik MIXDTX: default 0.35-0.38).
constexpr float kWetMax = 0.45f;
// Fade switch ~25 ms (hanya untuk menghilangkan klik; dry tidak ikut ditunda).
constexpr float kFadeSeconds = 0.025f;
// Level early reflections relatif ke wet (dikalibrasi lewat uji envelope: menyambung mulus ke ekor di ~25 ms).
constexpr float kErScale = 0.21f;
// Pantulan awal naik lebih landai daripada LEVEL (di LEVEL penuh dikurangi 40%): menjaga warna suara pad
// (ripple comb filter) tetap kecil di LEVEL tinggi, sambil tetap terasa langsung di LEVEL bawaan.
constexpr float kErLevelComp = 0.40f;
}

PadReverb &PadReverb::instance() {
    static PadReverb inst;
    return inst;
}

// Paksa instance (dan alokasi bufernya) dibuat saat library dimuat oleh System.loadLibrary,
// di luar audio thread - jadi callback audio tidak pernah kebagian alokasi/konstruksi.
static const bool kPadReverbCreatedAtLoad = (PadReverb::instance(), true);

PadReverb::PadReverb() {
    fv_.prepare(kReverbRate);
    fv_.setPreDelayMs(0.0f);   // tanpa jeda sebelum ekor
    fv_.setCess(0.3f);
    fv_.setDry(0.0f);          // WET ONLY: dry pad tidak lewat sini
    fv_.setWet(1.0f);          // level diatur di wrapper (wetSmooth_)
    fv_.setEnabled(true);

    // Waktu tap (ms): RAPAT di awal lalu merenggang (seperti pantulan ruang asli), kiri/kanan beda, semuanya < 25 ms
    // supaya menyatu dengan suara asli. Gain TURUN dari kuat ke lemah -> reverb langsung terasa di pukulan
    // (loudness di awal), bukan baru "mekar" puluhan ms kemudian. Tanda bergantian agar tidak jadi comb filter.
    static const float msL[kErTaps] = {1.2f, 2.4f, 3.7f, 5.1f, 6.8f, 8.7f, 11.0f, 13.7f, 17.0f, 20.5f, 23.9f};
    static const float msR[kErTaps] = {1.7f, 3.0f, 4.4f, 6.0f, 7.8f, 9.9f, 12.4f, 15.3f, 18.6f, 22.0f, 24.6f};
    for (int i = 0; i < kErTaps; ++i) {
        const float ramp = 1.0f - (1.0f - 0.5f) * (float) i / (float) (kErTaps - 1);
        const float sgn = (i % 2 == 0) ? 1.0f : -1.0f;
        erTapL_[i] = std::min(kErSize - 1, (int) std::lround(msL[i] * 0.001f * kReverbRate));
        erTapR_[i] = std::min(kErSize - 1, (int) std::lround(msR[i] * 0.001f * kReverbRate));
        erGainL_[i] = sgn * ramp;
        erGainR_[i] = -sgn * ramp;
    }
}

void PadReverb::processAdd(float *out, int32_t numFrames, int channels) {
    const bool on = enabled_.load(std::memory_order_relaxed);
    // OFF dan fade-out sudah selesai -> tidak melakukan apa pun.
    if (!on && gain_ <= 0.0f) {
        wasRunning_ = false;
        return;
    }
    if (channels < 1 || channels > 2) return;   // layout lain: lewati saja (tidak pernah dipakai app ini)

    if (on && !wasRunning_) {
        fv_.clear();            // mulai bersih, tanpa sisa ekor lama
        erBuf_.fill(0.0f);
        erW_ = 0;
        wetSmooth_ = level_.load(std::memory_order_relaxed) * kWetMax;
    }
    wasRunning_ = true;

    // Parameter bentuk ruang: hanya di-set kalau berubah (setRoomSize menghitung ulang feedback 16 comb).
    const float room = room_.load(std::memory_order_relaxed);
    const float damp = damp_.load(std::memory_order_relaxed);
    const float width = width_.load(std::memory_order_relaxed);
    if (room != lastRoom_)   { fv_.setRoomSize(room); lastRoom_ = room; }
    if (damp != lastDamp_)   { fv_.setDamp(damp);     lastDamp_ = damp; }
    if (width != lastWidth_) { fv_.setWidth(width);   lastWidth_ = width; }

    const float wetTarget = level_.load(std::memory_order_relaxed) * kWetMax;
    const float fadeStep = 1.0f / (kFadeSeconds * (float) kReverbRate);
    const float smoothK = 0.002f;                         // one-pole, ~10 ms

    for (int32_t f = 0; f < numFrames; ++f) {
        gain_ += on ? fadeStep : -fadeStep;
        gain_ = std::fmax(0.0f, std::fmin(1.0f, gain_));
        wetSmooth_ += smoothK * (wetTarget - wetSmooth_);

        float *p = out + (size_t) f * channels;
        const float mono = (channels == 2) ? 0.5f * (p[0] + p[1]) : p[0];

        float wl, wr;
        fv_.process(mono, wl, wr);                 // wet-only (dry diset 0)

        // early reflections: tap dari input mono yang baru (tap terpendek 1.7 ms -> tidak menunda dry sama sekali)
        erBuf_[erW_] = mono;
        float erL = 0.0f, erR = 0.0f;
        for (int t = 0; t < kErTaps; ++t) {
            erL += erGainL_[t] * erBuf_[(erW_ - erTapL_[t]) & (kErSize - 1)];
            erR += erGainR_[t] * erBuf_[(erW_ - erTapR_[t]) & (kErSize - 1)];
        }
        erW_ = (erW_ + 1) & (kErSize - 1);
        const float erM = 0.5f * (erL + erR), erS = 0.5f * (erL - erR);   // knob LEBAR juga berlaku di sini
        erL = erM + width * erS;
        erR = erM - width * erS;

        const float g = gain_ * wetSmooth_;
        const float eg = g * kErScale * (1.0f - kErLevelComp * (wetSmooth_ * (1.0f / kWetMax)));
        if (channels == 2) { p[0] += wl * g + erL * eg; p[1] += wr * g + erR * eg; }
        else               { p[0] += 0.5f * (wl + wr) * g + 0.5f * (erL + erR) * eg; }
    }
}

} // namespace padfx

// ---------------------------------------------------------------------------
// JNI (class Kotlin: com.kendang.realpads.PadReverb)
// ---------------------------------------------------------------------------
extern "C" {
JNIEXPORT void JNICALL Java_com_kendang_realpads_PadReverb_nativeSetEnabled(JNIEnv*, jobject, jboolean on) {
    padfx::PadReverb::instance().setEnabled(on == JNI_TRUE);
}
JNIEXPORT void JNICALL Java_com_kendang_realpads_PadReverb_nativeSetLevel(JNIEnv*, jobject, jfloat v) {
    padfx::PadReverb::instance().setLevel(v);
}
JNIEXPORT void JNICALL Java_com_kendang_realpads_PadReverb_nativeSetRoom(JNIEnv*, jobject, jfloat v) {
    padfx::PadReverb::instance().setRoom(v);
}
JNIEXPORT void JNICALL Java_com_kendang_realpads_PadReverb_nativeSetDamp(JNIEnv*, jobject, jfloat v) {
    padfx::PadReverb::instance().setDamp(v);
}
JNIEXPORT void JNICALL Java_com_kendang_realpads_PadReverb_nativeSetWidth(JNIEnv*, jobject, jfloat v) {
    padfx::PadReverb::instance().setWidth(v);
}
}
