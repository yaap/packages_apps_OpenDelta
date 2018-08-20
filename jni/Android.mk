LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_CFLAGS += -Ofast -Wno-unused-parameter -Wno-unused-function -Wno-sign-compare
LOCAL_MODULE := libopendelta
LOCAL_SRC_FILES += xdelta3-3.0.7/xdelta3.c zipadjust.c delta.c delta_jni.c
LOCAL_LDLIBS := -lz

LOCAL_C_INCLUDES += external/zlib
LOCAL_SHARED_LIBRARIES := libz

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_CFLAGS += -Ofast -Wno-unused-parameter -Wno-unused-function -Wno-sign-compare
LOCAL_MODULE := dedelta
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES += xdelta3-3.0.7/xdelta3.c delta.c delta_run.c
LOCAL_LDLIBS := -lz

LOCAL_C_INCLUDES += external/zlib
LOCAL_SHARED_LIBRARIES := libz

include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)

LOCAL_CFLAGS += -Ofast -Wno-unused-parameter -Wno-unused-function -Wno-sign-compare
LOCAL_MODULE := zipadjust
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES += zipadjust.c zipadjust_run.c
LOCAL_LDLIBS := -lz

LOCAL_C_INCLUDES += external/zlib
LOCAL_SHARED_LIBRARIES := libz

include $(BUILD_EXECUTABLE)
