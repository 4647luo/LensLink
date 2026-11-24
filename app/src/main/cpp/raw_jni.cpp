#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <libraw/libraw.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#define LOG_TAG "LianJingRaw"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

void clearPendingException(JNIEnv* env, const char* where) {
    if (env->ExceptionCheck()) {
        LOGE("%s: Java exception pending", where);
        env->ExceptionClear();
    }
}

/**
 * 在 native 里直接创建 ARGB_8888 的软件位图。
 *
 * 不走 HardwareBuffer，也不依赖 AndroidLibRaw 的 getMutableBitmap：
 * 那两个路径在小米 Android 15 上一个花屏、一个 copy 返回 null。
 * 这里只创建普通 Bitmap，再用 AndroidBitmap_lockPixels 写真实像素。
 */
jobject createBitmapWithConfig(JNIEnv* env, int width, int height, const char* configName) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (bitmapClass == nullptr) {
        clearPendingException(env, "FindClass Bitmap");
        return nullptr;
    }
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (configClass == nullptr) {
        clearPendingException(env, "FindClass Bitmap.Config");
        env->DeleteLocalRef(bitmapClass);
        return nullptr;
    }
    jfieldID configField = env->GetStaticFieldID(
            configClass, configName, "Landroid/graphics/Bitmap$Config;");
    if (configField == nullptr) {
        clearPendingException(env, "GetStaticFieldID Bitmap.Config");
        env->DeleteLocalRef(configClass);
        env->DeleteLocalRef(bitmapClass);
        return nullptr;
    }
    jobject config = env->GetStaticObjectField(configClass, configField);
    if (config == nullptr) {
        clearPendingException(env, "GetStaticObjectField Bitmap.Config");
        env->DeleteLocalRef(configClass);
        env->DeleteLocalRef(bitmapClass);
        return nullptr;
    }
    jmethodID createBitmap = env->GetStaticMethodID(
            bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (createBitmap == nullptr) {
        clearPendingException(env, "GetStaticMethodID createBitmap");
        env->DeleteLocalRef(config);
        env->DeleteLocalRef(configClass);
        env->DeleteLocalRef(bitmapClass);
        return nullptr;
    }
    jobject bitmap = env->CallStaticObjectMethod(
            bitmapClass, createBitmap, width, height, config);
    if (env->ExceptionCheck()) {
        clearPendingException(env, "Bitmap.createBitmap");
        bitmap = nullptr;
    }
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);
    return bitmap;
}

/**
 * 在 native 里直接创建 ARGB_8888 的软件位图。
 *
 * 不走 HardwareBuffer，也不依赖 AndroidLibRaw 的 getMutableBitmap：
 * 那两个路径在小米 Android 15 上一个花屏、一个 copy 返回 null。
 * 这里只创建普通 Bitmap，再用 AndroidBitmap_lockPixels 写真实像素。
 */
jobject createArgbBitmap(JNIEnv* env, int width, int height) {
    return createBitmapWithConfig(env, width, height, "ARGB_8888");
}

/**
 * GainMap 用 ALPHA_8：一个字节一个像素，体积只有 ARGB 的 1/4，
 * Android 的 Gainmap 构造函数也直接吃这个格式。
 */
jobject createAlphaBitmap(JNIEnv* env, int width, int height) {
    return createBitmapWithConfig(env, width, height, "ALPHA_8");
}

inline void writeRgba(uint8_t* dst, uint8_t r, uint8_t g, uint8_t b) {
    // ANDROID_BITMAP_FORMAT_RGBA_8888 的内存字节序是 R,G,B,A。
    // 不能直接写 0xAARRGGBB 的 uint32：小端机器会变成 B,G,R,A，蓝羽毛会变橙色。
    dst[0] = r;
    dst[1] = g;
    dst[2] = b;
    dst[3] = 0xFF;
}

inline uint8_t clampByte(float value) {
    if (value <= 0.0f) return 0;
    if (value >= 255.0f) return 255;
    return static_cast<uint8_t>(value + 0.5f);
}

/**
 * 把 LibRaw 的 8bit RGB 缓冲写进 Android Bitmap。
 *
 * 不能假设 Bitmap 的 stride 等于 width * 4：Android 会按设备做行对齐。
 * 以前 AndroidLibRaw 的 getMutableBitmap 就是忽略了 stride，才会出现斜向彩条。
 */
