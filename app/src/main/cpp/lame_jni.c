#include <jni.h>
#include <stdint.h>
#include "libmp3lame/lame.h"

/* Тонкая JNI-обёртка над LAME. Один дескриптор (указатель) на один кодировщик. */

JNIEXPORT jlong JNICALL
Java_app_soundcatcher_LameEncoder_nativeInit(JNIEnv *env, jobject thiz,
                                             jint sampleRate, jint channels,
                                             jint bitrate, jint quality) {
    lame_t g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, sampleRate);
    lame_set_num_channels(g, channels);
    lame_set_mode(g, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(g, bitrate);
    lame_set_VBR(g, vbr_off);
    lame_set_quality(g, quality);
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    return (jlong) (intptr_t) g;
}

JNIEXPORT jint JNICALL
Java_app_soundcatcher_LameEncoder_nativeEncode(JNIEnv *env, jobject thiz,
                                               jlong handle, jshortArray pcm,
                                               jint frames, jbyteArray out) {
    lame_t g = (lame_t) (intptr_t) handle;
    if (!g) return -1;
    jshort *p = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize outSize = (*env)->GetArrayLength(env, out);
    int r;
    if (lame_get_num_channels(g) == 1) {
        r = lame_encode_buffer(g, p, p, frames, (unsigned char *) o, outSize);
    } else {
        r = lame_encode_buffer_interleaved(g, p, frames, (unsigned char *) o, outSize);
    }
    (*env)->ReleaseShortArrayElements(env, pcm, p, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT jint JNICALL
Java_app_soundcatcher_LameEncoder_nativeFlush(JNIEnv *env, jobject thiz,
                                              jlong handle, jbyteArray out) {
    lame_t g = (lame_t) (intptr_t) handle;
    if (!g) return -1;
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize outSize = (*env)->GetArrayLength(env, out);
    int r = lame_encode_flush(g, (unsigned char *) o, outSize);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT void JNICALL
Java_app_soundcatcher_LameEncoder_nativeClose(JNIEnv *env, jobject thiz, jlong handle) {
    lame_t g = (lame_t) (intptr_t) handle;
    if (g) lame_close(g);
}
