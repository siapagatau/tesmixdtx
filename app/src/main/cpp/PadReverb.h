#pragma once
// ============================================================================
// PadReverb - efek REVERB untuk output pad DTX (switch on/off + knob).
//
// DSP-nya = FreeverbStyle yang sama persis dengan efek mik di MIXDTX (8 comb +
// 4 allpass, pre-delay, cess, LFO halus), disalin apa adanya supaya karakter
// suaranya sama. Satu-satunya tambahan: FreeverbStyle::clear().
//
// KENAPA TIDAK ADA DELAY TAMBAHAN:
//  - Reverb dipasang sebagai SEND PARALEL: sinyal pad (dry) TIDAK dilewatkan
//    ke dalam reverb lalu keluar lagi. Dry tetap persis seperti sebelumnya
//    (tidak disentuh sama sekali), reverb hanya MENAMBAHKAN ekor (wet) di atasnya.
//  - Pre-delay diset 0 ms, jadi ekor mulai sedini mungkin.
//  - Tidak ada alokasi memori di audio thread (semua buffer dialokasi saat
//    library dimuat), tidak ada lock, hanya atomic.
//  - Saat OFF (default), process() langsung return: nol CPU, nol efek ke suara.
// ============================================================================
#include <array>
#include <vector>
#include <atomic>
#include <cmath>
#include <algorithm>
#include <cstdint>

namespace padfx {

class FreeverbStyle {
public:
    void prepare(int sampleRate);

    // BARU (khusus salinan di DTX): kosongkan semua delay line & filter tanpa alokasi memori.
    // Dipanggil saat reverb dinyalakan lagi, supaya sisa ekor lama tidak "meledak" keluar.
    void clear();

    // Parameter 0..1 semua, langsung dari knob UI:
    //  roomSize -> makin gede makin "membesar" ruangnya (feedback comb makin tinggi, ekor makin panjang)
    //  damp     -> makin gede makin "gelap"/teredam nada tingginya
    //  width    -> stereo spread hasil reverb (0 = mono, 1 = paling lebar)
    //  wet/dry  -> level sinyal basah/kering yang di-mix ke output
    void setRoomSize(float v);
    void setDamp(float v);
    void setWidth(float v);
    void setWet(float v) { wet_.store(v); }
    void setDry(float v) { dry_.store(v); }
    // cess -> 0..1, karakter "ssss" panjang ala reverb Alesis. Makin gede:
    // (1) nada tinggi (4-9kHz, tempat huruf S/C/T) makin banyak masuk ke
    // tangki reverb, (2) redaman nada tinggi di ekor makin kecil jadi
    // desisnya meluruh lama (cesss...), (3) filter penghalus ujung wet
    // makin terbuka. Cess = 0 -> reverb gelap/halus biasa.
    void setCess(float v);
    // preDelayMs -> jeda sebelum ekor reverb mulai "membuncah", 0..80ms.
    // Ini yang bikin reverb unit niatan (Alesis dkk) kedengeran rapi/jelas
    // dibanding reverb bawaan mixer murah: vokal kering kedengar dulu utuh,
    // BARU nyusul dengungnya - bukan nyampur langsung jadi "becek". Aman
    // dipanggil kapan aja, gak realokasi (buffer pre-delay dialokasi
    // sekali di prepare() sesuai kMaxPreDelayMs).
    void setPreDelayMs(float ms);

    // Bypass total efek reverb-nya (Wet komponen jadi 0, delay line comb
    // BERHENTI diproses - hemat CPU, bukan cuma di-mute doang). Suara mic
    // "kering" tetap keluar sesuai knob Dry, cuma dengungnya yang hilang.
    // Nilai knob (roomSize/damp/dst) TETAP kesimpen walau di-off-in, jadi
    // pas di-on-in lagi settingannya balik persis kayak sebelumnya.
    void setEnabled(bool e) { enabled_.store(e); }

