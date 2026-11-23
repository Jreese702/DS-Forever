/* Modified by DS Forever, 2026-11-23 (GPL-3.0 section 5a): the launcher's game-account frame after the token, and
 * GAME_ACCOUNT / REGION written beside the credential (SOURCE_PROVENANCE.md). Changes are marked "DS Forever". */
#include "protocol.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_config(void) {
    const unsigned char valid[] =
        "54321\n000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\n";
    unsigned short port = 0;
    unsigned char nonce[BRIDGE_NONCE_SIZE] = {0};
    assert(bridge_parse_config(valid, sizeof(valid) - 1, &port, nonce));
    assert(port == 54321);
    for (size_t i = 0; i < sizeof(nonce); i++) assert(nonce[i] == i);
    for (size_t length = 0; length < sizeof(valid) - 1; length++)
        assert(!bridge_parse_config(valid, length, &port, nonce));
    assert(!bridge_parse_config(valid, sizeof(valid), &port, nonce));
    unsigned char malformed[sizeof(valid)];
    memcpy(malformed, valid, sizeof(valid));
    malformed[0] = 'x';
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    memcpy(malformed, valid, sizeof(valid));
    memcpy(malformed, "65536", 5);
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    memcpy(malformed, "00000", 5);
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    memcpy(malformed, valid, sizeof(valid));
    malformed[10] = 'g';
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    memcpy(malformed, valid, sizeof(valid));
    malformed[sizeof(valid) - 2] = '\r';
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    /* A malformed input must not partially replace the caller's output. */
    port = 9000;
    memset(nonce, 0xa5, sizeof(nonce));
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    assert(port == 9000);
    for (size_t i = 0; i < sizeof(nonce); i++) assert(nonce[i] == 0xa5);
    assert(!bridge_parse_config(NULL, 71, &port, nonce));
}

static void test_frames_and_tokens(void) {
    unsigned char bytes[4];
    const uint32_t values[] = {0, 1, 255, 256, 65535, 65536, 0x12345678, UINT32_MAX};
    for (size_t i = 0; i < sizeof(values) / sizeof(values[0]); i++) {
        bridge_encode_length(values[i], bytes);
        assert(bridge_decode_length(bytes) == values[i]);
    }
    bridge_encode_length(0x12345678, bytes);
    assert(bytes[0] == 0x12 && bytes[1] == 0x34 && bytes[2] == 0x56 && bytes[3] == 0x78);
    unsigned char token[BRIDGE_TOKEN_LIMIT + 1];
    memset(token, 'x', sizeof(token));
    assert(!bridge_printable_token(token, 0));
    assert(bridge_printable_token(token, 1));
    assert(bridge_printable_token(token, BRIDGE_TOKEN_LIMIT));
    assert(!bridge_printable_token(token, sizeof(token)));
    for (unsigned int c = 0; c <= 255; c++) {
        token[0] = (unsigned char)c;
        assert(bridge_printable_token(token, 1) == (c >= 32 && c < 127));
    }
    unsigned char a[BRIDGE_NONCE_SIZE] = {0}, b[BRIDGE_NONCE_SIZE] = {0};
    assert(bridge_equal_nonce(a, b));
    for (size_t i = 0; i < sizeof(b); i++) {
        b[i] = 1;
        assert(!bridge_equal_nonce(a, b));
        b[i] = 0;
    }
}

