/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#define LOG_TAG "LiveVideo"
#include <jni.h>
#include <time.h>
#include <android/bitmap.h>
#include <dlfcn.h>
#include <assert.h>
#include <pthread.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/intreadwrite.h>

#include "JNIHelp.h"
#include "Log.h"
#include "msjava.h"

#define USE_DLOPEN 0

#define BUFFER_PACKET 1
#define BUFFER_PACKET_SIZE 20

#define FPS_CTRL 1
#define FPS 20

#define DUMP_RTP_RAW 0
#define DUMP_FRAME_RAW 0

/* Set to 1 to enable debug log traces. */
#define DEBUG 0


typedef uint8_t bool_t;
#undef TRUE
#undef FALSE
#define TRUE 1
#define FALSE 0

typedef struct MSPicture {
    int w;
    int h;
    uint8_t* planes[4];
    int strides[4];
} MSPicture;

typedef struct MSVideoSize {
    int width,height;
} MSVideoSize;

typedef struct MSRect {
    int x,y,w,h;
} MSRect;

typedef struct PacketQueue {
    AVPacketList *first_node, *last_node;
    int nb_packets;
    int size;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int init;
} PacketQueue;

typedef struct AndroidDisplay {
    jobject android_video_window;
    jobject jbitmap;
    jmethodID get_bitmap_id;
    jmethodID update_id;
    jmethodID request_orientation_id;
    AndroidBitmapInfo bmpinfo;
    bool_t orientation_change_pending;
    struct SwsContext *sws;
    
    PacketQueue queue;
    int quit;
    pthread_mutex_t mutex;
} AndroidDisplay;

static AndroidDisplay display;
static const char* const kClassPathName = "cn/yo2/aquarium/livevideo/AndroidVideoWindowImpl";

#if USE_DLOPEN
static int (*sym_AndroidBitmap_getInfo)(JNIEnv *env,jobject bitmap, AndroidBitmapInfo *bmpinfo) = NULL;
static int (*sym_AndroidBitmap_lockPixels)(JNIEnv *env, jobject bitmap, void **pixels) = NULL;
static int (*sym_AndroidBitmap_unlockPixels)(JNIEnv *env, jobject bitmap) = NULL;
#endif

static FILE *raw_h263_record_file = NULL;
static int raw_h263_record_file_opened = 0;

static void packet_queue_init(PacketQueue *q) {
    memset(q, 0, sizeof(PacketQueue));
    pthread_mutex_init(&q->mutex, NULL);
    pthread_cond_init(&q->cond, NULL);
    q->init = 1;
}

static int packet_queue_put(PacketQueue *q, AVPacketList *node) {
    if (!q->init) {
        LOGE("Packet not init, drop node");
        return -1;
    }
    
    LOGD("packet_queue_put, packets = %d", q->nb_packets);
    
    if (!node) {
        LOGE("node cannot be NULL");
        return -1;
    }
    
    pthread_mutex_lock(&q->mutex);
    {
        if (!q->last_node)
            q->first_node = node;
        else
            q->last_node->next = node;
        q->last_node = node;
        q->nb_packets++;
        q->size += node->pkt.size;
    }
    
    pthread_cond_signal(&q->cond);
    
    pthread_mutex_unlock(&q->mutex);
    
    return 0;
} 
/*
static int packet_queue_put(PacketQueue *q, AVPacket *pkt) {

    AVPacketList *pkt1;
    if(av_dup_packet(pkt) < 0) {
        return -1;
    }
    pkt1 = av_malloc(sizeof(AVPacketList));
    if (!pkt1)
        return -1;
    pkt1->pkt = *pkt;
    pkt1->next = NULL;


    pthread_mutex_lock(&q->mutex);
    {
        if (!q->last_pkt)
            q->first_pkt = pkt1;
        else
            q->last_pkt->next = pkt1;
        q->last_pkt = pkt1;
        q->nb_packets++;
        q->size += pkt1->pkt.size;
    }
    pthread_mutex_unlock(&q->mutex);
    
    pthread_cond_signal(&q->cond);
    return 0;
}
*/