    // input = 1 sample mono dari mic (frame ini), outL/outR = hasil MIX AKHIR
    // (dry + wet) yang siap ditulis ke buffer output stereo.
    //
    // PENTING soal delay: comb/allpass di bawah ini MEMANG punya "delay line"
    // internal, tapi itu cuma buat MEMBENTUK karakter ekor reverb-nya (gimana
    // dengungnya berkembang), BUKAN nunda sinyal input->output. Bagian "dry"
    // (dry_*input) ditambahkan LANGSUNG di frame yang sama, gak nunggu apa-apa.
    // Jadi suara asli tetep nyampe speaker secepat mungkin (nol delay
    // tambahan); yang "telat dikit-dikit" cuma ekor reverbnya doang, dan itu
    // memang sifat alami reverb - bukan bug/lag di app-nya.
    void process(float input, float &outL, float &outR);

private:
    struct Comb {
        std::vector<float> buffer;
        int index = 0;
        float feedback = 0.5f;
        float damp1 = 0.2f;
        float damp2 = 0.8f;
        float filterStore = 0.0f;
        void setSize(int size) {
            buffer.assign(size > 0 ? size : 1, 0.0f);
            index = 0;
            filterStore = 0.0f;
        }
        float process(float in) {
            float output = buffer[index];
            filterStore = (output * damp2) + (filterStore * damp1);
            buffer[index] = in + (filterStore * feedback);
            if (++index >= (int) buffer.size()) index = 0;
            return output;
        }
        // Sama seperti process(), tapi TITIK BACA-nya digeser sedikit ke
        // depan dari titik tulis (readOffset dalam sample, biasanya kecil
        // & goyang lewat LFO), diinterpolasi linear biar geserannya halus
        // (gak "klik"). Ini yang bikin karakter comb ini goyang halus
        // (chorus-y) alih-alih dengung statis - buffer sudah dialokasi
        // extra headroom di prepare() khusus buat comb yang pakai fungsi
        // ini, jadi aman gak kebaca sample yang belum sempat ditulis.
        float processModulated(float in, float readOffset) {
            const int size = (int) buffer.size();
            float readPos = (float) index + readOffset;
            int i0 = ((int) readPos) % size;
            if (i0 < 0) i0 += size;
            int i1 = i0 + 1;
            if (i1 >= size) i1 = 0;
            float frac = readPos - std::floor(readPos);
            float output = buffer[i0] * (1.0f - frac) + buffer[i1] * frac;
            filterStore = (output * damp2) + (filterStore * damp1);
            buffer[index] = in + (filterStore * feedback);
            if (++index >= size) index = 0;
            return output;
        }
    };
    struct Allpass {
        std::vector<float> buffer;
        int index = 0;
        float feedback = 0.5f;
        void setSize(int size) {
            buffer.assign(size > 0 ? size : 1, 0.0f);
            index = 0;
        }
        float process(float in) {
            float bufOut = buffer[index];
            float output = -in + bufOut;
            buffer[index] = in + (bufOut * feedback);
            if (++index >= (int) buffer.size()) index = 0;
            return output;
        }
    };

    static constexpr int kNumCombs = 8;
    static constexpr int kNumAllpasses = 4;
    // 4 dari 8 comb dimodulasi dikit (bukan semua) - biar tetep ada comb
    // yang "diam" jadi jangkar pitch, sementara yang lain goyang halus buat
    // shimmer. Modulasi di SEMUA comb sekaligus malah bikin "wobbly"/pitchy,
    // bukan lush.
    static constexpr int kNumModulatedCombs = 4;
    static constexpr float kMaxPreDelayMs = 80.0f;

    std::array<Comb, kNumCombs> combsL_;
    std::array<Comb, kNumCombs> combsR_;
    std::array<Allpass, kNumAllpasses> allpassL_;
    std::array<Allpass, kNumAllpasses> allpassR_;

    // Buffer pre-delay (mono, sebelum masuk ke tangki comb/allpass).
    std::vector<float> preDelayBuf_;
    int preDelayWrite_ = 0;
    std::atomic<int> preDelaySamples_{0};
    int sampleRate_ = 44100;

    // LFO modulasi halus buat 2 comb (kiri & kanan beda fase dikit biar
    // stereo-nya gerak, bukan mono-modulated). Depth-nya SENGAJA kecil
    // (dalam sample, bukan ms) - cukup buat "melembutkan" ringing metalik,
    // gak sampe kedengeran kayak efek chorus terpisah.
    float lfoPhaseL_ = 0.0f;
    float lfoPhaseR_ = 1.57f; // ~90 derajat offset dari L
    static constexpr float kLfoRateHz = 0.6f;
    static constexpr float kLfoDepthSamples = 3.0f;

