#include <jni.h>
#include <jvmti.h>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#include "sampler.h"

// The library is loaded with System.load (via instructive.agent.NativeLibraryLoader),
// not as a JVMTI agent: Agent_OnLoad/Agent_OnAttach are deliberately not defined. The
// sampler exists for as long as the library is loaded; sampling is started and stopped
// explicitly from Java through instructive.agent.Sampler.

namespace {

instructive::Sampler* sampler = nullptr;

// The system property `name` as a positive number of bytes, or `fallback`.
size_t size_property(JavaVM* vm, const char* name, size_t fallback) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
        return fallback;
    }
    size_t result = fallback;
    jclass system = env->FindClass("java/lang/System");
    jmethodID get_property =
        system == nullptr ? nullptr : env->GetStaticMethodID(system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring key = get_property == nullptr ? nullptr : env->NewStringUTF(name);
    auto value = key == nullptr ? nullptr
                                : static_cast<jstring>(env->CallStaticObjectMethod(system, get_property, key));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (value != nullptr) {
        const char* text = env->GetStringUTFChars(value, nullptr);
        if (text != nullptr) {
            char* end = nullptr;
            unsigned long long parsed = std::strtoull(text, &end, 10);
            if (end != text && *end == '\0' && parsed > 0) {
                result = static_cast<size_t>(parsed);
            }
            env->ReleaseStringUTFChars(value, text);
        }
    }
    env->DeleteLocalRef(value);
    env->DeleteLocalRef(key);
    env->DeleteLocalRef(system);
    return result;
}

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
        return JNI_ERR;
    }
    // Bytes reserved for per-PC counters, e.g. -Dinstructive.counterCapacity=67108864.
    size_t counter_capacity =
        size_property(vm, "instructive.counterCapacity", instructive::Sampler::DEFAULT_COUNTER_CAPACITY);
    sampler = new (std::nothrow) instructive::Sampler(vm, jvmti, counter_capacity);
    if (sampler == nullptr) {
        jvmti->DisposeEnvironment();
        return JNI_ERR;
    }
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    delete sampler;
    sampler = nullptr;
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_instructive_agent_Sampler_start0(JNIEnv* env, jclass cls) {
    if (sampler->start()) {
        return JNI_TRUE;
    }
    if (!sampler->running()) {
        int error = errno;
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception != nullptr) {
            env->ThrowNew(exception, std::strerror(error));
        }
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_instructive_agent_Sampler_stop0(JNIEnv* env, jclass cls) {
    return sampler->stop() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_instructive_agent_Sampler_cpuTicks0(JNIEnv* env, jclass cls) {
    return static_cast<jlong>(instructive::CpuTimer::ticks());
}

JNIEXPORT jboolean JNICALL Java_instructive_agent_Sampler_codeCacheAvailable0(JNIEnv* env, jclass cls) {
    return sampler->code_cache_available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL Java_instructive_agent_Sampler_kindCounts0(JNIEnv* env, jclass cls) {
    jlong counts[instructive::CODE_KIND_COUNT];
    for (size_t i = 0; i < instructive::CODE_KIND_COUNT; ++i) {
        counts[i] = static_cast<jlong>(sampler->kind_count(static_cast<instructive::CodeKind>(i)));
    }
    jlongArray result = env->NewLongArray(instructive::CODE_KIND_COUNT);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, instructive::CODE_KIND_COUNT, counts);
    }
    return result;
}

JNIEXPORT jboolean JNICALL Java_instructive_agent_Sampler_methodTracking0(JNIEnv* env, jclass cls) {
    return sampler->method_tracking() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL Java_instructive_agent_Sampler_profileBuffer0(JNIEnv* env, jclass cls) {
    const instructive::ProfileArena& arena = sampler->arena();
    if (!arena.available()) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(arena.base(), static_cast<jlong>(arena.capacity()));
}

JNIEXPORT jobject JNICALL Java_instructive_agent_Sampler_counterBuffer0(JNIEnv* env, jclass cls) {
    const instructive::CounterArena& counters = sampler->counters();
    if (!counters.available()) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(counters.base(), static_cast<jlong>(counters.capacity()));
}

JNIEXPORT jlong JNICALL Java_instructive_agent_Sampler_profileBytesUsed0(JNIEnv* env, jclass cls) {
    return static_cast<jlong>(sampler->arena().used());
}

// Copies the machine code of the nmethod whose code starts at `address`, for
// instructive.agent.InstructionProfile.machineCode(). Reading the code heap races with
// the JVM freeing and reusing code, so the nmethod found there must match the profile
// exactly: an nmethod with the same compile id, whose code starts at `address` and has
// `method_size` bytes (saturated as in the profile). The check is repeated after
// copying and the copy discarded if anything changed. Returns null when there is no
// match.
JNIEXPORT jbyteArray JNICALL Java_instructive_agent_InstructionProfile_machineCode0(JNIEnv* env, jclass cls,
                                                                                   jlong address, jint method_size,
                                                                                   jint compile_id) {
    const instructive::CodeCache& code_cache = sampler->code_cache();
    uintptr_t code_begin = static_cast<uintptr_t>(address);
    auto locate = [&](uintptr_t* begin, uintptr_t* end) {
        instructive::CodeLocation location = code_cache.lookup(code_begin);
        if (location.kind != instructive::CodeKind::Compiled || location.compile_id != compile_id ||
            !code_cache.code_range(location.blob, begin, end)) {
            return false;
        }
        size_t size = std::min<size_t>(*end - *begin, instructive::CompiledMethod::MAX_LENGTH);
        return *begin == code_begin && size == static_cast<size_t>(method_size);
    };
    uintptr_t begin, end;
    if (method_size <= 0 || !locate(&begin, &end) || end - begin > INT32_MAX) {
        return nullptr;
    }
    std::vector<jbyte> code(end - begin);
    std::memcpy(code.data(), reinterpret_cast<const void*>(begin), code.size());
    uintptr_t begin_after, end_after;
    if (!locate(&begin_after, &end_after) || begin_after != begin || end_after != end) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(code.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(code.size()), code.data());
    }
    return result;
}

// The name of the method with these dictionary codes, for
// instructive.agent.InstructionProfile.name(): "package.Class.name(signature)", or empty
// if a code is unknown. The dictionaries hold JVMTI's modified UTF-8, which is what
// NewStringUTF takes.
JNIEXPORT jstring JNICALL Java_instructive_agent_InstructionProfile_name0(JNIEnv* env, jclass cls, jint class_code,
                                                                         jint name_code, jint signature_code) {
    std::string name = sampler->method_name(static_cast<uint32_t>(class_code), static_cast<uint32_t>(name_code),
                                            static_cast<uint32_t>(signature_code));
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT void JNICALL Java_instructive_agent_Sampler_resetCounts0(JNIEnv* env, jclass cls) {
    sampler->reset_counts();
}

JNIEXPORT jboolean JNICALL Java_instructive_agent_Sampler_isRunning0(JNIEnv* env, jclass cls) {
    return sampler->running() ? JNI_TRUE : JNI_FALSE;
}

}