static void packet_queue_flush(PacketQueue *q) {
    if (!q->init) {
        LOGE("Packet not init");
        return;
    }
    
    LOGD("packet_queue_flush, packets = %d", q->nb_packets);
    
    AVPacketList *node, *node1;

    pthread_mutex_lock(&q->mutex);
    for(node = q->first_node; node != NULL; node = node1) {
        node1 = node->next;
        av_free_packet(&node->pkt);
        av_freep(&node);
    }
    q->last_node = NULL;
    q->first_node = NULL;
    q->nb_packets = 0;
    q->size = 0;
    pthread_mutex_unlock(&q->mutex);
}

static AVPacketList* packet_queue_get(PacketQueue *q, int block)
{
    if (!q->init) {
        LOGE("Packet not init");
        return NULL;
    }
    
    LOGD("packet_queue_get, packets = %d", q->nb_packets);
    AVPacketList *first_node = NULL;
    
    pthread_mutex_lock(&q->mutex);

    for(;;) {

        if(display.quit) {
            first_node = NULL;
            break;
        }

        first_node = q->first_node;
        if (first_node) {
            q->first_node = first_node->next;
            first_node->next = NULL;
            if (!q->first_node) {
                q->last_node = NULL;
            }
            q->nb_packets--;
            q->size -= first_node->pkt.size;
            
            break;
        } else if (!block) {
            break;
        } else {
            pthread_cond_wait(&q->cond, &q->mutex);
        }
    }
    pthread_mutex_unlock(&q->mutex);
    
    return first_node;
}
/*
static int packet_queue_get(PacketQueue *q, AVPacket *pkt, int block)
{
    AVPacketList *pkt1;
    int ret;

    pthread_mutex_lock(&q->mutex);

    for(;;) {

        if(display.quit) {
            ret = -1;
            break;
        }

        pkt1 = q->first_pkt;
        if (pkt1) {
            q->first_pkt = pkt1->next;
            if (!q->first_pkt)
                q->last_pkt = NULL;
            q->nb_packets--;
            q->size -= pkt1->pkt.size;
            *pkt = pkt1->pkt;
            av_free(pkt1);
            ret = 1;
            break;
        } else if (!block) {
            ret = 0;
            break;
        } else {
            pthread_cond_wait(&q->cond, &q->mutex);
        }
    }
    pthread_mutex_unlock(&q->mutex);
    return ret;
}
*/

/* Return current time in milliseconds */
static uint64_t now_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return tv.tv_sec*1000 + tv.tv_usec/1000;
}

static int android_display_init(JNIEnv *jenv) {
    jclass wc;

    wc = (*jenv)->FindClass(jenv, kClassPathName);
    if (wc == 0) {
        LOGE("Could not find class %s!", kClassPathName);
        return -1;
    }
    display.get_bitmap_id = (*jenv)->GetMethodID(jenv, wc, "getBitmap", "()Landroid/graphics/Bitmap;");
    display.update_id = (*jenv)->GetMethodID(jenv, wc, "update", "()V");
    display.request_orientation_id = (*jenv)->GetMethodID(jenv, wc, "requestOrientation", "(I)V");
    pthread_mutex_init(&display.mutex, NULL);

    return 0;
}

static int android_display_uninit(JNIEnv *jenv) {

}

