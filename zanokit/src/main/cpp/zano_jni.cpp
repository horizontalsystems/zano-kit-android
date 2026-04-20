#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <cstdlib>

#define LOG_TAG "ZanoKit"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef ZANO_LIBS_AVAILABLE
#include "wallet2_api_c.h"
#endif

// Helper: convert jstring to std::string
static std::string jstring_to_string(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// Helper: convert const char* result from Zano C API to jstring, then free the pointer
static jstring result_to_jstring(JNIEnv *env, const char *result) {
    if (!result) return env->NewStringUTF("");
    jstring jstr = env->NewStringUTF(result);
#ifdef ZANO_LIBS_AVAILABLE
    ZANO_free((void*)result);
#endif
    return jstr;
}

extern "C" {

// ----- Library lifecycle -----

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_init2(
        JNIEnv *env, jobject, jstring jIp, jstring jPort, jstring jWorkingDir, jint logLevel) {
    LOGI("init2 called");
#ifdef ZANO_LIBS_AVAILABLE
    auto ip = jstring_to_string(env, jIp);
    auto port = jstring_to_string(env, jPort);
    auto dir = jstring_to_string(env, jWorkingDir);
    return result_to_jstring(env, ZANO_PlainWallet_init2(ip.c_str(), port.c_str(), dir.c_str(), logLevel));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_deinit(JNIEnv *env, jobject) {
    LOGI("deinit called");
#ifdef ZANO_LIBS_AVAILABLE
    ZANO_PlainWallet_deinit();
#endif
}

// ----- Wallet file operations -----

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_isWalletExist(JNIEnv *env, jobject, jstring jPath) {
#ifdef ZANO_LIBS_AVAILABLE
    return (jboolean) ZANO_PlainWallet_isWalletExist(jstring_to_string(env, jPath).c_str());
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_openWallet(JNIEnv *env, jobject, jstring jPath, jstring jPassword) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_open(
            jstring_to_string(env, jPath).c_str(),
            jstring_to_string(env, jPassword).c_str()));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_restoreWallet(
        JNIEnv *env, jobject, jstring jSeed, jstring jPath, jstring jPassword, jstring jSeedPassword) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_restore(
            jstring_to_string(env, jSeed).c_str(),
            jstring_to_string(env, jPath).c_str(),
            jstring_to_string(env, jPassword).c_str(),
            jstring_to_string(env, jSeedPassword).c_str()));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_closeWallet(JNIEnv *env, jobject, jlong walletId) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_closeWallet((int64_t) walletId));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_generateWallet(JNIEnv *env, jobject, jstring jPath, jstring jPassword) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_generate(
            jstring_to_string(env, jPath).c_str(),
            jstring_to_string(env, jPassword).c_str()));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

// ----- Core wallet operations -----

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_invoke(JNIEnv *env, jobject, jlong walletId, jstring jParams) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_invoke(
            (int64_t) walletId,
            jstring_to_string(env, jParams).c_str()));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_getWalletStatus(JNIEnv *env, jobject, jlong walletId) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_getWalletStatus((int64_t) walletId));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_syncCall(
        JNIEnv *env, jobject, jstring jMethod, jlong instanceId, jstring jParams) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_syncCall(
            jstring_to_string(env, jMethod).c_str(),
            (uint64_t) instanceId,
            jstring_to_string(env, jParams).c_str()));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

// ----- Static utilities -----

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_getCurrentTxFee(JNIEnv *env, jobject, jlong priority) {
#ifdef ZANO_LIBS_AVAILABLE
    return (jlong) ZANO_PlainWallet_getCurrentTxFee((uint64_t) priority);
#else
    return 10000000000LL; // stub: 0.01 ZANO
#endif
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_getTimestampFromWord(JNIEnv *env, jobject, jstring jWord) {
#ifdef ZANO_LIBS_AVAILABLE
    bool passwordUsed = false;
    return (jlong) ZANO_getTimestampFromWord(jstring_to_string(env, jWord).c_str(), &passwordUsed);
#else
    return 0L;
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_generateAddress(JNIEnv *env, jobject, jstring jSeed, jstring jSeedPassword) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_generateAddress(
            jstring_to_string(env, jSeed).c_str(),
            jstring_to_string(env, jSeedPassword).c_str()));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_generateAddressFromDerivation(
        JNIEnv *env, jobject, jstring jDerivationHex, jboolean isAuditable) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_generateAddressFromDerivation(
            jstring_to_string(env, jDerivationHex).c_str(),
            (bool) isAuditable));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_getAddressInfo(JNIEnv *env, jobject, jstring jAddress) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_getAddressInfo(jstring_to_string(env, jAddress).c_str()));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_getVersion(JNIEnv *env, jobject) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_getVersion());
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_resetWalletPassword(JNIEnv *env, jobject, jlong walletId, jstring jPassword) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_resetWalletPassword(
            (int64_t) walletId,
            jstring_to_string(env, jPassword).c_str()));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_zanokit_ZanoNative_getWalletInfo(JNIEnv *env, jobject, jlong walletId) {
#ifdef ZANO_LIBS_AVAILABLE
    return result_to_jstring(env, ZANO_PlainWallet_getWalletInfo((int64_t) walletId));
#else
    return env->NewStringUTF("{\"stub\":\"ZANO_libs_not_linked\"}");
#endif
}

} // extern "C"
