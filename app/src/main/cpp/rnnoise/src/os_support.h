/* Minimal replacement for opus' os_support.h (not shipped with RNNoise 0.2 release sources). */
#ifndef OS_SUPPORT_H
#define OS_SUPPORT_H
#include <string.h>
#ifndef OPUS_CLEAR
#define OPUS_CLEAR(dst, n) (memset((dst), 0, (n)*sizeof(*(dst))))
#endif
#endif