static void test_arguments(void) {
    wchar_t command[256] = {0};
    assert(bridge_append_argument(command, 256, L"C:\\WoW Forever\\Wow-ARM64.exe"));
    assert(bridge_append_argument(command, 256, L"-launcherlogin"));
    assert(bridge_append_argument(command, 256, L"-uid"));
    assert(bridge_append_argument(command, 256, L"wowf"));
    /* The game refuses a quoted option, so only the path with a space is quoted. */
    assert(wcscmp(command, L"\"C:\\WoW Forever\\Wow-ARM64.exe\" -launcherlogin -uid wowf") == 0);
    command[0] = 0;
    assert(bridge_append_argument(command, 256, L"G:\\_forever_\\Wow-ARM64.exe"));
    assert(bridge_append_argument(command, 256, L"trailing\\"));
    assert(wcscmp(command, L"G:\\_forever_\\Wow-ARM64.exe trailing\\") == 0);
    command[0] = 0;
    assert(bridge_append_argument(command, 256, L""));
    assert(bridge_append_argument(command, 256, L"trailing space\\"));
    assert(bridge_append_argument(command, 256, L"say\"hello"));
    assert(bridge_append_argument(command, 256, L"a\tb"));
    assert(wcscmp(command, L"\"\" \"trailing space\\\\\" \"say\\\"hello\" \"a\tb\"") == 0);
    command[0] = 0;
    assert(bridge_append_argument(command, 256, L"\\\""));
    assert(wcscmp(command, L"\"\\\\\\\"\"") == 0);
    wchar_t exact[3] = {0};
    assert(bridge_append_argument(exact, 3, L"ab"));
    wchar_t guarded[3] = {0, 0, L'X'};
    assert(!bridge_append_argument(guarded, 2, L"ab"));
    assert(guarded[2] == L'X');
    wchar_t exact_quoted[6] = {0};
    assert(bridge_append_argument(exact_quoted, 6, L"a b"));
    wchar_t guarded_quoted[6] = {0, 0, 0, 0, 0, L'X'};
    assert(!bridge_append_argument(guarded_quoted, 5, L"a b"));
    assert(guarded_quoted[5] == L'X');
    wchar_t unterminated[2] = {L'x', L'x'};
    assert(!bridge_append_argument(unterminated, 2, L"a"));
}

/* DS Forever: the game-account frame (placeholder names only). */
static int account(const char *frame, char name[BRIDGE_ACCOUNT_LIMIT + 1], char region[BRIDGE_REGION_SIZE + 1]) {
    return bridge_parse_account((const unsigned char *)frame, strlen(frame), name, region);
}

static void test_account(void) {
    char name[BRIDGE_ACCOUNT_LIMIT + 1], region[BRIDGE_REGION_SIZE + 1];
    assert(account("-", name, region) == 0);
    assert(account("US:WoW1", name, region) == 1);
    assert(strcmp(name, "WoW1") == 0 && strcmp(region, "US") == 0);
    assert(account("EU:My Old:Name", name, region) == 1);          /* inner spaces and colons are the name's */
    assert(strcmp(name, "My Old:Name") == 0 && strcmp(region, "EU") == 0);
    char longest[BRIDGE_ACCOUNT_FRAME_LIMIT + 2];
    memcpy(longest, "KR:", 3);
    memset(longest + 3, 'x', BRIDGE_ACCOUNT_LIMIT);
    longest[3 + BRIDGE_ACCOUNT_LIMIT] = 0;
    assert(account(longest, name, region) == 1 && strlen(name) == BRIDGE_ACCOUNT_LIMIT);
    longest[3 + BRIDGE_ACCOUNT_LIMIT] = 'x';
    longest[4 + BRIDGE_ACCOUNT_LIMIT] = 0;
    assert(account(longest, name, region) == -1);                  /* 65 characters */
    /* malformed: never partly written */
    memset(name, 'Q', sizeof(name));
    memset(region, 'Q', sizeof(region));
    const char *bad[] = {"", "--", "US", "US:", "us:WoW1", "U1:WoW1", "USA:WoW1", "US-WoW1", "US: WoW1", "US:WoW1 ", "US:Wo\tW1"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) assert(account(bad[i], name, region) == -1);
    const unsigned char control[] = {'U', 'S', ':', 'W', 0x7f, '1'};
    assert(bridge_parse_account(control, sizeof(control), name, region) == -1);
    const unsigned char nul[] = {'U', 'S', ':', 'W', 0, '1'};
    assert(bridge_parse_account(nul, sizeof(nul), name, region) == -1);
    for (size_t i = 0; i < sizeof(name); i++) assert(name[i] == 'Q');
    for (size_t i = 0; i < sizeof(region); i++) assert(region[i] == 'Q');
    assert(bridge_parse_account(NULL, 1, name, region) == -1);
}

int main(void) {
    test_config();
    test_frames_and_tokens();
    test_arguments();
    test_account();
    puts("Session bridge protocol tests passed");
    return 0;
}