static void android_display_process(JNIEnv* jenv, AVFrame *pFrame) {
    LOGD("android_display_process ----->");
    void *pixels = NULL;

    MSPicture dest = {0};
    
    if (display.quit) {
        LOGW("quit flag set");
        return;
    }
    
    if (!display.jbitmap) {
        LOGW("No Bitmap to render!");
        return;
    }

    dest.w = display.bmpinfo.width;
    dest.h = display.bmpinfo.height;

    AVCodecContext *pCodecCtx = pFrame->owner;

    if (!display.sws) {
        display.sws = sws_getContext(pCodecCtx->width, pCodecCtx->height, pCodecCtx->pix_fmt,
                                     dest.w, dest.h, PIX_FMT_RGB565,
                                     SWS_FAST_BILINEAR, NULL, NULL, NULL);// other codes

        if (!display.sws) {
            LOGE("Cannot create display.sws");
            return;
        }
    }
#if USE_DLOPEN
    if (sym_AndroidBitmap_lockPixels(jenv,display.jbitmap, &pixels) == 0) {
#else
    if (AndroidBitmap_lockPixels(jenv,display.jbitmap, &pixels) == 0) {
#endif
        if (pixels) {
            dest.planes[0] = (uint8_t*)pixels;
            dest.strides[0] = display.bmpinfo.stride;
            
            sws_scale(display.sws, (const uint8_t* const)pFrame->data, pFrame->linesize, 0, pCodecCtx->height, dest.planes, dest.strides);
#if USE_DLOPEN            
            sym_AndroidBitmap_unlockPixels(jenv, display.jbitmap);
#else
            AndroidBitmap_unlockPixels(jenv, display.jbitmap);
#endif        
            (*jenv)->CallVoidMethod(jenv, display.android_video_window, display.update_id);
        } else {
            LOGW("Pixels==NULL in android bitmap !");
        }
    } else {
        LOGE("AndroidBitmap_lockPixels() failed !");
    }
}


static int android_display_set_window(JNIEnv *jenv, jobject thiz, jobject weak_thiz) {
    LOGD("android_display_set_window -->");
    pthread_mutex_lock(&display.mutex);
    
    int err;
    
    
    if (weak_thiz != NULL) {
        LOGD("android_display_set_window --> new GlobalRef");
        display.android_video_window = (*jenv)->NewGlobalRef(jenv, weak_thiz);
        
        jobject bitmap = (*jenv)->CallObjectMethod(jenv, thiz, display.get_bitmap_id);
        display.jbitmap = (*jenv)->NewGlobalRef(jenv, bitmap);
    } else {
        LOGD("android_display_set_window --> clear GlobalRef");
        
        (*jenv)->DeleteGlobalRef(jenv, display.android_video_window);
        display.android_video_window = NULL;
        
        (*jenv)->DeleteGlobalRef(jenv, display.jbitmap);
        display.jbitmap = NULL;
        display.quit = 1;
    }

    if (display.jbitmap != NULL) {
#if USE_DLOPEN
        err = sym_AndroidBitmap_getInfo(jenv, display.jbitmap, &display.bmpinfo);
#else
        err = AndroidBitmap_getInfo(jenv, display.jbitmap, &display.bmpinfo);
#endif
        if (err != 0) {
            LOGE("AndroidBitmap_getInfo() failed.");
            display.jbitmap = NULL;

            pthread_mutex_unlock(&display.mutex);
            return -1;
        }
    }

    if (display.sws) {
        LOGD("android_display_set_window --> clear display.sws");
        sws_freeContext(display.sws);
        display.sws = NULL;
    }

    display.orientation_change_pending = FALSE;

    if (display.jbitmap != NULL)
        LOGD("New java bitmap given with w=%i,h=%i,stride=%i,format=%i",
             display.bmpinfo.width,display.bmpinfo.height,display.bmpinfo.stride,display.bmpinfo.format);
    
    
    pthread_mutex_unlock(&display.mutex);
    
    return 0;

}

#if USE_DLOPEN
bool_t libmsandroiddisplay_init(void) {
    /*See if we can use AndroidBitmap_* symbols (only since android 2.2 normally)*/
    void *handle = NULL;
    handle = dlopen("libjnigraphics.so", RTLD_LAZY);
    if (handle != NULL) {
        sym_AndroidBitmap_getInfo = dlsym(handle, "AndroidBitmap_getInfo");
        sym_AndroidBitmap_lockPixels = dlsym(handle, "AndroidBitmap_lockPixels");
        sym_AndroidBitmap_unlockPixels = dlsym(handle, "AndroidBitmap_unlockPixels");

        if (sym_AndroidBitmap_getInfo == NULL || sym_AndroidBitmap_lockPixels == NULL
                || sym_AndroidBitmap_unlockPixels == NULL) {
            LOGE("AndroidBitmap not available.");
        } else {
            LOGI("MSAndroidDisplay registered.");
            return TRUE;
        }
    } else {
        LOGE("libjnigraphics.so cannot be loaded.");
    }
    return FALSE;
}
#endif

static void* play_thread_func(void* arg) {
    LOGI("play_thread_func -----> enter");
    
    int             i;
    AVCodecContext  *pCodecCtx;
    AVCodec         *pCodec;
    AVFrame         *pFrame;
    int             frameFinished;
    int             len;
    
    display.quit = 0;
    packet_queue_init(&display.queue);

    uint64_t start_time = 0;
    uint64_t end_time = 0;
    
    uint64_t frame_start_time = 0;
    uint64_t frame_end_time = 0;
    int last_frame_finished = 1;
    uint64_t frame_cost_time = 0;
    
    avcodec_init();

    // Register all formats and codecs
    avcodec_register_all();

    // Find the decoder for the video stream
    pCodec = avcodec_find_decoder(CODEC_ID_H263);
    
    if(!pCodec) {
        LOGE("Unsupported codec!");
        goto end; // Codec not found
    }
    
    pCodecCtx = avcodec_alloc_context();
    if (!pCodecCtx) {
        LOGE("Cannot alloc codec context");
        goto end;
    }
    
#if 1    
    if(pCodec->capabilities & CODEC_CAP_TRUNCATED) {
        LOGV("add CODEC_CAP_TRUNCATED flags");
        pCodecCtx->flags |= CODEC_FLAG_TRUNCATED; /* we do not send complete frames */
    } else {
        LOGV("clear CODEC_CAP_TRUNCATED");
    }
#endif
    
    pCodecCtx->codec_id = CODEC_ID_H263;

    /* without setting the following params, can still decode */    
    
    pCodecCtx->width = 176;
    pCodecCtx->height = 144;
    
//    pCodecCtx->time_base.num = 1; //这两行：一秒钟20帧
//    pCodecCtx->time_base.den = 20;

//    pCodecCtx->bit_rate = 0; //初始化为0
//    pCodecCtx->frame_number = 1; //每包一个视频帧
    pCodecCtx->codec_type = AVMEDIA_TYPE_VIDEO;
    pCodecCtx->pix_fmt = PIX_FMT_YUV420P;
    
    // Open codec
    if(avcodec_open(pCodecCtx, pCodec) < 0) {
        LOGE("Could not open video codec");
        goto close_codec_ctx; // Could not open codec
    }

    // Allocate video frame
    pFrame = avcodec_alloc_frame();

    // Read frames and save first five frames to disk
    i = 0;

    LOGD("setup done, ready to read frame");

    JavaVM *jvm = ms_get_jvm();
    
    if (jvm == NULL) {
        LOGE("Cannot find JavaVM, abort");
        goto close_avframe;
    }
    
    JNIEnv *jenv = NULL;
    
    if ((*jvm)->AttachCurrentThread(jvm, &jenv,NULL) != 0){
        LOGE("AttachCurrentThread() failed !");
        return;
    } else {
        LOGI("AttachCurrentThread() success !");
    }

#if BUFFER_PACKET
    pthread_mutex_lock(&display.queue.mutex);
    
    // wait until got enougth packets
    while (display.queue.nb_packets < 20) {
        LOGI("wait until get 20 packets...");
        pthread_cond_wait(&display.queue.cond, &display.queue.mutex);
    }
    
    pthread_mutex_unlock(&display.queue.mutex);
#endif

    LOGI("Got 20 packets, start decode.");

    AVPacketList *node;
    
    uint8_t* org_data;
    int org_size;
    for (;;) {
        node = packet_queue_get(&display.queue, 1);
        
        if (node == NULL) {
            LOGE("Cannot get packet, quit loop...");
            break;
        }
        
        org_data = node->pkt.data;
        org_size = node->pkt.size;
        
#if DUMP_FRAME_RAW
        if (!raw_h263_record_file_opened) {
            LOGD("Open /sdcard/raw_c.h263 to write raw h263");
            raw_h263_record_file = fopen("/sdcard/raw_c.h263", "w");
            if (raw_h263_record_file != NULL) {
                raw_h263_record_file_opened = 1;
            }
        }
        fwrite(org_data, sizeof(uint8_t), org_size, raw_h263_record_file);
#endif
        
        while (node->pkt.size > 0) {
            //LOGD("packet_queue_get");
            start_time = now_ms();
            
#if FPS_CTRL        
            if (last_frame_finished) {
                frame_start_time = start_time;
            }
#endif        
            len = avcodec_decode_video2(pCodecCtx, pFrame, &frameFinished, &node->pkt);
            end_time = now_ms();
            //LOGI("decode frame cost time = %llu", end_time - start_time);
            
            LOGI("packet size = %d, decode len = %d", node->pkt.size, len);
            
            if (len < 0) {
                LOGE("avcodec_decode_video2 error, drop packet");
                break;
            }
            
            // Did we get a video frame?
            if(frameFinished) {
                LOGI("decode frame success!");
                    
                start_time = now_ms();
                
                pthread_mutex_lock(&display.mutex);
                android_display_process(jenv, pFrame);
                pthread_mutex_unlock(&display.mutex);
                
                end_time = now_ms();
                
                //LOGI("render frame cost time = %llu", end_time - start_time);
            } else {
                LOGI("decode need next frame...");
            }
            
#if FPS_CTRL        
            if (frameFinished) {
                frame_end_time = now_ms();
                
                frame_cost_time = frame_end_time - frame_start_time;
                
                LOGI("frame cost time = %llu ms", frame_cost_time);
                
                if(frame_cost_time < 1000 / FPS) {
                    usleep((1000/FPS - frame_cost_time) * 1000);
                }
            }
            last_frame_finished = frameFinished;
#endif
            
            node->pkt.size -= len;
            node->pkt.data += len;
        }
        
        node->pkt.size = org_size;
        node->pkt.data = org_data;
        
        // Free the packet that was allocated by av_read_frame
        //LOGD("av_free_packet");
        av_free_packet(&node->pkt);
        
        //LOGD("av_free node");
        av_free(node);
    }
    
    packet_queue_flush(&display.queue);

detach_jvm:
    // Detach current thread from JVM
    LOGI("detaching jvm from current thread");
    (*jvm)->DetachCurrentThread(jvm);

close_sws:
    // Free the Sw context
    sws_freeContext(display.sws);

close_avframe:
    // Free the YUV frame
    av_free(pFrame);

close_codec_ctx:
    // Close the codec_ctx
    avcodec_close(pCodecCtx);
end:
    return NULL;
}

static void start_play_thread(JNIEnv *jenv, jobject thiz) {
    LOGI("start_play_thread ----->");
    
    pthread_mutex_lock(&display.mutex);
    
    pthread_t mThread;
    pthread_create(&mThread, NULL, play_thread_func, (void *)NULL);
    
    pthread_mutex_unlock(&display.mutex);
}

static void stop_play_thread(JNIEnv *jenv, jobject thiz) {
    LOGI("stop_play_thread ----->");
    
    pthread_mutex_lock(&display.mutex);
    
    if (display.quit) {
        pthread_mutex_unlock(&display.mutex);
        return;
    }
    
    display.quit = 1;
    
    LOGD("signal play thread");
    pthread_cond_signal(&display.queue.cond);
    
    pthread_mutex_unlock(&display.mutex);
}

/**
 * Write h263 raw frame to buffer. This method is called in RTP video receiver thread.
 * 
 */
static int write_h263_frame(JNIEnv *jenv, jobject thiz, jbyteArray packet, jint len) {
    if ((*jenv)->GetArrayLength(jenv, packet) < len) {
        LOGE("IllegalArgument len too large");
        return -1;
    }
    
    AVPacketList *pkt_node = av_malloc(sizeof(AVPacketList));
    av_new_packet(&pkt_node->pkt, len);
    pkt_node->next = NULL;
    
    (*jenv)->GetByteArrayRegion(jenv, packet, 0, len, pkt_node->pkt.data);
    
    // Producer: put the h263 frame to queue, the memory is allocated on heap, the frame consumer should free the memory 
    return packet_queue_put(&display.queue, pkt_node);
}



static int h263_handle_packet(AVPacket * pkt,
                              const uint8_t * buf,
                              int len)
{
    uint8_t *ptr;
    uint16_t header;
    int startcode, vrc, picture_header;

    if (len < 2) {
        LOGE("Too short H.263 RTP packet\n");
        return -1;
    }

    /* Decode the 16 bit H.263+ payload header, as described in section
     * 5.1 of RFC 4629. The fields of this header are:
     * - 5 reserved bits, should be ignored.
     * - One bit (P, startcode), indicating a picture start, picture segment
     *   start or video sequence end. If set, two zero bytes should be
     *   prepended to the payload.
     * - One bit (V, vrc), indicating the presence of an 8 bit Video
     *   Redundancy Coding field after this 16 bit header.
     * - 6 bits (PLEN, picture_header), the length (in bytes) of an extra
     *   picture header, following the VRC field.
     * - 3 bits (PEBIT), the number of bits to ignore of the last byte
     *   of the extra picture header. (Not used at the moment.)
     */
    header = AV_RB16(buf);
    startcode      = (header & 0x0400) >> 9;
    vrc            =  header & 0x0200;
    picture_header = (header & 0x01f8) >> 3;
    buf += 2;
    len -= 2;

    if (vrc) {
        /* Skip VRC header if present, not used at the moment. */
        buf += 1;
        len -= 1;
    }
    if (picture_header) {
        /* Skip extra picture header if present, not used at the moment. */
        buf += picture_header;
        len -= picture_header;
    }

    if (len < 0) {
        LOGE("Too short H.263 RTP packet\n");
        return -1;
    }

    if (av_new_packet(pkt, len + startcode)) {
        LOGE("Out of memory\n");
        return -1;
    }
    ptr = pkt->data;

    if (startcode) {
        *ptr++ = 0;
        *ptr++ = 0;
    }
    memcpy(ptr, buf, len);
    
    return 0;
}

static int write_rtp_packet(JNIEnv *jenv, jobject thiz, jbyteArray packet, jint offset, jint len, jint marker) {
    LOGV("write_rtp_packet");
    static int last_marker = 1;
    int ret;
    
    if ((*jenv)->GetArrayLength(jenv, packet) < len) {
        LOGE("IllegalArgument len too large");
        return -1;
    }
    
    AVPacketList *pkt_node = av_malloc(sizeof(AVPacketList));
    if (pkt_node == NULL) {
        LOGE("Cannot av_malloc AVPacketList.");
        return -1;
    }
    pkt_node->next = NULL;
    
    uint8_t* data = av_malloc(len);
    
    if (data == NULL) {
        LOGE("Cannot av_malloc rtp payload data.");
        av_free(pkt_node);
        return -1;
    }
    
    (*jenv)->GetByteArrayRegion(jenv, packet, offset, len, data);
    
    ret = h263_handle_packet(&pkt_node->pkt, data, len);
    
    if (ret < 0) {
        LOGE("h263_handle_packet error.");
        av_free(data);
        av_free(pkt_node);
        return -1;
    } 
    
#if DUMP_RTP_RAW

    if (!raw_h263_record_file_opened) {
        LOGD("Open /sdcard/raw_h263_record_file to write raw h263");
        raw_h263_record_file = fopen("/sdcard/raw_h263_record_file", "a");
        if (raw_h263_record_file != NULL) {
            raw_h263_record_file_opened = 1;
        }
    }
    
    fwrite(pkt_node->pkt.data, sizeof(uint8_t), pkt_node->pkt.size, raw_h263_record_file);
#endif
    
    // Producer: put the h263 frame to queue, the memory is allocated on heap, the frame consumer should free the memory 
    ret = packet_queue_put(&display.queue, pkt_node);
    
    if (ret < 0) {
        LOGE("Cannot packet_queue_put.");
        av_free_packet(&pkt_node->pkt);
        av_free(pkt_node);
    }
    
    av_free(data);
    
    return ret;
}

static JNINativeMethod gMethods[] = {
    {"writeRtpPacket",      "([BII)I",                       (void *)write_rtp_packet},
    {"writeH263Frame",      "([BI)I",                       (void *)write_h263_frame},
    {"startPlayThread",     "()V",                          (void *)start_play_thread},
    {"stopPlayThread",      "()V",                          (void *)stop_play_thread},
    {"setViewWindowId",     "(Ljava/lang/Object;)V",        (void *)android_display_set_window},
};

int register_android_media_FFMpegPlayerAndroid(JNIEnv *jenv) {
    return jniRegisterNativeMethods(jenv, kClassPathName, gMethods, sizeof(gMethods) / sizeof(gMethods[0]));
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;

    LOGI("Enter JNI_OnLoad.....");

    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4) != JNI_OK)
    {
        LOGE("ERROR: GetEnv failed");
        return -1;
    }

    assert(env != NULL);

    ms_set_jvm(vm);
    
    LOGD("Init AndroidDisplay");
    memset(&display, 0, sizeof(AndroidDisplay));

#if USE_DLOPEN
    if (libmsandroiddisplay_init() == FALSE) {
        LOGE("Error: libmsandroiddisplay_init fail!");
        return -1;
    }
#endif

    if (android_display_init(env) < 0) {
        LOGE("Error: android_display_init fail!");
        return -1;
    }

    if (register_android_media_FFMpegPlayerAndroid(env) < 0) {
        LOGE("Error: register_android_media_FFMpegPlayerAndroid fail!");
        return -1;
    }

    return JNI_VERSION_1_4;
}

