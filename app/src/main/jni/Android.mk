LOCAL_PATH := $(call my-dir)

# LAME 预编译静态库（arm64-v8a / armeabi-v7a / x86_64）
include $(CLEAR_VARS)
LOCAL_MODULE := mp3lame
LOCAL_SRC_FILES := lame/lib/$(TARGET_ARCH_ABI)/libmp3lame.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/lame/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := mp3encoder
LOCAL_SRC_FILES := mp3_encoder.cpp
LOCAL_STATIC_LIBRARIES := mp3lame
LOCAL_C_INCLUDES := $(LOCAL_PATH)/lame/include
LOCAL_CPPFLAGS := -std=c++11 -fexceptions
include $(BUILD_SHARED_LIBRARY)
