#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <string>
#include <vector>

// Driver discovery only. This does not create models or run NPU inference.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_androidharness_app_local_DeviceHardware_neuralDevices(JNIEnv * env, jobject) {
    std::vector<std::string> result;
    void * lib = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (lib) {
        struct Device;
        auto count = reinterpret_cast<int (*)(uint32_t *)>(dlsym(lib, "ANeuralNetworks_getDeviceCount"));
        auto device = reinterpret_cast<int (*)(uint32_t, Device **)>(dlsym(lib, "ANeuralNetworks_getDevice"));
        auto name = reinterpret_cast<int (*)(const Device *, const char **)>(dlsym(lib, "ANeuralNetworksDevice_getName"));
        auto type = reinterpret_cast<int (*)(const Device *, int32_t *)>(dlsym(lib, "ANeuralNetworksDevice_getType"));
        auto version = reinterpret_cast<int (*)(const Device *, const char **)>(dlsym(lib, "ANeuralNetworksDevice_getVersion"));
        uint32_t n = 0;
        if (count && device && name && type && count(&n) == 0) {
            for (uint32_t i = 0; i < n && i < 64; ++i) {
                Device * d = nullptr; const char * label = nullptr; const char * revision = nullptr; int32_t kind = 0;
                if (device(i, &d) != 0 || !d || name(d, &label) != 0 || !label || type(d, &kind) != 0) continue;
                if (version) version(d, &revision);
                result.push_back(std::to_string(kind) + "\t" + label + "\t" + (revision ? revision : ""));
            }
        }
        dlclose(lib);
    }
    auto strings = env->FindClass("java/lang/String");
    auto output = env->NewObjectArray(result.size(), strings, nullptr);
    for (size_t i = 0; i < result.size(); ++i) {
        auto text = env->NewStringUTF(result[i].c_str());
        env->SetObjectArrayElement(output, i, text);
        env->DeleteLocalRef(text);
    }
    return output;
}
