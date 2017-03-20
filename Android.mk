LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := OpenDelta
LOCAL_MODULE_TAGS := optional
LOCAL_PRIVILEGED_MODULE := true

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res \
    frameworks/support/v7/cardview/res

LOCAL_STATIC_JAVA_LIBRARIES += android-support-v7-cardview
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v4

LOCAL_AAPT_FLAGS := --auto-add-overlay \
    --extra-packages android.support.v7.cardview

LOCAL_JNI_SHARED_LIBRARIES := libopendelta
LOCAL_REQUIRED_MODULES := libopendelta
LOCAL_PROGUARD_FLAG_FILES := proguard-project.txt

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
