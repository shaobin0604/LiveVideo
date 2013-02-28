LOCAL_PATH := $(call my-dir)

#declare the prebuilt library
include $(CLEAR_VARS)
FFMPEG := ffmpeg-0.8
ARMVER := armv5te
LOCAL_MODULE := libffmpeg-prebuilt
LOCAL_SRC_FILES := $(FFMPEG)/android/$(ARMVER)/libffmpeg.so
include $(PREBUILT_SHARED_LIBRARY)


include $(CLEAR_VARS)
FFMPEG := ffmpeg-0.8
ARMVER := armv5te
LOCAL_MODULE    := librtp-player
LOCAL_CFLAGS := -D__STDC_CONSTANT_MACROS
#LOCAL_ALLOW_UNDEFINED_SYMBOLS:=false
LOCAL_SRC_FILES := JNIHelp.c \
	android_display2.c \
	msjava.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(FFMPEG)/android/$(ARMVER)/include
LOCAL_SHARED_LIBRARIES := libffmpeg-prebuilt
LOCAL_LDLIBS    := -lm -llog -ljnigraphics
include $(BUILD_SHARED_LIBRARY)
