#include <jni.h>
#include <vector>
#include "lame/lame.h"

/**
 * LAME 的极简 JNI 封装：PCM 16-bit 小端单声道 → MP3。
 * 静态链接 libmp3lame.a，APK 只增加几百 KB。
 */
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ebook_voicereader_export_LameEncoder_nativeInit(
        JNIEnv *, jobject, jint inRate, jint channels, jint outRate, jint kbps) {
    lame_t lame = lame_init();
    if (!lame) return 0;
    lame_set_in_samplerate(lame, inRate);
    lame_set_num_channels(lame, channels);
    lame_set_out_samplerate(lame, outRate);
    lame_set_mode(lame, channels == 1 ? MONO : STEREO);
    lame_set_brate(lame, kbps);
    lame_set_VBR(lame, vbr_off);
    lame_set_quality(lame, 5);
    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return 0;
    }
    return reinterpret_cast<jlong>(lame);
}

JNIEXPORT jbyteArray JNICALL
Java_com_ebook_voicereader_export_LameEncoder_nativeEncode(
        JNIEnv *env, jobject, jlong handle, jbyteArray pcm, jint length) {
    lame_t lame = reinterpret_cast<lame_t>(handle);
    if (!lame || length <= 0) return nullptr;

    const int samples = length / 2;
    // LAME 官方建议的输出缓冲大小：1.25 * 采样数 + 7200
    const int capacity = static_cast<int>(1.25 * samples + 7200);
    std::vector<unsigned char> out(capacity);

    jbyte *data = env->GetByteArrayElements(pcm, nullptr);
    if (!data) return nullptr;
    const short int *samplesPtr = reinterpret_cast<const short int *>(data);
    // 单声道时 LAME 要求左右声道传同一指针
    const int written = lame_encode_buffer(
            lame, samplesPtr, samplesPtr, samples, out.data(), capacity);
    env->ReleaseByteArrayElements(pcm, data, JNI_ABORT);

    if (written < 0) return nullptr;
    jbyteArray result = env->NewByteArray(written);
    if (result && written > 0) {
        env->SetByteArrayRegion(result, 0, written,
                                reinterpret_cast<const jbyte *>(out.data()));
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ebook_voicereader_export_LameEncoder_nativeFlush(
        JNIEnv *env, jobject, jlong handle) {
    lame_t lame = reinterpret_cast<lame_t>(handle);
    if (!lame) return nullptr;
    std::vector<unsigned char> out(7200);
    const int written = lame_encode_flush(lame, out.data(), static_cast<int>(out.size()));
    if (written < 0) return nullptr;
    jbyteArray result = env->NewByteArray(written);
    if (result && written > 0) {
        env->SetByteArrayRegion(result, 0, written,
                                reinterpret_cast<const jbyte *>(out.data()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_ebook_voicereader_export_LameEncoder_nativeClose(
        JNIEnv *, jobject, jlong handle) {
    lame_t lame = reinterpret_cast<lame_t>(handle);
    if (lame) lame_close(lame);
}

}
