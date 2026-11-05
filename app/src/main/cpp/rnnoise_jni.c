#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

#define FRAME 480

JNIEXPORT jlong JNICALL
Java_ir_smarthearing_ai_RNNoise_create(JNIEnv *env, jobject thiz) {
    return (jlong)(intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_ir_smarthearing_ai_RNNoise_destroy(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle) rnnoise_destroy((DenoiseState *)(intptr_t) handle);
}

/* in/out: ۴۸۰ نمونه float در مقیاس ۱۶ بیتی (±32768). خروجی: احتمال وجود گفتار (VAD) */
JNIEXPORT jfloat JNICALL
Java_ir_smarthearing_ai_RNNoise_process(JNIEnv *env, jobject thiz, jlong handle,
                                        jfloatArray in, jfloatArray out) {
    float inBuf[FRAME];
    float outBuf[FRAME];
    (*env)->GetFloatArrayRegion(env, in, 0, FRAME, inBuf);
    float vad = rnnoise_process_frame((DenoiseState *)(intptr_t) handle, outBuf, inBuf);
    (*env)->SetFloatArrayRegion(env, out, 0, FRAME, outBuf);
    return vad;
}
