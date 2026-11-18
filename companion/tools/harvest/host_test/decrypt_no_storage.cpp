// #472 HOST REPRO (1.0.33 SIGSEGV on the kit-pack threads, libcasc.so pc 0x4fc98 in CascDecrypt, fault addr 0x8d80): an encrypted
// ('E') frame read through a handle with NO storage (hs == NULL: every CascOpenLocalFile handle, i.e. casc_jni.cpp's retry of a
// damaged entry from the loose cache via wc_reopen_cached). Unpatched CascLib reads NULL->KeyMap.HashTable[0xe1] (0x8678 + 0x708 =
// 0x8d80, the reports' address) and dies; patched (tools/harvest/patch_casclib.py) it answers ERROR_FILE_ENCRYPTED.
// Windows host, MSVC x64 (vcvars64), CascDecrypt.cpp from the tree under test, the rest from an unpatched CascLib <U>\src:
//   cl /nologo /EHsc /DCASCLIB_NO_AUTO_LINK_LIBRARY /DCASCLIB_NODEBUG /DUNICODE /D_UNICODE /D_CRT_SECURE_NO_WARNINGS /I<U>\src
//      decrypt_no_storage.cpp <tree>\CascDecrypt.cpp <U>\src\common\Common.cpp <U>\src\common\Mime.cpp
//      <U>\src\common\FileStream.cpp <U>\src\common\Sockets.cpp <U>\src\hashes\md5.cpp <U>\src\hashes\sha1.cpp
//      <U>\src\jenkins\lookup3.c /link ws2_32.lib
// 2026-11-18: unpatched exit 0xC0000005 (access violation), patched exit 0 ("= 6002").
#include <stdio.h>
#define __CASCLIB_SELF__
#include "CascLib.h"
#include "CascCommon.h"

int main() {
    BYTE in[64] = {0};
    size_t n = 0;
    in[n++] = 8;                                  // key name size
    for (int i = 0; i < 8; i++) in[n++] = 0xe1;   // key name (low byte 0xe1: the crash's hash slot, x10 = 0x708)
    in[n++] = 4;                                  // IV size
    for (int i = 0; i < 4; i++) in[n++] = 0x11;
    in[n++] = 'S';                                // Salsa20
    for (int i = 0; i < 16; i++) in[n++] = (BYTE)i;
    BYTE out[64]; DWORD cb = sizeof(out);
    DWORD err = CascDecrypt(NULL, out, &cb, in, (DWORD)n, 0);
    printf("CascDecrypt(NULL storage) = %u (ERROR_FILE_ENCRYPTED = %u)\n", (unsigned)err, (unsigned)ERROR_FILE_ENCRYPTED);
    return err == ERROR_FILE_ENCRYPTED ? 0 : 1;
}
