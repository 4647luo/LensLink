// Ultra HDR 编码桥：用 Google libultrahdr 真正写 ISO 21496-1 容器。
//
// 输入：Kotlin 侧压缩好的 SDR 底图 JPEG（原始字节，不再二次压缩）+ 8bit 单通道增益图字节；
// 输出：标准 Ultra HDR JPEG（XMP + MPF + gain map JPEG 都由 libultrahdr 生成），
//       不再依赖 Bitmap.setGainmap 那条手写容器路径。
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <turbojpeg.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "ultrahdr_api.h"

#define LOG_TAG "UltraHdrJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool writeFile(const std::string& path, const void* data, size_t size) {
  FILE* f = fopen(path.c_str(), "wb");
  if (f == nullptr) return false;
  bool ok = fwrite(data, 1, size, f) == size;
  fclose(f);
  return ok;
}

// 把 8bit 单通道增益图压成灰度 JPEG（libultrahdr 在 API-4 里要求增益图是已编码的 JPEG）。
static bool encodeGainJpeg(const unsigned char* gray, int w, int h, int quality,
                           std::vector<unsigned char>& out) {
  tjhandle jpeg = tjInitCompress();
  if (jpeg == nullptr) {
    LOGE("tjInitCompress failed: %s", tjGetErrorStr());
    return false;
  }
  unsigned char* buf = nullptr;
  unsigned long size = 0;
  int q = quality < 1 ? 1 : (quality > 100 ? 100 : quality);
  int ok = tjCompress2(jpeg, gray, w, 0, h, TJPF_GRAY, &buf, &size, TJSAMP_GRAY, q,
                       TJFLAG_ACCURATEDCT);
  if (ok != 0) {
    LOGE("tjCompress2 failed: %s", tjGetErrorStr());
    tjDestroy(jpeg);
    return false;
  }
  out.assign(buf, buf + size);
  tjFree(buf);
  tjDestroy(jpeg);
  return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lutcamera_lutcamerapro_ui_douk_UltraHdrCodec_nativeEncode(
    JNIEnv* env, jclass /*clazz*/, jbyteArray baseJpeg, jbyteArray gainGray, jint gw, jint gh,
    jfloat minBoost, jfloat maxBoost, jfloat gamma, jfloat epsSdr, jfloat epsHdr, jfloat capMin,
    jfloat capMax, jint gainQuality, jstring outPath) {
  if (baseJpeg == nullptr || gainGray == nullptr || outPath == nullptr) return JNI_FALSE;
  if (gw <= 0 || gh <= 0) return JNI_FALSE;

  jbyte* baseBytes = env->GetByteArrayElements(baseJpeg, nullptr);
  jbyte* gainBytes = env->GetByteArrayElements(gainGray, nullptr);
  if (baseBytes == nullptr || gainBytes == nullptr) {
    if (baseBytes) env->ReleaseByteArrayElements(baseJpeg, baseBytes, JNI_ABORT);
    if (gainBytes) env->ReleaseByteArrayElements(gainGray, gainBytes, JNI_ABORT);
    return JNI_FALSE;
  }
  jsize baseSize = env->GetArrayLength(baseJpeg);
  jsize gainSize = env->GetArrayLength(gainGray);
  std::vector<unsigned char> gainGrayCopy(gainBytes, gainBytes + gainSize);
  env->ReleaseByteArrayElements(gainGray, gainBytes, JNI_ABORT);

  std::vector<unsigned char> gainJpeg;
  bool ok = encodeGainJpeg(gainGrayCopy.data(), gw, gh, gainQuality, gainJpeg);
  if (!ok) {
    env->ReleaseByteArrayElements(baseJpeg, baseBytes, JNI_ABORT);
    return JNI_FALSE;
  }

  uhdr_compressed_image_t baseImg{};
  baseImg.data = baseBytes;
  baseImg.data_sz = (size_t)baseSize;
  baseImg.capacity = (size_t)baseSize;
  baseImg.cg = UHDR_CG_BT_709;
  baseImg.ct = UHDR_CT_SRGB;
  baseImg.range = UHDR_CR_FULL_RANGE;

  uhdr_compressed_image_t gainImg{};
  gainImg.data = gainJpeg.data();
  gainImg.data_sz = gainJpeg.size();
  gainImg.capacity = gainJpeg.size();
  gainImg.cg = UHDR_CG_BT_709;
  gainImg.ct = UHDR_CT_SRGB;
  gainImg.range = UHDR_CR_FULL_RANGE;

  uhdr_gainmap_metadata_t meta{};
  float maxB = maxBoost < 1.0009765625f ? 1.0009765625f : maxBoost;
  float minB = minBoost < 0.5f ? 0.5f : (minBoost > maxB ? maxB : minBoost);
  for (int i = 0; i < 3; i++) {
    meta.max_content_boost[i] = maxB;
    meta.min_content_boost[i] = minB;
    meta.gamma[i] = gamma > 0.02f ? gamma : 1.f;
    meta.offset_sdr[i] = epsSdr;
    meta.offset_hdr[i] = epsHdr;
  }
  meta.hdr_capacity_min = capMin < minB ? minB : capMin;
  meta.hdr_capacity_max = capMax > maxB ? capMax : maxB;
  meta.use_base_cg = 1;

  uhdr_codec_private_t* enc = uhdr_create_encoder();
  if (enc == nullptr) {
    env->ReleaseByteArrayElements(baseJpeg, baseBytes, JNI_ABORT);
    LOGE("uhdr_create_encoder failed");
    return JNI_FALSE;
  }

  bool success = false;
  do {
    auto st = uhdr_enc_set_compressed_image(enc, &baseImg, UHDR_BASE_IMG);
    if (st.error_code != UHDR_CODEC_OK) { LOGE("set base: %s", st.detail); break; }
    st = uhdr_enc_set_gainmap_image(enc, &gainImg, &meta);
    if (st.error_code != UHDR_CODEC_OK) { LOGE("set gainmap: %s", st.detail); break; }
    st = uhdr_enc_set_quality(enc, 90, UHDR_GAIN_MAP_IMG);
    if (st.error_code != UHDR_CODEC_OK) { LOGE("set quality: %s", st.detail); break; }
    st = uhdr_encode(enc);
    if (st.error_code != UHDR_CODEC_OK) { LOGE("encode: %s", st.detail); break; }
    uhdr_compressed_image_t* stream = uhdr_get_encoded_stream(enc);
    if (stream == nullptr || stream->data == nullptr || stream->data_sz == 0) {
      LOGE("empty stream");
      break;
    }
    const char* pathChars = env->GetStringUTFChars(outPath, nullptr);
    if (pathChars == nullptr) break;
    success = writeFile(std::string(pathChars), stream->data, stream->data_sz);
    env->ReleaseStringUTFChars(outPath, pathChars);
    if (success) {
      LOGI("encoded ultra hdr: base=%d gain=%dx%d max=%.3f out=%zu", (int)baseSize, gw, gh, maxB,
           stream->data_sz);
    }
  } while (false);

  uhdr_release_encoder(enc);
  env->ReleaseByteArrayElements(baseJpeg, baseBytes, JNI_ABORT);
  return success ? JNI_TRUE : JNI_FALSE;
}

// 解码自检：确认文件确实带 gain map（导出后调用，出问题能第一时间定位）。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_lutcamera_lutcamerapro_ui_douk_UltraHdrCodec_nativeProbe(JNIEnv* env, jclass /*clazz*/,
                                                                jstring path) {
  const char* chars = env->GetStringUTFChars(path, nullptr);
  if (chars == nullptr) return JNI_FALSE;
  bool ok = false;
  FILE* f = fopen(chars, "rb");
  if (f != nullptr) {
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (n > 0) {
      std::vector<unsigned char> bytes((size_t)n);
      if (fread(bytes.data(), 1, bytes.size(), f) == bytes.size()) {
        ok = is_uhdr_image(bytes.data(), (int)bytes.size()) != 0;
      }
    }
    fclose(f);
  }
  env->ReleaseStringUTFChars(path, chars);
  return ok ? JNI_TRUE : JNI_FALSE;
}