bool fillBitmap(JNIEnv* env, jobject bitmap,
                const uint8_t* src, int srcWidth, int srcHeight, int channels,
                int targetWidth, int targetHeight) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bitmap format is not RGBA_8888: %u", info.format);
        return false;
    }
    if (info.width != static_cast<uint32_t>(targetWidth) ||
        info.height != static_cast<uint32_t>(targetHeight)) {
        LOGE("bitmap size mismatch: %ux%u vs %dx%d", info.width, info.height,
             targetWidth, targetHeight);
        return false;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        LOGE("AndroidBitmap_lockPixels failed");
        return false;
    }

    const bool sameSize = srcWidth == targetWidth && srcHeight == targetHeight;
    const float scaleX = static_cast<float>(srcWidth) / static_cast<float>(targetWidth);
    const float scaleY = static_cast<float>(srcHeight) / static_cast<float>(targetHeight);

    for (int y = 0; y < targetHeight; ++y) {
        uint8_t* dstRow = static_cast<uint8_t*>(pixels) +
                          static_cast<size_t>(y) * info.stride;
        int sy = static_cast<int>(std::floor((y + 0.5f) * scaleY - 0.5f));
        sy = std::max(0, std::min(srcHeight - 1, sy));

        if (sameSize) {
            const uint8_t* srcRow = src + static_cast<size_t>(sy) * srcWidth * channels;
            for (int x = 0; x < targetWidth; ++x) {
                const uint8_t* p = srcRow + static_cast<size_t>(x) * channels;
                writeRgba(dstRow + static_cast<size_t>(x) * 4, p[0], p[1], p[2]);
            }
            continue;
        }

        const int sy0 = sy;
        const int sy1 = std::min(srcHeight - 1, sy0 + 1);
        const float fy = std::max(0.0f, std::min(1.0f,
                (y + 0.5f) * scaleY - 0.5f - static_cast<float>(sy0)));

        for (int x = 0; x < targetWidth; ++x) {
            int sx = static_cast<int>(std::floor((x + 0.5f) * scaleX - 0.5f));
            sx = std::max(0, std::min(srcWidth - 1, sx));
            const int sx0 = sx;
            const int sx1 = std::min(srcWidth - 1, sx0 + 1);
            const float fx = std::max(0.0f, std::min(1.0f,
                    (x + 0.5f) * scaleX - 0.5f - static_cast<float>(sx0)));

            const uint8_t* p00 = src + (static_cast<size_t>(sy0) * srcWidth + sx0) * channels;
            const uint8_t* p01 = src + (static_cast<size_t>(sy0) * srcWidth + sx1) * channels;
            const uint8_t* p10 = src + (static_cast<size_t>(sy1) * srcWidth + sx0) * channels;
            const uint8_t* p11 = src + (static_cast<size_t>(sy1) * srcWidth + sx1) * channels;

            float r = (p00[0] * (1 - fx) + p01[0] * fx) * (1 - fy) +
                      (p10[0] * (1 - fx) + p11[0] * fx) * fy;
            float g = (p00[1] * (1 - fx) + p01[1] * fx) * (1 - fy) +
                      (p10[1] * (1 - fx) + p11[1] * fx) * fy;
            float b = (p00[2] * (1 - fx) + p01[2] * fx) * (1 - fy) +
                      (p10[2] * (1 - fx) + p11[2] * fx) * fy;
            writeRgba(dstRow + static_cast<size_t>(x) * 4,
                      clampByte(r), clampByte(g), clampByte(b));
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_lutcamera_lutcamerapro_ui_douk_RawDecode_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(LIBRAW_VERSION_STR);
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_lutcamera_lutcamerapro_ui_douk_RawDecode_nativeProbe(JNIEnv* env, jobject,
                                                             jstring path) {
    const std::string filePath = jstringToUtf8(env, path);
    if (filePath.empty()) return nullptr;

    LibRaw raw;
    int rc = raw.open_file(filePath.c_str());
    if (rc != LIBRAW_SUCCESS) {
        LOGW("probe open failed: %d %s file=%s", rc, libraw_strerror(rc), filePath.c_str());
        return nullptr;
    }
    jint size[2] = {
            static_cast<jint>(raw.imgdata.sizes.width),
            static_cast<jint>(raw.imgdata.sizes.height)
    };
    jintArray result = env->NewIntArray(2);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, 2, size);
    }
    raw.recycle();
    return result;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_lutcamera_lutcamerapro_ui_douk_RawDecode_nativeDecode(JNIEnv* env, jobject,
                                                               jstring path,
                                                               jint maxSide,
                                                               jboolean preview) {
    const std::string filePath = jstringToUtf8(env, path);
    if (filePath.empty()) {
        LOGE("decode: empty path");
        return nullptr;
    }

    LibRaw raw;
    // 用析构函数保证每一条 return 路径都会释放 LibRaw 的 mem image。
    struct ImageGuard {
        libraw_processed_image_t* image = nullptr;
        ~ImageGuard() {
            if (image != nullptr) LibRaw::dcraw_clear_mem(image);
        }
    } guard;
    jobject bitmap = nullptr;

    try {
        int rc = raw.open_file(filePath.c_str());
        if (rc != LIBRAW_SUCCESS) {
            LOGE("open failed: %d %s file=%s", rc, libraw_strerror(rc), filePath.c_str());
            return nullptr;
        }

        LOGI("opened %s: %dx%d, make=%s, model=%s, raw=%dx%d",
             filePath.c_str(),
             raw.imgdata.sizes.width, raw.imgdata.sizes.height,
             raw.imgdata.idata.make, raw.imgdata.idata.model,
             raw.imgdata.sizes.raw_width, raw.imgdata.sizes.raw_height);

        // 输出 8bit sRGB，和 App 原有 Bitmap/滤镜链路一致。
        raw.imgdata.params.output_bps = 8;
        raw.imgdata.params.output_color = 1;        // sRGB
        raw.imgdata.params.user_qual = 3;           // AHD
        raw.imgdata.params.use_camera_wb = 1;       // 优先相机白平衡
        raw.imgdata.params.use_auto_wb = 0;
        raw.imgdata.params.use_camera_matrix = 3;   // 始终使用相机色彩矩阵
        raw.imgdata.params.highlight = 2;           // blend 高光
        raw.imgdata.params.no_auto_bright = 0;
        raw.imgdata.params.auto_bright_thr = 0.01f;
        raw.imgdata.params.fbdd_noiserd = 1;

        const int rawLong = std::max(raw.imgdata.sizes.width, raw.imgdata.sizes.height);
        // 预览目标不超过原图一半时，让 LibRaw 直接半尺寸解压：
        // Z50II 的 HE★ 解压本身很重，半尺寸能把编辑预览的等待时间砍掉一大截。
        const bool useHalf = preview && maxSide > 0 && maxSide <= rawLong / 2 + 1;
        raw.imgdata.params.half_size = useHalf ? 1 : 0;

        // LibRaw 分两步：open_file 只读元数据，unpack 才真正解压/解 Bayer。
        // 少了 unpack 会报 -4 Out of order call of libraw function。
        rc = raw.unpack();
        if (rc != LIBRAW_SUCCESS) {
            LOGE("unpack failed: %d %s file=%s", rc, libraw_strerror(rc), filePath.c_str());
            return nullptr;
        }

        rc = raw.dcraw_process();
        if (rc != LIBRAW_SUCCESS) {
            LOGE("dcraw_process failed: %d %s file=%s", rc, libraw_strerror(rc), filePath.c_str());
            return nullptr;
        }

        int makeMemRc = LIBRAW_SUCCESS;
        guard.image = raw.dcraw_make_mem_image(&makeMemRc);
        libraw_processed_image_t* image = guard.image;
        if (image == nullptr || makeMemRc != LIBRAW_SUCCESS) {
            LOGE("dcraw_make_mem_image failed: %d %s", makeMemRc,
                 image == nullptr ? "null image" : "error");
            return nullptr;
        }
        if (image->type != LIBRAW_IMAGE_BITMAP || image->bits != 8 ||
            (image->colors != 3 && image->colors != 4)) {
            LOGE("unsupported decoded image type=%u bits=%u colors=%u",
                 image->type, image->bits, image->colors);
            return nullptr;
        }

        const int srcWidth = image->width;
        const int srcHeight = image->height;
        const int srcLong = std::max(srcWidth, srcHeight);
        int targetWidth = srcWidth;
        int targetHeight = srcHeight;
        if (maxSide > 0 && maxSide < srcLong) {
            const float ratio = static_cast<float>(maxSide) / static_cast<float>(srcLong);
            targetWidth = std::max(1, static_cast<int>(std::lround(srcWidth * ratio)));
            targetHeight = std::max(1, static_cast<int>(std::lround(srcHeight * ratio)));
        }

        // 先把 LibRaw 的 ushort 图像缓冲释放掉，再创建 Bitmap，
        // 20MP 全尺寸导出时能少占一百多 MB 峰值。
        raw.recycle();

        bitmap = createArgbBitmap(env, targetWidth, targetHeight);
        if (bitmap == nullptr) {
            LOGE("createArgbBitmap failed: %dx%d", targetWidth, targetHeight);
            return nullptr;
        }
        if (!fillBitmap(env, bitmap, image->data, srcWidth, srcHeight, image->colors,
                        targetWidth, targetHeight)) {
            env->DeleteLocalRef(bitmap);
            return nullptr;
        }

        LOGI("decoded %s: %dx%d -> %dx%d half=%d", filePath.c_str(),
             srcWidth, srcHeight, targetWidth, targetHeight, useHalf ? 1 : 0);
        return bitmap;
    } catch (const std::exception& e) {
        LOGE("decode exception: %s file=%s", e.what(), filePath.c_str());
        if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
        return nullptr;
    } catch (...) {
        LOGE("decode unknown exception file=%s", filePath.c_str());
        if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
        return nullptr;
    }
}

/**
 * 从 RAW 传感器原始像素生成 HDR GainMap。
 *
 * 这里不经过 LibRaw 的 8bit 输出：直接读 unpack 后的 CFA 原始数据，
 * 按 2x2 Bayer 块求平均亮度。这样高光是否还有细节完全取决于传感器
 * 自身的数据，而不是已经被压成 8bit 的 SDR 预览。
 *
 * 返回 ALPHA_8 增益图：0 = 不增益，255 = maxBoost。
 * 只给 RAW 用；普通 JPG/HEIC 仍走原片 GainMap 或亮度反推。
 */
extern "C"
JNIEXPORT jobject JNICALL
Java_com_lutcamera_lutcamerapro_ui_douk_RawDecode_nativeHdrGain(
        JNIEnv* env, jobject, jstring path, jint maxSide,
        jfloat maxBoost, jfloat highlightStart) {
    const std::string filePath = jstringToUtf8(env, path);
    if (filePath.empty()) return nullptr;

    LibRaw raw;
    jobject gainBitmap = nullptr;
    try {
        int rc = raw.open_file(filePath.c_str());
        if (rc != LIBRAW_SUCCESS) {
            LOGW("hdr open failed: %d %s file=%s", rc, libraw_strerror(rc), filePath.c_str());
            return nullptr;
        }
        rc = raw.unpack();
        if (rc != LIBRAW_SUCCESS) {
            LOGW("hdr unpack failed: %d %s file=%s", rc, libraw_strerror(rc), filePath.c_str());
            return nullptr;
        }

        const ushort* rawImage = raw.imgdata.rawdata.raw_image;
        if (rawImage == nullptr) {
            LOGW("hdr no raw_image file=%s", filePath.c_str());
            return nullptr;
        }
        const int rawWidth = static_cast<int>(raw.imgdata.sizes.raw_width);
        const int rawHeight = static_cast<int>(raw.imgdata.sizes.raw_height);
        int visWidth = static_cast<int>(raw.imgdata.sizes.width);
        int visHeight = static_cast<int>(raw.imgdata.sizes.height);
        const int left = static_cast<int>(raw.imgdata.sizes.left_margin);
        const int top = static_cast<int>(raw.imgdata.sizes.top_margin);
        if (visWidth <= 0) visWidth = rawWidth - left;
        if (visHeight <= 0) visHeight = rawHeight - top;
        if (rawWidth <= 1 || rawHeight <= 1 || visWidth <= 1 || visHeight <= 1) {
            return nullptr;
        }

        int targetWidth = visWidth;
        int targetHeight = visHeight;
        const int visLong = std::max(visWidth, visHeight);
        if (maxSide > 0 && maxSide < visLong) {
            const float ratio = static_cast<float>(maxSide) / static_cast<float>(visLong);
            targetWidth = std::max(1, static_cast<int>(std::lround(visWidth * ratio)));
            targetHeight = std::max(1, static_cast<int>(std::lround(visHeight * ratio)));
        }

        unsigned white = raw.imgdata.color.maximum;
        if (white == 0) white = raw.imgdata.color.data_maximum;
        if (white == 0) white = 65535;
        const float black = static_cast<float>(raw.imgdata.color.black);
        const float denom = std::max(1.0f, static_cast<float>(white) - black);
        const float boost = std::max(1.01f, static_cast<float>(maxBoost));
        const float start = std::min(0.98f, std::max(0.0f, static_cast<float>(highlightStart)));

        gainBitmap = createAlphaBitmap(env, targetWidth, targetHeight);
        if (gainBitmap == nullptr) {
            LOGE("hdr createAlphaBitmap failed: %dx%d", targetWidth, targetHeight);
            return nullptr;
        }

        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, gainBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_A_8) {
            LOGE("hdr gain bitmap format is not A_8");
            env->DeleteLocalRef(gainBitmap);
            return nullptr;
        }
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, gainBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels == nullptr) {
            LOGE("hdr AndroidBitmap_lockPixels failed");
            env->DeleteLocalRef(gainBitmap);
            return nullptr;
        }

        const float scaleX = static_cast<float>(visWidth) / static_cast<float>(targetWidth);
        const float scaleY = static_cast<float>(visHeight) / static_cast<float>(targetHeight);
        const float span = std::max(1e-4f, 1.0f - start);
        float maxLinear = 0.0f;
        int maxAlpha = 0;
        for (int y = 0; y < targetHeight; ++y) {
            uint8_t* dstRow = static_cast<uint8_t*>(pixels) +
                              static_cast<size_t>(y) * info.stride;
            int vy = static_cast<int>(std::floor((y + 0.5f) * scaleY));
            vy = std::max(0, std::min(visHeight - 1, vy));
            int ry = top + (vy & ~1);
            ry = std::max(0, std::min(rawHeight - 2, ry));
            for (int x = 0; x < targetWidth; ++x) {
                int vx = static_cast<int>(std::floor((x + 0.5f) * scaleX));
                vx = std::max(0, std::min(visWidth - 1, vx));
                int rx = left + (vx & ~1);
                rx = std::max(0, std::min(rawWidth - 2, rx));

                float sum = 0.0f;
                for (int dy = 0; dy < 2; ++dy) {
                    const ushort* row = rawImage +
                        static_cast<size_t>(ry + dy) * static_cast<size_t>(rawWidth);
                    for (int dx = 0; dx < 2; ++dx) {
                        sum += static_cast<float>(row[rx + dx]);
                    }
                }
                float linear = (sum * 0.25f - black) / denom;
                linear = std::max(0.0f, std::min(1.0f, linear));
                if (linear > maxLinear) maxLinear = linear;
                // 只抬真正的高光区：start 以下不动，越接近传感器饱和越接近 maxBoost。
                float t = (linear - start) / span;
                t = std::max(0.0f, std::min(1.0f, t));
                t = t * t * (3.0f - 2.0f * t);
                const float ratio = 1.0f + (boost - 1.0f) * t;
                const uint8_t a = static_cast<uint8_t>(std::lround(
                    (ratio - 1.0f) / (boost - 1.0f) * 255.0f));
                if (a > maxAlpha) maxAlpha = a;
                dstRow[x] = a;
            }
        }
        AndroidBitmap_unlockPixels(env, gainBitmap);
        raw.recycle();
        LOGI("hdr gain %s: vis=%dx%d target=%dx%d boost=%.2f start=%.2f white=%u black=%.0f linMax=%.3f alphaMax=%d",
             filePath.c_str(), visWidth, visHeight, targetWidth, targetHeight,
             boost, start, white, black, maxLinear, maxAlpha);
        return gainBitmap;
    } catch (const std::exception& e) {
        LOGE("hdr gain exception: %s file=%s", e.what(), filePath.c_str());
        if (gainBitmap != nullptr) env->DeleteLocalRef(gainBitmap);
        return nullptr;
    } catch (...) {
        LOGE("hdr gain unknown exception file=%s", filePath.c_str());
        if (gainBitmap != nullptr) env->DeleteLocalRef(gainBitmap);
        return nullptr;
    }
}

