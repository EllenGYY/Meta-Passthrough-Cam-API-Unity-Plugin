/*
 * Quest Camera Plugin for Unity - JNI Bridge
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>

#define LOG_TAG "QuestCameraJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Unity callback function pointers
typedef void (*FrameCallback)(const uint8_t* frameData, int32_t dataSize, 
                             int32_t width, int32_t height, int64_t timestamp,
                             const float* intrinsics, const float* distortion, 
                             const float* pose, bool isLeft);
typedef void (*ErrorCallback)(const char* errorMessage);

// NEW: Stereo frame callback
typedef void (*StereoFrameCallback)(const uint8_t* frameData, int32_t dataSize,
                                   int32_t width, int32_t height, int64_t timestamp,
                                   const float* stereoMetadata, int32_t metadataSize);

static FrameCallback g_leftFrameCallback = nullptr;
static FrameCallback g_rightFrameCallback = nullptr;
static ErrorCallback g_errorCallback = nullptr;
static StereoFrameCallback g_stereoFrameCallback = nullptr; // NEW
static JavaVM* g_jvm = nullptr;

// Helper function to get plugin instance
static jobject getPluginInstance(JNIEnv* env) {
    jclass pluginClass = env->FindClass("com/meta/questcamera/plugin/QuestCameraPlugin");
    if (!pluginClass) {
        LOGE("Failed to find QuestCameraPlugin class");
        return nullptr;
    }
    
    // Get companion object
    jclass companionClass = env->FindClass("com/meta/questcamera/plugin/QuestCameraPlugin$Companion");
    if (!companionClass) {
        LOGE("Failed to find Companion class");
        return nullptr;
    }
    
    jfieldID companionField = env->GetStaticFieldID(pluginClass, "Companion", "Lcom/meta/questcamera/plugin/QuestCameraPlugin$Companion;");
    if (!companionField) {
        LOGE("Failed to find Companion field");
        return nullptr;
    }
    
    jobject companion = env->GetStaticObjectField(pluginClass, companionField);
    if (!companion) {
        LOGE("Failed to get Companion object");
        return nullptr;
    }
    
    // Get getInstance method from companion
    jmethodID getInstanceMethod = env->GetMethodID(companionClass, "getInstance", "()Lcom/meta/questcamera/plugin/QuestCameraPlugin;");
    if (!getInstanceMethod) {
        LOGE("Failed to find getInstance method");
        return nullptr;
    }
    
    // Get the plugin instance
    jobject instance = env->CallObjectMethod(companion, getInstanceMethod);
    if (!instance) {
        LOGE("Failed to get plugin instance");
        return nullptr;
    }
    
    return instance;
}

extern "C" {

// Unity calls these to set callbacks
JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_setLeftFrameCallback(JNIEnv *env, jclass clazz, jlong callback) {
    LOGD("Setting left frame callback: %p", (void*)callback);
    g_leftFrameCallback = reinterpret_cast<FrameCallback>(callback);
}

JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_setRightFrameCallback(JNIEnv *env, jclass clazz, jlong callback) {
    LOGD("Setting right frame callback: %p", (void*)callback);
    g_rightFrameCallback = reinterpret_cast<FrameCallback>(callback);
}

JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_setErrorCallback(JNIEnv *env, jclass clazz, jlong callback) {
    LOGD("Setting error callback: %p", (void*)callback);
    g_errorCallback = reinterpret_cast<ErrorCallback>(callback);
}

// NEW: Stereo frame callback setter
JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_setStereoFrameCallback(JNIEnv *env, jclass clazz, jlong callback) {
    LOGD("Setting stereo frame callback: %p", (void*)callback);
    g_stereoFrameCallback = reinterpret_cast<StereoFrameCallback>(callback);
}

// Unity calls these for camera control
JNIEXPORT jboolean JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_nativeInitialize(JNIEnv *env, jclass clazz, jobject context) {
    LOGD("Native initialize called");
    
    jobject instance = getPluginInstance(env);
    if (!instance) {
        return JNI_FALSE;
    }
    
    jclass pluginClass = env->FindClass("com/meta/questcamera/plugin/QuestCameraPlugin");
    if (!pluginClass) {
        LOGE("Failed to find QuestCameraPlugin class");
        return JNI_FALSE;
    }
    
    // Call initialize on the instance
    jmethodID initMethod = env->GetMethodID(pluginClass, "initialize", "(Landroid/content/Context;)Z");
    if (!initMethod) {
        LOGE("Failed to find initialize method");
        return JNI_FALSE;
    }
    
    // Verify that context is indeed a Context
    jclass contextClass = env->FindClass("android/content/Context");
    if (!contextClass) {
        LOGE("Failed to find Context class");
        return JNI_FALSE;
    }
    
    if (!env->IsInstanceOf(context, contextClass)) {
        LOGE("Provided object is not a Context instance");
        return JNI_FALSE;
    }
    
    jboolean result = env->CallBooleanMethod(instance, initMethod, context);
    if (env->ExceptionCheck()) {
        LOGE("Exception occurred while calling initialize method");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return JNI_FALSE;
    }
    
    LOGD("Initialize result: %d", result);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_nativeStartDualCamera(JNIEnv *env, jclass clazz) {
    LOGD("Native start dual camera called");
    
    jobject pluginInstance = getPluginInstance(env);
    if (!pluginInstance) {
        return JNI_FALSE;
    }
    
    jclass pluginClass = env->FindClass("com/meta/questcamera/plugin/QuestCameraPlugin");
    if (!pluginClass) {
        LOGE("Failed to find QuestCameraPlugin class");
        return JNI_FALSE;
    }
    
    // Call startDualCamera method
    jmethodID startMethod = env->GetMethodID(pluginClass, "startDualCamera", "()Z");
    if (!startMethod) {
        LOGE("Failed to find startDualCamera method");
        return JNI_FALSE;
    }
    
    jboolean result = env->CallBooleanMethod(pluginInstance, startMethod);
    LOGD("Start dual camera result: %d", result);
    return result;
}

JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_nativeStopDualCamera(JNIEnv *env, jclass clazz) {
    LOGD("Native stop dual camera called");
    
    jobject pluginInstance = getPluginInstance(env);
    if (!pluginInstance) {
        return;
    }
    
    jclass pluginClass = env->FindClass("com/meta/questcamera/plugin/QuestCameraPlugin");
    if (!pluginClass) {
        LOGE("Failed to find QuestCameraPlugin class");
        return;
    }
    
    // Call stopDualCamera method
    jmethodID stopMethod = env->GetMethodID(pluginClass, "stopDualCamera", "()V");
    if (!stopMethod) {
        LOGE("Failed to find stopDualCamera method");
        return;
    }
    
    env->CallVoidMethod(pluginInstance, stopMethod);
    LOGD("Stop dual camera completed");
}

JNIEXPORT jboolean JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_nativeStartSingleCamera(JNIEnv *env, jclass clazz, jboolean isLeft) {
    LOGD("Native start single camera called (isLeft: %d)", isLeft);
    
    jobject pluginInstance = getPluginInstance(env);
    if (!pluginInstance) {
        return JNI_FALSE;
    }
    
    jclass pluginClass = env->FindClass("com/meta/questcamera/plugin/QuestCameraPlugin");
    if (!pluginClass) {
        LOGE("Failed to find QuestCameraPlugin class");
        return JNI_FALSE;
    }
    
    // Call startSingleCamera method
    jmethodID startMethod = env->GetMethodID(pluginClass, "startSingleCamera", "(Z)Z");
    if (!startMethod) {
        LOGE("Failed to find startSingleCamera method");
        return JNI_FALSE;
    }
    
    jboolean result = env->CallBooleanMethod(pluginInstance, startMethod, isLeft);
    LOGD("Start single camera result: %d", result);
    return result;
}

JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_nativeStopSingleCamera(JNIEnv *env, jclass clazz, jboolean isLeft) {
    LOGD("Native stop single camera called (isLeft: %d)", isLeft);
    
    jobject pluginInstance = getPluginInstance(env);
    if (!pluginInstance) {
        return;
    }
    
    jclass pluginClass = env->FindClass("com/meta/questcamera/plugin/QuestCameraPlugin");
    if (!pluginClass) {
        LOGE("Failed to find QuestCameraPlugin class");
        return;
    }
    
    // Call stopSingleCamera method with boolean parameter
    jmethodID stopMethod = env->GetMethodID(pluginClass, "stopSingleCamera", "(Z)V");
    if (!stopMethod) {
        LOGE("Failed to find stopSingleCamera method");
        return;
    }
    
    env->CallVoidMethod(pluginInstance, stopMethod, isLeft);
    LOGD("Stop single camera completed");
}

// Called from Kotlin when frames are available
JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_onLeftFrameAvailable(
    JNIEnv *env, jclass clazz, jbyteArray frameData, jint width, jint height, 
    jlong timestamp, jfloatArray intrinsics, jfloatArray distortion, jfloatArray pose) {
    
    if (g_leftFrameCallback == nullptr) {
        LOGD("Left frame callback is null, skipping frame");
        return;
    }
    
    // Get array elements
    jbyte* frameBytes = env->GetByteArrayElements(frameData, nullptr);
    jfloat* intrinsicsFloat = env->GetFloatArrayElements(intrinsics, nullptr);
    jfloat* distortionFloat = env->GetFloatArrayElements(distortion, nullptr);
    jfloat* poseFloat = env->GetFloatArrayElements(pose, nullptr);
    
    if (!frameBytes || !intrinsicsFloat || !distortionFloat || !poseFloat) {
        LOGE("Failed to get array elements for left frame");
        return;
    }
    
    jsize dataSize = env->GetArrayLength(frameData);
    
    LOGD("Calling left frame callback with %d bytes", dataSize);
    
    // Call Unity left callback
    g_leftFrameCallback(
        reinterpret_cast<const uint8_t*>(frameBytes), 
        dataSize, width, height, timestamp,
        intrinsicsFloat, distortionFloat, poseFloat, true
    );
    
    // Release array elements
    env->ReleaseByteArrayElements(frameData, frameBytes, JNI_ABORT);
    env->ReleaseFloatArrayElements(intrinsics, intrinsicsFloat, JNI_ABORT);
    env->ReleaseFloatArrayElements(distortion, distortionFloat, JNI_ABORT);
    env->ReleaseFloatArrayElements(pose, poseFloat, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_onRightFrameAvailable(
    JNIEnv *env, jclass clazz, jbyteArray frameData, jint width, jint height,
    jlong timestamp, jfloatArray intrinsics, jfloatArray distortion, jfloatArray pose) {
    
    if (g_rightFrameCallback == nullptr) {
        LOGD("Right frame callback is null, skipping frame");
        return;
    }
    
    // Get array elements
    jbyte* frameBytes = env->GetByteArrayElements(frameData, nullptr);
    jfloat* intrinsicsFloat = env->GetFloatArrayElements(intrinsics, nullptr);
    jfloat* distortionFloat = env->GetFloatArrayElements(distortion, nullptr);
    jfloat* poseFloat = env->GetFloatArrayElements(pose, nullptr);
    
    if (!frameBytes || !intrinsicsFloat || !distortionFloat || !poseFloat) {
        LOGE("Failed to get array elements for right frame");
        return;
    }
    
    jsize dataSize = env->GetArrayLength(frameData);
    
    LOGD("Calling right frame callback with %d bytes", dataSize);
    
    // Call Unity right callback
    g_rightFrameCallback(
        reinterpret_cast<const uint8_t*>(frameBytes),
        dataSize, width, height, timestamp,
        intrinsicsFloat, distortionFloat, poseFloat, false
    );
    
    // Release array elements
    env->ReleaseByteArrayElements(frameData, frameBytes, JNI_ABORT);
    env->ReleaseFloatArrayElements(intrinsics, intrinsicsFloat, JNI_ABORT);
    env->ReleaseFloatArrayElements(distortion, distortionFloat, JNI_ABORT);
    env->ReleaseFloatArrayElements(pose, poseFloat, JNI_ABORT);
}

// NEW: Called from Kotlin when stereo frame is available
JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_onStereoFrameAvailable(
    JNIEnv *env, jclass clazz, jbyteArray frameData, jint width, jint height,
    jlong timestamp, jfloatArray stereoMetadata) {
    
    if (g_stereoFrameCallback == nullptr) {
        LOGD("Stereo frame callback is null, skipping frame");
        return;
    }
    
    // Get array elements
    jbyte* frameBytes = env->GetByteArrayElements(frameData, nullptr);
    jfloat* metadataFloat = env->GetFloatArrayElements(stereoMetadata, nullptr);
    
    if (!frameBytes || !metadataFloat) {
        LOGE("Failed to get array elements for stereo frame");
        return;
    }
    
    jsize dataSize = env->GetArrayLength(frameData);
    jsize metadataSize = env->GetArrayLength(stereoMetadata);
    
    LOGD("Calling stereo frame callback with %d bytes, metadata size: %d", dataSize, metadataSize);
    
    // Call Unity stereo callback
    g_stereoFrameCallback(
        reinterpret_cast<const uint8_t*>(frameBytes),
        dataSize, width, height, timestamp,
        metadataFloat, metadataSize
    );
    
    // Release array elements
    env->ReleaseByteArrayElements(frameData, frameBytes, JNI_ABORT);
    env->ReleaseFloatArrayElements(stereoMetadata, metadataFloat, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_meta_questcamera_plugin_QuestCameraPlugin_onCameraError(JNIEnv *env, jclass clazz, jstring errorMessage) {
    if (g_errorCallback == nullptr) {
        return;
    }
    
    const char* errorStr = env->GetStringUTFChars(errorMessage, nullptr);
    if (errorStr) {
        LOGE("Camera error: %s", errorStr);
        g_errorCallback(errorStr);
        env->ReleaseStringUTFChars(errorMessage, errorStr);
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGD("JNI_OnLoad called");
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    LOGD("JNI_OnUnload called");
    g_jvm = nullptr;
}

} // extern "C"