    // Filter "air" di ujung sinyal wet (one-pole low-pass). Dulu koefisiennya
    // KONSTAN 0.35 (low-pass ~3kHz) -> semua desis "s" di ekor reverb
    // terpotong, itu penyebab suara kurang "cess". Sekarang koefisiennya
    // ikut knob Cess: 0.55 (~6kHz, hangat) sampai 0.95 (praktis terbuka).
    float airStateL_ = 0.0f;
    float airStateR_ = 0.0f;

    // Filter sisi input tangki: low-cut (buang dengung bass biar wet gak
    // butek, desis jadi menonjol) + high-pass buat pre-emphasis nada tinggi.
    float lowCutState_ = 0.0f;
    // BARU (DTX): derau ±1e-18 bergantian tiap sampel, disuntik DI DALAM tangki (setelah low-cut) supaya
    // ekor yang meluruh tidak jatuh ke bilangan denormal (CPU ARM jadi lambat). Tak terdengar (~-360 dB).
    float denormFlip_ = 1e-18f;
    float emphState_ = 0.0f;
    // Kompensasi level tangki: makin panjang feedback comb, makin besar
    // penumpukan energi - input diturunin biar tail panjang gak jadi
    // kenceng/meledak, cuma jadi lebih lama.
    std::atomic<float> tankGain_{0.6f};

    void updateDamping();

    std::atomic<float> cess_{0.6f};
    std::atomic<float> wet_{0.3f};
    std::atomic<float> dry_{1.0f};
    std::atomic<float> width_{1.0f};
    std::atomic<float> roomSize_{0.5f};
    std::atomic<float> damp_{0.5f};
    std::atomic<bool> enabled_{true};
};


// Pemroses reverb untuk buffer output PadEngine (interleaved float).
class PadReverb {
public:
    static PadReverb &instance();

    // Dipanggil dari audio thread (PadEngine::render) - menambah ekor reverb ke `out`.
    void processAdd(float *out, int32_t numFrames, int channels);

    // Dipanggil dari UI thread (JNI). Semuanya atomic -> aman.
    void setEnabled(bool on) { enabled_.store(on, std::memory_order_relaxed); }
    void setLevel(float v)   { level_.store(clamp01(v), std::memory_order_relaxed); }     // 0..1
    void setRoom(float v)    { room_.store(clamp01(v), std::memory_order_relaxed); }      // 0..1
    void setDamp(float v)    { damp_.store(clamp01(v), std::memory_order_relaxed); }      // 0..1
    void setWidth(float v)   { width_.store(clamp01(v), std::memory_order_relaxed); }     // 0..1

private:
    PadReverb();
    static float clamp01(float v) { return std::fmax(0.0f, std::fmin(1.0f, v)); }

    FreeverbStyle fv_;
    std::atomic<bool>  enabled_{false};   // DEFAULT OFF
    std::atomic<float> level_{0.35f};
    std::atomic<float> room_{0.55f};
    std::atomic<float> damp_{0.35f};
    std::atomic<float> width_{1.0f};

    // EARLY REFLECTIONS (v1.12.3): Freeverb hanya delay line murni -> setelah pukulan SENYAP total sampai comb
    // terpendek terisi (~23 ms), lalu ekor muncul tiba-tiba. Itu terdengar sebagai reverb "telat masuk".
    // Beberapa tap pendek (1.7-24 ms) mengisi celah itu supaya reverb sudah terasa dari ~2 ms dan menyambung
    // ke ekor. Ekor Freeverb-nya sendiri TIDAK diubah. Semua buffer statis (tanpa alokasi).
    static constexpr int kErTaps = 11;
    static constexpr int kErSize = 2048;           // pangkat 2, > 24.4 ms pada 48 kHz
    std::array<float, kErSize> erBuf_{};
    int erW_ = 0;
    int   erTapL_[kErTaps], erTapR_[kErTaps];
    float erGainL_[kErTaps], erGainR_[kErTaps];

    // State audio thread saja (tidak dipakai thread lain).
    float gain_ = 0.0f;          // fade masuk/keluar send (0..1) -> tanpa klik saat switch
    float wetSmooth_ = 0.0f;     // level yang dihaluskan -> tanpa zipper saat knob diputar
    float lastRoom_ = -1, lastDamp_ = -1, lastWidth_ = -1;
    bool  wasRunning_ = false;
};

} // namespace padfx
