#pragma once

#include <jni.h>

namespace libretrodroid {

/** JNI can copy an array or return null with OOM pending. Always release successful pins. */
class ReadOnlyJniBytes {
public:
    ReadOnlyJniBytes(JNIEnv* env, jbyteArray array)
        : env_(env), array_(array), data_(env->GetByteArrayElements(array, nullptr)) {}
    ~ReadOnlyJniBytes() {
        if (data_) env_->ReleaseByteArrayElements(array_, data_, JNI_ABORT);
    }
    ReadOnlyJniBytes(const ReadOnlyJniBytes&) = delete;
    ReadOnlyJniBytes& operator=(const ReadOnlyJniBytes&) = delete;
    jbyte* data() const { return data_; }
private:
    JNIEnv* env_;
    jbyteArray array_;
    jbyte* data_;
};

template<class Restore>
bool restoreJniBytes(JNIEnv* env, jbyteArray array, Restore&& restore) {
    if (!array) return false;
    const auto size = env->GetArrayLength(array);
    if (size <= 0 || size > 32 * 1024 * 1024) return false;
    ReadOnlyJniBytes bytes(env, array);
    // Preserve the pending JVM exception. Never pass/release a null pointer.
    if (!bytes.data()) return false;
    return restore(bytes.data(), size);
}

} // namespace libretrodroid
