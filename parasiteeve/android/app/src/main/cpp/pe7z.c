/* SPDX-License-Identifier: GPL-3.0-or-later */

/* 7z extraction for texture packs, on Igor Pavlov's LZMA SDK (lzma/, public domain): the SDK
 * parses the archive's headers; each solid block is then decoded here as a stream through a
 * dictionary-sized ring buffer, and its bytes are written to the files they belong to as they come.
 * (The SDK's own extraction decodes a whole solid block into memory, and texture packs have solid
 * blocks of many gigabytes.) */

#include "pe7z.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

#include "lzma/7z.h"
#include "lzma/7zAlloc.h"
#include "lzma/7zCrc.h"
#include "lzma/Lzma2Dec.h"
#include "lzma/LzmaDec.h"

#define METHOD_COPY 0
#define METHOD_LZMA2 0x21
#define METHOD_LZMA 0x30101

/* As in lzma/Lzma2Dec.c, which keeps it to itself. */
#define LZMA2_DICT_SIZE(prop) ((prop) == 40 ? 0xFFFFFFFFu : (((UInt32) 2 | ((prop) & 1)) << ((prop) / 2 + 11)))

#define INPUT_CHUNK (1 << 20)
#define PROGRESS_STEP (16ull << 20)

static const ISzAlloc alloc_main = {SzAlloc, SzFree};
static const ISzAlloc alloc_temp = {SzAllocTemp, SzFreeTemp};

/* ---- The parts as one stream ---- */

typedef struct {
    ISeekInStream vt;
    const Pe7zParts *parts;
    unsigned long long total;
    unsigned long long pos;
    int failed;
} PartsStream;

/* Reads up to size bytes at offset of the joined parts; returns the count, -1 on a read error. */
static long long parts_read_at(const Pe7zParts *parts, unsigned long long offset, void *buf, size_t size) {
    unsigned long long start = 0;
    size_t done = 0;
    int i;
    for (i = 0; i < parts->count && done < size; i++) {
        unsigned long long end = start + (unsigned long long) parts->sizes[i];
        while (offset + done >= start && offset + done < end && done < size) {
            unsigned long long in_part = offset + done - start;
            size_t want = size - done;
            ssize_t got;
            if (want > end - (offset + done)) want = (size_t) (end - (offset + done));
            got = pread(parts->fds[i], (char *) buf + done, want, (off_t) in_part);
            if (got < 0) {
                if (errno == EINTR) continue;
                return -1;
            }
            if (got == 0) return (long long) done;
            done += (size_t) got;
        }
        start = end;
    }
    return (long long) done;
}

static SRes parts_stream_read(ISeekInStreamPtr pp, void *buf, size_t *size) {
    PartsStream *p = Z7_CONTAINER_FROM_VTBL(pp, PartsStream, vt);
    long long got;
    if (p->pos >= p->total) {
        *size = 0;
        return SZ_OK;
    }
    got = parts_read_at(p->parts, p->pos, buf, *size);
    if (got < 0) {
        p->failed = 1;
        *size = 0;
        return SZ_ERROR_READ;
    }
    p->pos += (unsigned long long) got;
    *size = (size_t) got;
    return SZ_OK;
}

static SRes parts_stream_seek(ISeekInStreamPtr pp, Int64 *pos, ESzSeek origin) {
    PartsStream *p = Z7_CONTAINER_FROM_VTBL(pp, PartsStream, vt);
    long long base = origin == SZ_SEEK_SET ? 0 : origin == SZ_SEEK_CUR ? (long long) p->pos : (long long) p->total;
    long long next = base + *pos;
    if (next < 0) return SZ_ERROR_PARAM;
    p->pos = (unsigned long long) next;
    *pos = next;
    return SZ_OK;
}

/* ---- The archive ---- */

typedef struct {
    PartsStream stream;
    CLookToRead2 look;
    CSzArEx db;
    int opened;
} Archive;

