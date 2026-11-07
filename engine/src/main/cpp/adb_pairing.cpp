// Adapted from RikkaApps/Shizuku v13.6.0 manager/src/main/jni/adb_pairing.cpp.
// Apache-2.0; provenance and dependency licences in ../adb-NOTICE.
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <new>
#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/evp.h>
#include <openssl/mem.h>

static_assert(SPAKE2_MAX_MSG_SIZE == 32, "Keep Kotlin SPAKE2 bound in sync");
static constexpr uint8_t kClientName[] = "adb pair client";
static constexpr uint8_t kServerName[] = "adb pair server";
static constexpr size_t kMaxPlaintext = 8192;
static constexpr size_t kTagBytes = 16;

struct Context {
    SPAKE2_CTX* spake = nullptr;
    EVP_AEAD_CTX* aes = nullptr;
    uint8_t message[SPAKE2_MAX_MSG_SIZE] = {};
    size_t message_size = 0;
    uint64_t enc_sequence = 0;
    uint64_t dec_sequence = 0;
    bool attempted = false;
};

static void Destroy(JNIEnv*, jobject, jlong pointer) {
    auto* ctx = reinterpret_cast<Context*>(pointer);
    if (!ctx) return;
    SPAKE2_CTX_free(ctx->spake);
    EVP_AEAD_CTX_free(ctx->aes);
    OPENSSL_cleanse(ctx, sizeof(*ctx));
    delete ctx;
}

static jlong Create(JNIEnv* env, jclass, jboolean client, jbyteArray password) {
    if (!password) return 0;
    auto size = env->GetArrayLength(password);
    if (size <= 0 || size > 128) return 0;
    auto* ctx = new (std::nothrow) Context();
    if (!ctx) return 0;
    ctx->spake = SPAKE2_CTX_new(client ? spake2_role_alice : spake2_role_bob,
        client ? kClientName : kServerName, client ? sizeof(kClientName) : sizeof(kServerName),
        client ? kServerName : kClientName, client ? sizeof(kServerName) : sizeof(kClientName));
    uint8_t bytes[128] = {};
    env->GetByteArrayRegion(password, 0, size, reinterpret_cast<jbyte*>(bytes));
    int ok = !env->ExceptionCheck() && ctx->spake && SPAKE2_generate_msg(ctx->spake, ctx->message,
        &ctx->message_size, sizeof(ctx->message), bytes, size);
    OPENSSL_cleanse(bytes, sizeof(bytes));
    if (!ok || !ctx->message_size) { Destroy(env, nullptr, reinterpret_cast<jlong>(ctx)); return 0; }
    return reinterpret_cast<jlong>(ctx);
}

static jbyteArray Bytes(JNIEnv* env, const uint8_t* bytes, size_t size) {
    auto result = env->NewByteArray(static_cast<jsize>(size));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(bytes));
    return result;
}

static jbyteArray Message(JNIEnv* env, jobject, jlong pointer) {
    auto* ctx = reinterpret_cast<Context*>(pointer);
    return ctx ? Bytes(env, ctx->message, ctx->message_size) : nullptr;
}

static jboolean Init(JNIEnv* env, jobject, jlong pointer, jbyteArray message) {
    auto* ctx = reinterpret_cast<Context*>(pointer);
    if (!ctx || !message || ctx->attempted) return JNI_FALSE;
    ctx->attempted = true;
    auto size = env->GetArrayLength(message);
    if (size <= 0 || size > SPAKE2_MAX_MSG_SIZE) return JNI_FALSE;
    uint8_t bytes[SPAKE2_MAX_MSG_SIZE] = {};
    uint8_t material[SPAKE2_MAX_KEY_SIZE] = {};
    uint8_t key[16] = {};
    size_t material_size = 0;
    env->GetByteArrayRegion(message, 0, size, reinterpret_cast<jbyte*>(bytes));
    static constexpr uint8_t info[] = "adb pairing_auth aes-128-gcm key";
    bool ok = !env->ExceptionCheck() && SPAKE2_process_msg(ctx->spake, material, &material_size,
        sizeof(material), bytes, size) && HKDF(key, sizeof(key), EVP_sha256(), material, material_size,
        nullptr, 0, info, sizeof(info) - 1);
    if (ok) ctx->aes = EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), key, sizeof(key), EVP_AEAD_DEFAULT_TAG_LENGTH);
    OPENSSL_cleanse(material, sizeof(material));
    OPENSSL_cleanse(key, sizeof(key));
    SPAKE2_CTX_free(ctx->spake);
    ctx->spake = nullptr;
    return ctx->aes ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray Crypt(JNIEnv* env, jlong pointer, jbyteArray input, bool encrypt) {
    auto* ctx = reinterpret_cast<Context*>(pointer);
    if (!ctx || !ctx->aes || !input) return nullptr;
    auto size = env->GetArrayLength(input);
    if (size < (encrypt ? 1 : static_cast<int>(kTagBytes)) ||
        size > static_cast<int>(encrypt ? kMaxPlaintext : kMaxPlaintext + kTagBytes)) return nullptr;
    auto& sequence = encrypt ? ctx->enc_sequence : ctx->dec_sequence;
    if (sequence == UINT64_MAX) return nullptr;
    uint8_t bytes[kMaxPlaintext + kTagBytes] = {};
    uint8_t out[kMaxPlaintext + kTagBytes] = {};
    uint8_t nonce[12] = {};
    // ADB's nonce is a little-endian sequence number followed by four zero bytes.
    for (size_t i = 0; i < sizeof(sequence); ++i) nonce[i] = static_cast<uint8_t>(sequence >> (i * 8));
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(bytes));
    size_t written = 0;
    bool ok = !env->ExceptionCheck() && (encrypt
        ? EVP_AEAD_CTX_seal(ctx->aes, out, &written, sizeof(out), nonce, sizeof(nonce), bytes, size, nullptr, 0)
        : EVP_AEAD_CTX_open(ctx->aes, out, &written, sizeof(out), nonce, sizeof(nonce), bytes, size, nullptr, 0));
    jbyteArray result = nullptr;
    if (ok) { ++sequence; result = Bytes(env, out, written); }
    OPENSSL_cleanse(bytes, sizeof(bytes));
    OPENSSL_cleanse(out, sizeof(out));
    return result;
}

static jbyteArray Encrypt(JNIEnv* env, jobject, jlong pointer, jbyteArray input) { return Crypt(env, pointer, input, true); }
static jbyteArray Decrypt(JNIEnv* env, jobject, jlong pointer, jbyteArray input) { return Crypt(env, pointer, input, false); }

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    auto clazz = env->FindClass("dev/launcher/engine/adb/PairingContext");
    if (!clazz) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"nativeConstructor", "(Z[B)J", reinterpret_cast<void*>(Create)},
        {"nativeMsg", "(J)[B", reinterpret_cast<void*>(Message)},
        {"nativeInitCipher", "(J[B)Z", reinterpret_cast<void*>(Init)},
        {"nativeEncrypt", "(J[B)[B", reinterpret_cast<void*>(Encrypt)},
        {"nativeDecrypt", "(J[B)[B", reinterpret_cast<void*>(Decrypt)},
        {"nativeDestroy", "(J)V", reinterpret_cast<void*>(Destroy)},
    };
    auto status = env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0]));
    env->DeleteLocalRef(clazz);
    return status == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
