#include "jni_bytes.h"
#include <cassert>
#include <iostream>
#include <stdexcept>

static int acquired = 0, released = 0, calls = 0;
static bool allocationFails = false;
static jsize length = 32;
static jbyte payload[32]{};
static jbyteArray array = reinterpret_cast<jbyteArray>(payload);
static jsize JNICALL getLength(JNIEnv*, jarray) { return length; }
static jbyte* JNICALL acquire(JNIEnv*, jbyteArray, jboolean*) {
    acquired++;
    return allocationFails ? nullptr : payload;
}
static void JNICALL release(JNIEnv*, jbyteArray value, jbyte* data, jint mode) {
    assert(value == array && data == payload && mode == JNI_ABORT);
    released++;
}

int main() {
    JNINativeInterface_ api{};
    api.GetArrayLength = getLength;
    api.GetByteArrayElements = acquire;
    api.ReleaseByteArrayElements = release;
    JNIEnv env{&api};
    auto restore = [](jbyte* data, jsize size) {
        assert(data == payload && size == 32);
        calls++;
        return true;
    };
    assert(!libretrodroid::restoreJniBytes(&env, nullptr, restore));
    for (jsize invalid : {0, -1, 32 * 1024 * 1024 + 1}) {
        length = invalid;
        assert(!libretrodroid::restoreJniBytes(&env, array, restore));
    }
    assert(acquired == 0 && calls == 0);
    length = 32;
    for (int i = 0; i < 10000; i++) {
        assert(libretrodroid::restoreJniBytes(&env, array, restore));
        assert(!libretrodroid::restoreJniBytes(&env, array, [](jbyte*, jsize) { return false; }));
        try {
            libretrodroid::restoreJniBytes(&env, array, [](jbyte*, jsize) -> bool { throw std::bad_alloc(); });
            assert(false);
        } catch (const std::bad_alloc&) {}
        assert(acquired == released);
    }
    const int before = calls;
    allocationFails = true;
    assert(!libretrodroid::restoreJniBytes(&env, array, restore));
    assert(calls == before && acquired == released + 1);
    std::cout << "30000 JNI acquire/release cycles, null/OOM and invalid-size paths passed\n";
}
