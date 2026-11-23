/* tools/pageout/test.sh: DS Forever's request to [pid] (sigqueue SIGURG with our value), or a plain kill with "plain" */
#include <signal.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    pid_t pid = (pid_t) atoi(argv[1]);
    if (argc > 2 && strcmp(argv[2], "plain") == 0) return kill(pid, SIGURG) == 0 ? 0 : 1;
    union sigval v;
    v.sival_int = argc > 2 && strcmp(argv[2], "wrong") == 0 ? 1 : 0x44534650;
    return sigqueue(pid, SIGURG, v) == 0 ? 0 : 1;
}