static int open_archive(const Pe7zParts *parts, Archive *a) {
    unsigned char start[32];
    unsigned long long next_offset, next_size;
    int i;
    SRes res;

    memset(a, 0, sizeof(*a));
    a->stream.vt.Read = parts_stream_read;
    a->stream.vt.Seek = parts_stream_seek;
    a->stream.parts = parts;
    for (i = 0; i < parts->count; i++) a->stream.total += (unsigned long long) parts->sizes[i];

    if (parts_read_at(parts, 0, start, sizeof(start)) != (long long) sizeof(start)) return PE7Z_ERR_NOT_7Z;
    if (memcmp(start, k7zSignature, k7zSignatureSize) != 0) return PE7Z_ERR_NOT_7Z;
    /* The headers are at the end: with a part missing they'd be past what was given. */
    next_offset = 0;
    next_size = 0;
    for (i = 7; i >= 0; i--) next_offset = (next_offset << 8) | start[12 + i];
    for (i = 7; i >= 0; i--) next_size = (next_size << 8) | start[20 + i];
    if (32 + next_offset + next_size > a->stream.total) return PE7Z_ERR_MISSING_PARTS;

    CrcGenerateTable();
    LookToRead2_CreateVTable(&a->look, 0);
    a->look.realStream = &a->stream.vt;
    a->look.buf = (Byte *) malloc(1 << 18);
    if (!a->look.buf) return PE7Z_ERR_MEMORY;
    a->look.bufSize = 1 << 18;
    LookToRead2_INIT(&a->look);

    SzArEx_Init(&a->db);
    res = SzArEx_Open(&a->db, &a->look.vt, &alloc_main, &alloc_temp);
    if (res != SZ_OK) {
        free(a->look.buf);
        a->look.buf = NULL;
        if (res == SZ_ERROR_MEM) return PE7Z_ERR_MEMORY;
        if (res == SZ_ERROR_UNSUPPORTED) return PE7Z_ERR_UNSUPPORTED;
        if (a->stream.failed) return PE7Z_ERR_READ;
        return PE7Z_ERR_ARCHIVE;
    }
    a->opened = 1;
    return PE7Z_OK;
}

static void close_archive(Archive *a) {
    if (a->opened) SzArEx_Free(&a->db, &alloc_main);
    free(a->look.buf);
    a->look.buf = NULL;
    a->opened = 0;
}

