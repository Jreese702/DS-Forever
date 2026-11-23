/* Modified by DS Forever, 2026-11-23 (GPL-3.0 section 5a): the launcher's game-account frame after the token, and
 * GAME_ACCOUNT / REGION written beside the credential (SOURCE_PROVENANCE.md). Changes are marked "DS Forever". */
#ifndef WOW_SESSION_PROTOCOL_H
#define WOW_SESSION_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>
#include <wchar.h>

#define BRIDGE_NONCE_SIZE 32
#define BRIDGE_TOKEN_LIMIT 2048
#define BRIDGE_DEVICE_LIMIT 4096
#define BRIDGE_COMMAND_LIMIT 32768
/* DS Forever: the game account handed to the game (GAME_ACCOUNT, beside REGION), as the Battle.net app writes them. */
#define BRIDGE_ACCOUNT_LIMIT 64
#define BRIDGE_REGION_SIZE 2
#define BRIDGE_ACCOUNT_FRAME_LIMIT (BRIDGE_REGION_SIZE + 1 + BRIDGE_ACCOUNT_LIMIT)

int bridge_parse_config(const unsigned char *bytes, size_t length,
                        unsigned short *port, unsigned char nonce[BRIDGE_NONCE_SIZE]);
int bridge_printable_token(const unsigned char *bytes, size_t length);
int bridge_equal_nonce(const unsigned char a[BRIDGE_NONCE_SIZE],
                       const unsigned char b[BRIDGE_NONCE_SIZE]);
void bridge_encode_length(uint32_t length, unsigned char out[4]);
uint32_t bridge_decode_length(const unsigned char in[4]);
int bridge_append_argument(wchar_t *command, size_t capacity, const wchar_t *argument);
/* The launcher's game-account frame: "-" = none (the game asks), or "<REGION>:<name>" (two capital letters, then 1-64
 * printable ASCII characters with no leading or trailing space). Returns 1 with [name] and [region] NUL-terminated, 0 for none,
 * -1 when malformed (the caller then treats it as none). A malformed input never changes the outputs. */
int bridge_parse_account(const unsigned char *bytes, size_t length,
                         char name[BRIDGE_ACCOUNT_LIMIT + 1], char region[BRIDGE_REGION_SIZE + 1]);

#endif
