/* tools/pageout/test.sh: DS Forever's request to [pid] (sigqueue SIGURG with our value), or a plain kill with "plain", another value
 * with "wrong", the GAME value with "game" (to the process) or "gamethread" (to its thread [pid] with rt_tgsigqueueinfo, as
 * NativeHeap.pageOutSignalGame sends it) */
#define _GNU_SOURCE
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    pid_t pid = (pid_t) atoi(argv[1]);
    const char *m = argc > 2 ? argv[2] : "";
    if (strcmp(m, "plain") == 0) return kill(pid, SIGURG) == 0 ? 0 : 1;
    if (strcmp(m, "gamethread") == 0) {
        siginfo_t si;
        memset(&si, 0, sizeof si);
        si.si_signo = SIGURG; si.si_code = SI_QUEUE; si.si_pid = getpid(); si.si_uid = getuid();
        si.si_value.sival_int = 0x44534647;
        return syscall(SYS_rt_tgsigqueueinfo, pid, pid, SIGURG, &si) == 0 ? 0 : 1;
    }
    union sigval v;
    v.sival_int = strcmp(m, "wrong") == 0 ? 1 : strcmp(m, "game") == 0 ? 0x44534647 : 0x44534650;
    return sigqueue(pid, SIGURG, v) == 0 ? 0 : 1;
}