/* The file's name without the folders it's in, as UTF-8; NULL if it can't be read. */
static char *base_name(const CSzArEx *db, UInt32 index) {
    size_t len = SzArEx_GetFileNameUtf16(db, index, NULL);
    UInt16 *wide = (UInt16 *) malloc(len * sizeof(UInt16));
    char *utf8;
    size_t i, start = 0, out = 0;
    if (!wide) return NULL;
    SzArEx_GetFileNameUtf16(db, index, wide);
    for (i = 0; i + 1 < len; i++) {
        if (wide[i] == '/' || wide[i] == '\\') start = i + 1;
    }
    utf8 = (char *) malloc((len - start) * 4 + 1);
    if (!utf8) {
        free(wide);
        return NULL;
    }
    for (i = start; i + 1 < len; i++) {
        UInt32 c = wide[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 2 < len && wide[i + 1] >= 0xDC00 && wide[i + 1] < 0xE000) {
            c = 0x10000 + ((c - 0xD800) << 10) + (wide[i + 1] - 0xDC00);
            i++;
        }
        if (c < 0x80) {
            utf8[out++] = (char) c;
        } else if (c < 0x800) {
            utf8[out++] = (char) (0xC0 | (c >> 6));
            utf8[out++] = (char) (0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            utf8[out++] = (char) (0xE0 | (c >> 12));
            utf8[out++] = (char) (0x80 | ((c >> 6) & 0x3F));
            utf8[out++] = (char) (0x80 | (c & 0x3F));
        } else {
            utf8[out++] = (char) (0xF0 | (c >> 18));
            utf8[out++] = (char) (0x80 | ((c >> 12) & 0x3F));
            utf8[out++] = (char) (0x80 | ((c >> 6) & 0x3F));
            utf8[out++] = (char) (0x80 | (c & 0x3F));
        }
    }
    utf8[out] = '\0';
    free(wide);
    return utf8;
}

/* The image formats Beetle PSX reads replacements from. */
static int is_image(const char *name) {
    static const char *const extensions[] = {".png", ".dds", ".webp", ".jpg", ".jpeg", ".bmp", ".tga"};
    size_t len = strlen(name), i;
    if (len == 0 || name[0] == '.') return 0;
    for (i = 0; i < sizeof(extensions) / sizeof(extensions[0]); i++) {
        size_t ext = strlen(extensions[i]);
        if (len > ext && strcasecmp(name + len - ext, extensions[i]) == 0) return 1;
    }
    return 0;
}

static int wanted(const CSzArEx *db, UInt32 index) {
    char *name;
    int result;
    if (SzArEx_IsDir(db, index)) return 0;
    name = base_name(db, index);
    if (!name) return 0;
    result = is_image(name);
    free(name);
    return result;
}

int pe7z_inspect(const Pe7zParts *parts, Pe7zSummary *summary) {
    Archive a;
    UInt32 i;
    int result = open_archive(parts, &a);
    memset(summary, 0, sizeof(*summary));
    if (result != PE7Z_OK) return result;
    for (i = 0; i < a.db.NumFiles; i++) {
        if (SzArEx_IsDir(&a.db, i)) continue;
        if (wanted(&a.db, i)) {
            summary->files++;
            summary->bytes += SzArEx_GetFileSize(&a.db, i);
        } else {
            summary->skipped++;
        }
    }
    close_archive(&a);
    return PE7Z_OK;
}

/* ---- Writing a solid block's bytes into its files ---- */

typedef struct {
    const CSzArEx *db;
    const char *out_dir;
    UInt32 folder;
    UInt32 next_file;   /* the next file of the folder to start */
    UInt32 file;        /* the file being written */
    int writing;        /* a file is open (or being skipped) */
    int fd;             /* -1 while skipping a file that isn't wanted */
    UInt64 remaining;   /* of the current file */
    UInt32 crc;
    Pe7zSummary *summary;
    unsigned long long *done;
    unsigned long long total;
    unsigned long long next_report;
    Pe7zProgress progress;
    void *user;
} Sink;

static int sink_start_file(Sink *s) {
    const CSzArEx *db = s->db;
    UInt32 end = db->FolderToFile[s->folder + 1];
    while (s->next_file < end) {
        UInt32 i = s->next_file++;
        if (SzArEx_IsDir(db, i) || db->FileToFolder[i] != s->folder) continue;
        s->file = i;
        s->remaining = SzArEx_GetFileSize(db, i);
        s->crc = CRC_INIT_VAL;
        s->writing = 1;
        s->fd = -1;
        if (wanted(db, i)) {
            char *name = base_name(db, i);
            char *path;
            if (!name) return PE7Z_ERR_MEMORY;
            path = (char *) malloc(strlen(s->out_dir) + strlen(name) + 2);
            if (!path) {
                free(name);
                return PE7Z_ERR_MEMORY;
            }
            sprintf(path, "%s/%s", s->out_dir, name);
            s->fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
            free(path);
            free(name);
            if (s->fd < 0) return PE7Z_ERR_WRITE;
        }
        return PE7Z_OK;
    }
    return PE7Z_ERR_ARCHIVE; /* more data than files */
}

static int sink_finish_file(Sink *s) {
    const CSzArEx *db = s->db;
    int extracted = s->fd >= 0;
    if (extracted && close(s->fd) != 0) {
        s->fd = -1;
        return PE7Z_ERR_WRITE;
    }
    s->fd = -1;
    s->writing = 0;
    if (SzBitWithVals_Check(&db->CRCs, s->file) && CRC_GET_DIGEST(s->crc) != db->CRCs.Vals[s->file]) {
        return PE7Z_ERR_CRC;
    }
    if (extracted) {
        s->summary->files++;
        s->summary->bytes += SzArEx_GetFileSize(db, s->file);
    } else {
        s->summary->skipped++;
    }
    return PE7Z_OK;
}

static int sink_write(Sink *s, const Byte *data, size_t size) {
    while (size > 0) {
        size_t take;
        int result;
        if (!s->writing) {
            result = sink_start_file(s);
            if (result != PE7Z_OK) return result;
            if (s->remaining == 0) {
                result = sink_finish_file(s);
                if (result != PE7Z_OK) return result;
                continue;
            }
        }
        take = size < s->remaining ? size : (size_t) s->remaining;
        s->crc = CrcUpdate(s->crc, data, take);
        if (s->fd >= 0) {
            size_t written = 0;
            while (written < take) {
                ssize_t w = write(s->fd, data + written, take - written);
                if (w < 0) {
                    if (errno == EINTR) continue;
                    return PE7Z_ERR_WRITE;
                }
                written += (size_t) w;
            }
        }
        data += take;
        size -= take;
        s->remaining -= take;
        *s->done += take;
        if (s->remaining == 0) {
            result = sink_finish_file(s);
            if (result != PE7Z_OK) return result;
        }
        if (s->progress && *s->done >= s->next_report) {
            s->next_report = *s->done + PROGRESS_STEP;
            if (s->progress(s->user, *s->done, s->total)) return PE7Z_ERR_CANCELLED;
        }
    }
    return PE7Z_OK;
}

/* Files of the folder with no bytes at its end (empty files) still get created. */
static int sink_finish_folder(Sink *s) {
    const CSzArEx *db = s->db;
    UInt32 end = db->FolderToFile[s->folder + 1];
    int result;
    if (s->writing) return PE7Z_ERR_ARCHIVE; /* data ended inside a file */
    while (s->next_file < end) {
        UInt32 i = s->next_file;
        if (SzArEx_IsDir(db, i) || db->FileToFolder[i] != s->folder) {
            s->next_file++;
            continue;
        }
        if (SzArEx_GetFileSize(db, i) != 0) return PE7Z_ERR_ARCHIVE;
        result = sink_start_file(s);
        if (result == PE7Z_OK) result = sink_finish_file(s);
        if (result != PE7Z_OK) return result;
    }
    return PE7Z_OK;
}

/* ---- Decoding a solid block ---- */

static int extract_folder(Archive *a, UInt32 folder_index, Sink *sink) {
    const CSzAr *ar = &a->db.db;
    const Byte *coder_data = ar->CodersData + ar->FoCodersOffsets[folder_index];
    CSzData sd;
    CSzFolder folder;
    const CSzCoderInfo *coder;
    const Byte *props;
    UInt32 pack_index = ar->FoStartPackStreamIndex[folder_index];
    unsigned long long pack_pos = a->db.dataPos + ar->PackPositions[pack_index];
    unsigned long long pack_size = ar->PackPositions[pack_index + 1] - ar->PackPositions[pack_index];
    unsigned long long unpack_left = SzAr_GetFolderUnpackSize(ar, folder_index);
    unsigned long long pack_read = 0;
    Byte *input = NULL;
    size_t in_pos = 0, in_size = 0;
    int result = PE7Z_OK;

    sd.Data = coder_data;
    sd.Size = ar->FoCodersOffsets[folder_index + 1] - ar->FoCodersOffsets[folder_index];
    if (SzGetNextFolderItem(&folder, &sd) != SZ_OK) return PE7Z_ERR_ARCHIVE;
    if (folder.NumCoders != 1 || folder.NumPackStreams != 1) return PE7Z_ERR_UNSUPPORTED;
    coder = &folder.Coders[0];
    props = coder_data + coder->PropsOffset;

    sink->folder = folder_index;
    sink->next_file = a->db.FolderToFile[folder_index];
    sink->writing = 0;

    input = (Byte *) malloc(INPUT_CHUNK);
    if (!input) return PE7Z_ERR_MEMORY;

    if (coder->MethodID == METHOD_COPY) {
        while (unpack_left > 0) {
            size_t want = unpack_left < INPUT_CHUNK ? (size_t) unpack_left : INPUT_CHUNK;
            long long got = parts_read_at(a->stream.parts, pack_pos + pack_read, input, want);
            if (got <= 0) {
                result = got < 0 ? PE7Z_ERR_READ : PE7Z_ERR_MISSING_PARTS;
                break;
            }
            pack_read += (unsigned long long) got;
            unpack_left -= (unsigned long long) got;
            result = sink_write(sink, input, (size_t) got);
            if (result != PE7Z_OK) break;
        }
    } else if (coder->MethodID == METHOD_LZMA2 || coder->MethodID == METHOD_LZMA) {
        int lzma2 = coder->MethodID == METHOD_LZMA2;
        CLzma2Dec dec2;
        CLzmaDec *dec;
        UInt32 dict_size;
        size_t buf_size;
        Byte *dict;
        size_t out_start = 0;

        Lzma2Dec_CONSTRUCT(&dec2);
        dec = &dec2.decoder;
        if (lzma2) {
            if (coder->PropsSize != 1 || props[0] > 40) {
                free(input);
                return PE7Z_ERR_UNSUPPORTED;
            }
            dict_size = LZMA2_DICT_SIZE(props[0]);
            if (Lzma2Dec_AllocateProbs(&dec2, props[0], &alloc_main) != SZ_OK) {
                free(input);
                return PE7Z_ERR_MEMORY;
            }
        } else {
            if (coder->PropsSize != LZMA_PROPS_SIZE) {
                free(input);
                return PE7Z_ERR_UNSUPPORTED;
            }
            dict_size = (UInt32) props[1] | ((UInt32) props[2] << 8) | ((UInt32) props[3] << 16) | ((UInt32) props[4] << 24);
            if (LzmaDec_AllocateProbs(dec, props, LZMA_PROPS_SIZE, &alloc_main) != SZ_OK) {
                free(input);
                return PE7Z_ERR_MEMORY;
            }
        }
        /* The ring buffer: the dictionary's size, or the block's if that's smaller. */
        buf_size = dict_size < (1u << 12) ? (1u << 12) : dict_size;
        if (unpack_left < buf_size) buf_size = (size_t) unpack_left < (1u << 12) ? (1u << 12) : (size_t) unpack_left;
        dict = (Byte *) malloc(buf_size);
        if (!dict) {
            LzmaDec_FreeProbs(dec, &alloc_main);
            free(input);
            return PE7Z_ERR_MEMORY;
        }
        dec->dic = dict;
        dec->dicBufSize = buf_size;
        if (lzma2) Lzma2Dec_Init(&dec2); else LzmaDec_Init(dec);

        while (unpack_left > 0 && result == PE7Z_OK) {
            SizeT in_len, limit;
            ELzmaStatus status;
            SRes res;
            size_t produced;

            if (in_pos == in_size && pack_read < pack_size) {
                size_t want = pack_size - pack_read < INPUT_CHUNK ? (size_t) (pack_size - pack_read) : INPUT_CHUNK;
                long long got = parts_read_at(a->stream.parts, pack_pos + pack_read, input, want);
                if (got <= 0) {
                    result = got < 0 ? PE7Z_ERR_READ : PE7Z_ERR_MISSING_PARTS;
                    break;
                }
                pack_read += (unsigned long long) got;
                in_pos = 0;
                in_size = (size_t) got;
            }
            if (dec->dicPos == dec->dicBufSize) {
                dec->dicPos = 0;
                out_start = 0;
            }
            limit = dec->dicBufSize;
            if (limit - dec->dicPos > unpack_left) limit = dec->dicPos + (SizeT) unpack_left;
            in_len = in_size - in_pos;
            res = lzma2 ? Lzma2Dec_DecodeToDic(&dec2, limit, input + in_pos, &in_len, LZMA_FINISH_ANY, &status)
                        : LzmaDec_DecodeToDic(dec, limit, input + in_pos, &in_len, LZMA_FINISH_ANY, &status);
            in_pos += in_len;
            produced = dec->dicPos - out_start;
            if (produced > 0) {
                result = sink_write(sink, dict + out_start, produced);
                out_start = dec->dicPos;
                unpack_left -= produced;
            }
            if (res != SZ_OK) {
                result = res == SZ_ERROR_MEM ? PE7Z_ERR_MEMORY : PE7Z_ERR_ARCHIVE;
                break;
            }
            if (produced == 0 && in_len == 0) {
                /* No progress: the input ran out before the block did. */
                if (pack_read >= pack_size) result = PE7Z_ERR_ARCHIVE;
                else if (in_pos != in_size) result = PE7Z_ERR_ARCHIVE;
            }
        }
        free(dict);
        dec->dic = NULL;
        LzmaDec_FreeProbs(dec, &alloc_main);
    } else {
        result = PE7Z_ERR_UNSUPPORTED;
    }
    free(input);
    if (result == PE7Z_OK) result = sink_finish_folder(sink);
    if (sink->fd >= 0) {
        close(sink->fd);
        sink->fd = -1;
    }
    return result;
}

int pe7z_extract(const Pe7zParts *parts, const char *out_dir, Pe7zProgress progress, void *user,
                 Pe7zSummary *summary) {
    Archive a;
    Sink sink;
    unsigned long long done = 0, total = 0;
    UInt32 f, i;
    int result = open_archive(parts, &a);
    memset(summary, 0, sizeof(*summary));
    if (result != PE7Z_OK) return result;
    for (i = 0; i < a.db.NumFiles; i++) {
        if (!SzArEx_IsDir(&a.db, i)) total += SzArEx_GetFileSize(&a.db, i);
    }

    memset(&sink, 0, sizeof(sink));
    sink.db = &a.db;
    sink.out_dir = out_dir;
    sink.fd = -1;
    sink.summary = summary;
    sink.done = &done;
    sink.total = total;
    sink.progress = progress;
    sink.user = user;
    if (progress && progress(user, 0, total)) result = PE7Z_ERR_CANCELLED;

    for (f = 0; f < a.db.db.NumFolders && result == PE7Z_OK; f++) {
        result = extract_folder(&a, f, &sink);
    }
    /* Empty files outside any solid block. */
    for (i = 0; i < a.db.NumFiles && result == PE7Z_OK; i++) {
        if (SzArEx_IsDir(&a.db, i) || a.db.FileToFolder[i] != (UInt32) -1 || !wanted(&a.db, i)) continue;
        {
            char *name = base_name(&a.db, i);
            char *path;
            int fd;
            if (!name) {
                result = PE7Z_ERR_MEMORY;
                break;
            }
            path = (char *) malloc(strlen(out_dir) + strlen(name) + 2);
            if (!path) {
                free(name);
                result = PE7Z_ERR_MEMORY;
                break;
            }
            sprintf(path, "%s/%s", out_dir, name);
            fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
            free(path);
            free(name);
            if (fd < 0 || close(fd) != 0) result = PE7Z_ERR_WRITE;
            else summary->files++;
        }
    }
    if (result == PE7Z_OK && progress) progress(user, total, total);
    close_archive(&a);
    return result;
}
