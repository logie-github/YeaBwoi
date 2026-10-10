/* SPDX-License-Identifier: GPL-3.0-or-later */

/* com.logie.pe.hd.SevenZip's native side. */

#include <jni.h>
#include <stdlib.h>

#include "pe7z.h"

typedef struct {
    JNIEnv *env;
    jobject listener;
    jmethodID on_progress;
} Progress;

static int report(void *user, unsigned long long done, unsigned long long total) {
    Progress *p = (Progress *) user;
    jboolean stop = (*p->env)->CallBooleanMethod(p->env, p->listener, p->on_progress, (jlong) done, (jlong) total);
    if ((*p->env)->ExceptionCheck(p->env)) return 1;
    return stop ? 1 : 0;
}

/* [result, files, bytes, skipped] */
static jlongArray result_array(JNIEnv *env, int result, const Pe7zSummary *s) {
    jlong values[4] = {result, (jlong) s->files, (jlong) s->bytes, (jlong) s->skipped};
    jlongArray array = (*env)->NewLongArray(env, 4);
    if (array) (*env)->SetLongArrayRegion(env, array, 0, 4, values);
    return array;
}

static int read_parts(JNIEnv *env, jintArray fds, jlongArray sizes, Pe7zParts *parts) {
    jsize count = (*env)->GetArrayLength(env, fds);
    int *fd_copy = (int *) malloc(sizeof(int) * (count > 0 ? count : 1));
    long long *size_copy = (long long *) malloc(sizeof(long long) * (count > 0 ? count : 1));
    jint *fd_values;
    jlong *size_values;
    jsize i;
    if (!fd_copy || !size_copy) {
        free(fd_copy);
        free(size_copy);
        return 0;
    }
    fd_values = (*env)->GetIntArrayElements(env, fds, NULL);
    size_values = (*env)->GetLongArrayElements(env, sizes, NULL);
    for (i = 0; i < count; i++) {
        fd_copy[i] = fd_values[i];
        size_copy[i] = size_values[i];
    }
    (*env)->ReleaseIntArrayElements(env, fds, fd_values, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, sizes, size_values, JNI_ABORT);
    parts->count = count;
    parts->fds = fd_copy;
    parts->sizes = size_copy;
    return 1;
}

static void free_parts(Pe7zParts *parts) {
    free((void *) parts->fds);
    free((void *) parts->sizes);
}

JNIEXPORT jlongArray JNICALL
Java_com_logie_pe_hd_SevenZip_nativeInspect(JNIEnv *env, jclass clazz, jintArray fds, jlongArray sizes) {
    Pe7zParts parts;
    Pe7zSummary summary = {0, 0, 0};
    int result;
    if (!read_parts(env, fds, sizes, &parts)) return result_array(env, PE7Z_ERR_MEMORY, &summary);
    result = pe7z_inspect(&parts, &summary);
    free_parts(&parts);
    return result_array(env, result, &summary);
}

JNIEXPORT jlongArray JNICALL
Java_com_logie_pe_hd_SevenZip_nativeExtract(JNIEnv *env, jclass clazz, jintArray fds, jlongArray sizes, jstring out_dir,
                                      jobject listener) {
    Pe7zParts parts;
    Pe7zSummary summary = {0, 0, 0};
    Progress progress;
    const char *dir;
    int result;
    jclass listener_class = (*env)->GetObjectClass(env, listener);
    progress.env = env;
    progress.listener = listener;
    progress.on_progress = (*env)->GetMethodID(env, listener_class, "onProgress", "(JJ)Z");
    if (!progress.on_progress) return result_array(env, PE7Z_ERR_MEMORY, &summary);
    if (!read_parts(env, fds, sizes, &parts)) return result_array(env, PE7Z_ERR_MEMORY, &summary);
    dir = (*env)->GetStringUTFChars(env, out_dir, NULL);
    result = pe7z_extract(&parts, dir, report, &progress, &summary);
    (*env)->ReleaseStringUTFChars(env, out_dir, dir);
    free_parts(&parts);
    return result_array(env, result, &summary);
}
