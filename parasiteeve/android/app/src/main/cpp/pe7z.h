/* SPDX-License-Identifier: GPL-3.0-or-later */

/* Extracts the images of a 7z archive, split in parts (name.7z.001, .002, ...) or whole, into one
 * folder: every image file, whatever folder it's in inside the archive, lands directly in the
 * output folder, as Beetle PSX's texture replacement folder wants them. Solid blocks of any size
 * are decoded as a stream, so the output is written as it's decoded. */

#ifndef PE7Z_H
#define PE7Z_H

#ifdef __cplusplus
extern "C" {
#endif

/* The parts of the archive, in order: open file descriptors (read at any offset) and their sizes. */
typedef struct {
    int count;
    const int *fds;
    const long long *sizes;
} Pe7zParts;

typedef struct {
    /* Image files, and their bytes. */
    unsigned long long files;
    unsigned long long bytes;
    /* Other files, which aren't extracted. */
    unsigned long long skipped;
} Pe7zSummary;

/* Called now and then with the bytes decoded so far (of all files, extracted or not) and the
 * total; returns nonzero to stop. */
typedef int (*Pe7zProgress)(void *user, unsigned long long done, unsigned long long total);

enum {
    PE7Z_OK = 0,
    PE7Z_ERR_NOT_7Z = 1,
    PE7Z_ERR_MISSING_PARTS = 2,
    PE7Z_ERR_ARCHIVE = 3,
    PE7Z_ERR_UNSUPPORTED = 4,
    PE7Z_ERR_READ = 5,
    PE7Z_ERR_WRITE = 6,
    PE7Z_ERR_CRC = 7,
    PE7Z_ERR_MEMORY = 8,
    PE7Z_ERR_CANCELLED = 9,
};

/* What extracting would write. */
int pe7z_inspect(const Pe7zParts *parts, Pe7zSummary *summary);

/* Writes the images into out_dir (which must exist), checking each against its CRC. */
int pe7z_extract(const Pe7zParts *parts, const char *out_dir, Pe7zProgress progress, void *user,
                 Pe7zSummary *summary);

#ifdef __cplusplus
}
#endif

#endif
