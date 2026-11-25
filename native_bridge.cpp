// JNI helpers that Android's Java APIs cannot provide:
//  * /dev/kvm accessibility probe
//  * CPU hwcap bits
//  * fork/exec that lets a child process INHERIT chosen file descriptors, so QEMU can read
//    a Storage-Access-Framework file through /proc/self/fd/N without copying a 5 GB ISO.
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/auxv.h>
#include <errno.h>
#include <string>
#include <vector>

#define STILL_RUNNING (-1000)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_vantora_app_device_NativeBridge_nKvm(JNIEnv *, jclass) {
    return access("/dev/kvm", R_OK | W_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_vantora_app_device_NativeBridge_nHwcap(JNIEnv *, jclass) {
    return (jlong) getauxval(AT_HWCAP);
}

JNIEXPORT jboolean JNICALL
Java_com_vantora_app_device_NativeBridge_nSeekable(JNIEnv *, jclass, jint fd) {
    return lseek(fd, 0, SEEK_CUR) != (off_t) -1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_vantora_app_device_NativeBridge_nSpawn(JNIEnv *env, jclass, jobjectArray jargv,
                                                jstring jdir, jstring jlog, jintArray jkeep) {
    // Copy everything out of the JVM BEFORE fork(); only async-signal-safe calls run in the child.
    std::vector<std::string> args;
    jsize n = env->GetArrayLength(jargv);
    for (jsize i = 0; i < n; i++) {
        auto s = (jstring) env->GetObjectArrayElement(jargv, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        args.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    const char *dirc = env->GetStringUTFChars(jdir, nullptr);
    std::string dir(dirc);
    env->ReleaseStringUTFChars(jdir, dirc);
    const char *logc = env->GetStringUTFChars(jlog, nullptr);
    std::string logPath(logc);
    env->ReleaseStringUTFChars(jlog, logc);

    std::vector<int> keep;
    jsize kn = env->GetArrayLength(jkeep);
    jint *kp = env->GetIntArrayElements(jkeep, nullptr);
    for (jsize i = 0; i < kn; i++) keep.push_back(kp[i]);
    env->ReleaseIntArrayElements(jkeep, kp, JNI_ABORT);

    std::vector<char *> argv;
    for (auto &a : args) argv.push_back(const_cast<char *>(a.c_str()));
    argv.push_back(nullptr);

    int logfd = open(logPath.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
    int nullfd = open("/dev/null", O_RDONLY);

    pid_t pid = fork();
    if (pid < 0) {
        int e = errno;
        if (logfd >= 0) close(logfd);
        if (nullfd >= 0) close(nullfd);
        return -e;
    }
    if (pid == 0) {
        setsid();
        if (chdir(dir.c_str()) != 0) { /* continue anyway */ }
        if (nullfd >= 0) dup2(nullfd, 0);
        if (logfd >= 0) { dup2(logfd, 1); dup2(logfd, 2); }
        for (int fd : keep) {
            int flags = fcntl(fd, F_GETFD);
            if (flags >= 0) fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC);
        }
        execv(argv[0], argv.data());
        _exit(127);
    }
    if (logfd >= 0) close(logfd);
    if (nullfd >= 0) close(nullfd);
    return (jint) pid;
}

JNIEXPORT jint JNICALL
Java_com_vantora_app_device_NativeBridge_nWait(JNIEnv *, jclass, jint pid, jboolean block) {
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, block ? 0 : WNOHANG);
    if (r == 0) return STILL_RUNNING;
    if (r < 0) return 255;  // already reaped / not our child
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return STILL_RUNNING;
}

JNIEXPORT jint JNICALL
Java_com_vantora_app_device_NativeBridge_nKill(JNIEnv *, jclass, jint pid, jint sig) {
    return kill((pid_t) pid, sig);
}

}  // extern "C"
