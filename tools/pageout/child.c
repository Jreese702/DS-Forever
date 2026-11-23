/* A stand-in Wine process for tools/pageout/test.sh: 24 MiB of its own memory touched, then it waits to be stopped. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

int main(void) {
    size_t n = 24u << 20;
    unsigned char *a = mmap(0, n, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (a == MAP_FAILED) return 2;
    memset(a, 0x5a, n);
    for (;;) pause();
}
