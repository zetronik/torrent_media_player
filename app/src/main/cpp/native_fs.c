// Releases disk blocks inside a file without changing its size (FALLOC_FL_PUNCH_HOLE).
// Used to free already-played parts of a torrent file while libtorrent keeps writing to it.
// Java has no API for this, hence the tiny JNI shim.

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/falloc.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_com_zetronik_torrentplayer_torrent_NativeFs_punchHole(
        JNIEnv *env, jclass clazz, jstring path, jlong offset, jlong length) {
    (void) clazz;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return -ENOMEM;
    int fd = open(cpath, O_WRONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (fd < 0) return -errno;
    // fallocate64: off_t is 32-bit on 32-bit ABIs (common on TV boxes), files here are often > 4 GB.
    int rc = fallocate64(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, (off64_t) offset, (off64_t) length);
    int result = rc == 0 ? 0 : -errno;
    close(fd);
    return result;
}
