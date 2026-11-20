/*
 * Host probe for app/src/main/cpp/gamenative_dns_v4mapped.c (Linux/glibc or WSL).
 * Run through tools/dns-shim/test.sh, which builds the shim and LD_PRELOADs it.
 * Checks that gethostname / getaddrinfo(AI_CANONNAME) / gethostbyname /
 * gethostbyname_r all report WOWFOREVER_HOSTNAME, never the device address, and
 * that other names pass straight through.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

static int failures, checks;
#define CHECK(cond, ...) do { checks++; if (!(cond)) { failures++; \
    printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void check_own_name(const char *self, const char *asked)
{
    char name[256], buf[2048];
    struct hostent *host, ret, *result = NULL;
    struct addrinfo hints, *ai = NULL;
    int err = 0, status;

    CHECK(gethostname(name, sizeof(name)) == 0 && strcmp(name, self) == 0,
          "gethostname -> %s", name);

    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_flags = AI_CANONNAME;
    status = getaddrinfo(asked, NULL, &hints, &ai);
    CHECK(status == 0 && ai && ai->ai_canonname && strcmp(ai->ai_canonname, self) == 0,
          "getaddrinfo(%s) -> %d %s", asked, status, ai && ai->ai_canonname ? ai->ai_canonname : "(null)");
    if (ai) freeaddrinfo(ai);

    host = gethostbyname(asked);
    CHECK(host != NULL, "gethostbyname(%s) -> NULL", asked);
    if (host)
    {
        CHECK(strcmp(host->h_name, self) == 0, "gethostbyname h_name %s", host->h_name);
        CHECK(host->h_addrtype == AF_INET && host->h_addr_list[0] != NULL, "gethostbyname address");
    }

    status = gethostbyname_r(asked, &ret, buf, sizeof(buf), &result, &err);
    CHECK(status == 0 && result == &ret, "gethostbyname_r(%s) -> %d", asked, status);
    if (status == 0 && result)
    {
        CHECK(strcmp(result->h_name, self) == 0, "gethostbyname_r h_name %s", result->h_name);
        CHECK(result->h_name >= buf && result->h_name < buf + sizeof(buf), "h_name in caller buffer");
        CHECK(result->h_addr_list[0] != NULL, "gethostbyname_r address");
        /* The name sits outside the bytes libc used: the address is intact. */
        CHECK(result->h_addr_list[0] < result->h_name, "address before name");
    }

    /* Too small a buffer for the name: ERANGE, no result. */
    status = gethostbyname_r(asked, &ret, buf, 4, &result, &err);
    CHECK(status == ERANGE && result == NULL, "tiny buffer -> %d", status);
}

static void check_pass_through(void)
{
    char buf[2048];
    struct hostent *host, ret, *result = NULL;
    int err = 0, status;

    host = gethostbyname("127.0.0.1");
    CHECK(host && strcmp(host->h_name, "127.0.0.1") == 0, "numeric pass-through %s",
          host ? host->h_name : "(null)");
    status = gethostbyname_r("127.0.0.1", &ret, buf, sizeof(buf), &result, &err);
    CHECK(status == 0 && result && strcmp(result->h_name, "127.0.0.1") == 0, "numeric _r pass-through");
    host = gethostbyname("localhost");
    CHECK(host != NULL && strcasecmp(host->h_name, "DESKTOP-DSFTEST1") != 0, "localhost untouched");
}

int main(int argc, char **argv)
{
    const char *self = getenv("WOWFOREVER_HOSTNAME");
    (void)argc; (void)argv;

    if (self)
    {
        check_own_name(self, self);
        /* Lookups are case-insensitive, like Windows names. */
        {
            char lower[64];
            size_t i;
            for (i = 0; self[i] && i + 1 < sizeof(lower); i++)
                lower[i] = (char)(self[i] >= 'A' && self[i] <= 'Z' ? self[i] + 32 : self[i]);
            lower[i] = '\0';
            check_own_name(self, lower);
        }
    }
    else
    {
        /* Unset: gethostbyname of the real host name is libc's answer. */
        struct hostent *host = gethostbyname("DESKTOP-DSFTEST1");
        CHECK(host == NULL, "unset: unknown name stays unknown");
    }
    check_pass_through();
    printf("%s: %d checks, %d failures\n", self ? "hostname set" : "hostname unset", checks, failures);
    return failures ? 1 : 0;
}
