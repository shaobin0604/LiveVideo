#ifndef _NATIVEHELPER_LOG_H
#define _NATIVEHELPER_LOG_H

#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG NULL
#endif

#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#define  LOGV(...)  __android_log_print(ANDROID_LOG_VERBOSE,LOG_TAG,__VA_ARGS__)
#define  LOGW(...)  __android_log_print(ANDROID_LOG_WARN,LOG_TAG,__VA_ARGS__)
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG,LOG_TAG,__VA_ARGS__)

#define MLOGI(fmt, args...)	LOGI("[%s: %d: %s] _____ " fmt "\n", __FILE__, __LINE__, __FUNCTION__, ##args)
#define MLOGE(fmt, args...)	LOGE("[%s: %d: %s] _____ " fmt "\n", __FILE__, __LINE__, __FUNCTION__, ##args)
#define MLOGV(fmt, args...)	LOGV("[%s: %d: %s] _____ " fmt "\n", __FILE__, __LINE__, __FUNCTION__, ##args)
#define MLOGW(fmt, args...)	LOGW("[%s: %d: %s] _____ " fmt "\n", __FILE__, __LINE__, __FUNCTION__, ##args)
#define MLOGD(fmt, args...)	LOGD("[%s: %d: %s] _____ " fmt "\n", __FILE__, __LINE__, __FUNCTION__, ##args)

#endif /*__NATIVEHELPER_LOG_H*/
