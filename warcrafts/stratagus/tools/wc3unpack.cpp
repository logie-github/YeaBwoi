// SPDX-License-Identifier: GPL-2.0-or-later
//
// wc3unpack: installs Warcraft III from Blizzard's classic installer package ("Installer Tome.mpq")
// on the phone, without Blizzard's installer, its windows or its questions. The app reads the
// package's install script (InstallerFileList.xml, taken out with "extract") and hands this program
// a plan of what the script does on Windows, one line per file:
//
//   F <tab> <name in the package> <tab> <path in the game's folder>      a file copied out
//   M <tab> <archive in the game's folder> <tab> <name in the package> <tab> <name in the archive>
//                                                                         a file put into an archive
//
// Usage:
//   wc3unpack extract <package> <name> <output file>
//   wc3unpack run <package> <plan> <game folder>
//   wc3unpack key <archive> <name in the archive> <text>
//
// "key" writes what Blizzard's installer writes into the game's archives after installing: the
// player's CD key (font\font.gid in war3.mpq, font\font.exp in War3x.mpq), the owner's name
// (font\font.clh) and the installer's mark (font\font.ccd, the text "sgubon"), each encoded the way
// Game.dll 1.27 decodes them (read from its code and checked against it, run in an emulator):
// one 64-byte block per file, its bytes plus a keystream from Blizzard's SHA-1, then IDEA with
// little-endian 16-bit words, then 8 bytes: a checksum (the first SHA-1 word after hashing the
// block), 0, the text's length. The keys come from a fixed seed of the C library's rand().
//
// "run" prints "progress <bytes done> <bytes in all>" as it goes and "done" at the end; any failure
// is a line starting "error:" and exit code 1. Built with StormLib (Wargus's copy).

#include <StormLib.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {

struct Step {
	char kind;
	std::string archive, from, to;
};

int fail(const std::string &what) {
	std::printf("error: %s (%d)\n", what.c_str(), (int) GetLastError());
	std::fflush(stdout);
	return 1;
}

std::vector<std::string> split(const std::string &line) {
	std::vector<std::string> parts;
	size_t start = 0;
	for (;;) {
		size_t tab = line.find('\t', start);
		parts.push_back(line.substr(start, tab == std::string::npos ? std::string::npos : tab - start));
		if (tab == std::string::npos) return parts;
		start = tab + 1;
	}
}

// A path of the script (Windows' backslashes) in the game's folder.
std::string local(const std::string &folder, std::string path) {
	for (char &c : path) if (c == '\\') c = '/';
	return folder + "/" + path;
}

bool makeParents(const std::string &path) {
	for (size_t i = 1; i < path.size(); ++i) {
		if (path[i] != '/') continue;
		std::string dir = path.substr(0, i);
		if (mkdir(dir.c_str(), 0755) != 0 && errno != EEXIST) return false;
	}
	return true;
}

std::vector<char> buffer(1 << 20);

bool copyOut(HANDLE package, const std::string &name, const std::string &out, unsigned long long &done, unsigned long long total) {
	HANDLE file = nullptr;
	if (!SFileOpenFileEx(package, name.c_str(), SFILE_OPEN_FROM_MPQ, &file)) return false;
	if (!makeParents(out)) { SFileCloseFile(file); return false; }
	std::string part = out + ".part";
	FILE *f = std::fopen(part.c_str(), "wb");
	if (!f) { SFileCloseFile(file); return false; }
	bool good = true;
	for (;;) {
		DWORD got = 0;
		bool more = SFileReadFile(file, buffer.data(), (DWORD) buffer.size(), &got, nullptr);
		if (got && std::fwrite(buffer.data(), 1, got, f) != got) { good = false; break; }
		done += got;
		if (got) std::printf("progress %llu %llu\n", done, total);
		if (!more) { good = GetLastError() == ERROR_HANDLE_EOF; break; }
		if (got == 0) break;
	}
	SFileCloseFile(file);
	if (std::fclose(f) != 0) good = false;
	if (good) good = std::rename(part.c_str(), out.c_str()) == 0;
	if (!good) std::remove(part.c_str());
	std::fflush(stdout);
	return good;
}

bool putInto(HANDLE package, HANDLE archive, const Step &step, unsigned long long &done, unsigned long long total) {
	HANDLE file = nullptr;
	if (!SFileOpenFileEx(package, step.from.c_str(), SFILE_OPEN_FROM_MPQ, &file)) return false;
	DWORD size = SFileGetFileSize(file, nullptr);
	std::vector<char> data(size);
	DWORD got = 0;
	bool read = size == 0 || (SFileReadFile(file, data.data(), size, &got, nullptr) || GetLastError() == ERROR_HANDLE_EOF);
	DWORD readError = GetLastError();
	SFileCloseFile(file);
	if (!read || got != size) { SetLastError(readError); return false; }
	HANDLE out = nullptr;
	if (!SFileCreateFile(archive, step.to.c_str(), 0, size, 0, MPQ_FILE_REPLACEEXISTING, &out)) {
		if (GetLastError() != ERROR_DISK_FULL) return false;
		// The archive's file table is full: made bigger, then again.
		DWORD max = 0;
		SFileGetFileInfo(archive, SFileMpqMaxFileCount, &max, sizeof(max), nullptr);
		if (!SFileSetMaxFileCount(archive, max * 2) ||
			!SFileCreateFile(archive, step.to.c_str(), 0, size, 0, MPQ_FILE_REPLACEEXISTING, &out)) return false;
	}
	bool good = size == 0 || SFileWriteFile(out, data.data(), size, 0);
	if (!good) {
		DWORD error = GetLastError();
		SFileFinishFile(out);
		SetLastError(error);
		return false;
	}
	if (!SFileFinishFile(out)) return false;
	done += size;
	std::printf("progress %llu %llu\n", done, total);
	std::fflush(stdout);
	return good;
}

int extract(const char *packagePath, const char *name, const char *out) {
	HANDLE package = nullptr;
	if (!SFileOpenArchive(packagePath, 0, STREAM_FLAG_READ_ONLY, &package)) return fail(std::string("can't open ") + packagePath);
	unsigned long long done = 0;
	bool good = copyOut(package, name, out, done, 0);
	SFileCloseArchive(package);
	return good ? 0 : fail(std::string("can't extract ") + name);
}

int run(const char *packagePath, const char *planPath, const std::string &folder) {
	FILE *planFile = std::fopen(planPath, "r");
	if (!planFile) return fail(std::string("can't read ") + planPath);
	std::vector<Step> steps;
	char line[4096];
	while (std::fgets(line, sizeof(line), planFile)) {
		std::string text(line);
		while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.pop_back();
		if (text.empty()) continue;
		std::vector<std::string> p = split(text);
		if (p[0] == "F" && p.size() == 3) steps.push_back({'F', "", p[1], p[2]});
		else if (p[0] == "M" && p.size() == 4) steps.push_back({'M', p[1], p[2], p[3]});
		else { std::fclose(planFile); return fail("bad plan line: " + text); }
	}
	std::fclose(planFile);

	HANDLE package = nullptr;
	if (!SFileOpenArchive(packagePath, 0, STREAM_FLAG_READ_ONLY, &package)) return fail(std::string("can't open ") + packagePath);
	unsigned long long total = 0;
	for (const Step &step : steps) {
		HANDLE file = nullptr;
		if (!SFileOpenFileEx(package, step.from.c_str(), SFILE_OPEN_FROM_MPQ, &file)) {
			SFileCloseArchive(package);
			return fail("not in the package: " + step.from);
		}
		total += SFileGetFileSize(file, nullptr);
		SFileCloseFile(file);
	}

	unsigned long long done = 0;
	std::map<std::string, HANDLE> archives;
	int result = 0;
	for (const Step &step : steps) {
		if (step.kind == 'F') {
			if (!copyOut(package, step.from, local(folder, step.to), done, total)) { result = fail("can't copy " + step.from); break; }
			continue;
		}
		HANDLE &archive = archives[step.archive];
		if (!archive) {
			std::string path = local(folder, step.archive);
			if (!SFileOpenArchive(path.c_str(), 0, 0, &archive)) { archive = nullptr; result = fail("can't open " + step.archive); break; }
		}
		if (!putInto(package, archive, step, done, total)) { result = fail("can't put " + step.from + " into " + step.archive); break; }
	}
	for (auto &entry : archives) {
		if (entry.second && !SFileCloseArchive(entry.second) && result == 0) result = fail("can't finish " + entry.first);
	}
	SFileCloseArchive(package);
	if (result == 0) std::printf("done\n");
	std::fflush(stdout);
	return result;
}


// ---- The key files' encoding (see the top). ----

struct MsRand {
	uint32_t seed;
	int next() { seed = seed * 214013u + 2531011u; return (seed >> 16) & 0x7fff; }
};

uint32_t sar(uint32_t x, int n) { return (uint32_t) ((int32_t) x >> n); }

// Blizzard's SHA-1: words read little-endian, no rotation in the message expansion, rotations
// made with arithmetic shifts.
void bsha1(uint32_t h[5], const uint8_t block[64]) {
	uint32_t w[80];
	for (int i = 0; i < 16; ++i) w[i] = block[4 * i] | (block[4 * i + 1] << 8) | (block[4 * i + 2] << 16) | ((uint32_t) block[4 * i + 3] << 24);
	for (int i = 16; i < 80; ++i) w[i] = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
	uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
	for (int i = 0; i < 80; ++i) {
		uint32_t f, k;
		if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
		else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
		else if (i < 60) { f = ((c | b) & d) | (c & b); k = 0x8F1BBCDC; }
		else { f = b ^ c ^ d; k = 0xCA62C1D6; }
		uint32_t t = ((a << 5) | sar(a, 27)) + f + e + k + w[i];
		e = d; d = c; c = (b << 30) | sar(b, 2); b = a; a = t;
	}
	h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
}

const uint32_t SHA_IV[5] = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0};

uint16_t ideaMul(uint32_t a, uint32_t b) {
	if (a == 0) a = 0x10000;
	if (b == 0) b = 0x10000;
	uint32_t r = (uint32_t) (((uint64_t) a * b) % 0x10001);
	return (uint16_t) (r == 0x10000 ? 0 : r);
}

void ideaEncrypt(uint8_t data[8], const uint16_t z[52]) {
	uint16_t x[4];
	for (int i = 0; i < 4; ++i) x[i] = data[2 * i] | (data[2 * i + 1] << 8);
	for (int r = 0; r < 8; ++r) {
		const uint16_t *k = z + 6 * r;
		x[0] = ideaMul(x[0], k[0]); x[1] += k[1]; x[2] += k[2]; x[3] = ideaMul(x[3], k[3]);
		uint16_t t0 = ideaMul(k[4], x[0] ^ x[2]);
		uint16_t t1 = ideaMul(k[5], (uint16_t) (t0 + (x[1] ^ x[3])));
		t0 += t1;
		x[0] ^= t1; x[3] ^= t0; t0 ^= x[1]; x[1] = x[2] ^ t1; x[2] = t0;
	}
	uint16_t out[4] = {ideaMul(x[0], z[48]), (uint16_t) (x[2] + z[49]), (uint16_t) (x[1] + z[50]), ideaMul(x[3], z[51])};
	for (int i = 0; i < 4; ++i) { data[2 * i] = out[i] & 0xff; data[2 * i + 1] = out[i] >> 8; }
}

// The file holding [text] (at most 63 bytes), as the game reads it.
std::vector<uint8_t> encodeKeyFile(const std::string &text) {
	// The files' key: 19 non-zero bytes of rand() after srand(0x150b).
	uint8_t key[19];
	MsRand r{0x150b};
	for (int n = 0; n < 19;) { uint8_t b = r.next() & 0xff; if (b) key[n++] = b; }
	// 0x70 bytes of rand() after srand(0x4fa7), mixed with the SHA-1 of the key repeated.
	uint8_t buf[0x70], keyBlock[64];
	MsRand r2{0x4fa7};
	for (int i = 0; i < 0x70; ++i) buf[i] = r2.next() & 0xff;
	for (int i = 0; i < 64; ++i) keyBlock[i] = key[i % 19];
	uint32_t h[5];
	std::memcpy(h, SHA_IV, sizeof(h));
	bsha1(h, keyBlock);
	uint8_t d[20];
	for (int i = 0; i < 5; ++i) for (int j = 0; j < 4; ++j) d[4 * i + j] = (h[i] >> (8 * j)) & 0xff;
	for (int i = 0; i < 0x70; ++i) buf[i] ^= d[i % 20];
	// The keystream: the SHA-1 state after the last 64 of those bytes; IDEA's key: the first 16.
	std::memcpy(h, SHA_IV, sizeof(h));
	bsha1(h, buf + 0x30);
	for (int i = 0; i < 5; ++i) for (int j = 0; j < 4; ++j) d[4 * i + j] = (h[i] >> (8 * j)) & 0xff;
	uint16_t z[52];
	{
		uint8_t k[16];
		for (int i = 0; i < 16; i += 2) { k[i] = buf[i + 1]; k[i + 1] = buf[i]; } // words little-endian
		for (int i = 0; i < 52; ++i) {
			int bit = 16 * (i % 8) + 25 * (i / 8);
			uint16_t v = 0;
			for (int b = 0; b < 16; ++b) {
				int p = (bit + b) % 128;
				v = (uint16_t) ((v << 1) | ((k[p / 8] >> (7 - p % 8)) & 1));
			}
			z[i] = v;
		}
	}
	uint8_t block[64] = {0};
	std::memcpy(block, text.data(), text.size());
	std::vector<uint8_t> out(72);
	for (int k = 0; k < 64; ++k) out[k] = (uint8_t) (block[k] + d[(63 - k) % 20]);
	for (int i = 0; i < 64; i += 8) ideaEncrypt(&out[i], z);
	bsha1(h, block);
	for (int j = 0; j < 4; ++j) out[64 + j] = (h[0] >> (8 * j)) & 0xff;
	out[68] = 0;
	out[69] = (uint8_t) text.size();
	return out;
}

int key(const char *archivePath, const char *name, const std::string &text) {
	if (text.size() > 63) { SetLastError(ERROR_INVALID_PARAMETER); return fail("text too long"); }
	std::vector<uint8_t> data = encodeKeyFile(text);
	HANDLE archive = nullptr;
	if (!SFileOpenArchive(archivePath, 0, 0, &archive)) return fail(std::string("can't open ") + archivePath);
	HANDLE out = nullptr;
	bool good = SFileCreateFile(archive, name, 0, (DWORD) data.size(), 0, MPQ_FILE_REPLACEEXISTING, &out);
	if (good) {
		good = SFileWriteFile(out, data.data(), (DWORD) data.size(), 0);
		DWORD error = GetLastError();
		good = SFileFinishFile(out) && good;
		if (!good) SetLastError(error);
	}
	if (!good) { DWORD error = GetLastError(); SFileCloseArchive(archive); SetLastError(error); return fail(std::string("can't write ") + name); }
	if (!SFileCloseArchive(archive)) return fail(std::string("can't finish ") + archivePath);
	std::printf("done\n");
	return 0;
}

} // namespace

int main(int argc, char **argv) {
	if (argc == 5 && std::strcmp(argv[1], "extract") == 0) return extract(argv[2], argv[3], argv[4]);
	if (argc == 5 && std::strcmp(argv[1], "run") == 0) return run(argv[2], argv[3], argv[4]);
	if (argc == 5 && std::strcmp(argv[1], "key") == 0) return key(argv[2], argv[3], argv[4]);
	std::fprintf(stderr, "usage: wc3unpack extract <package> <name> <output file>\n"
		"       wc3unpack run <package> <plan> <game folder>\n"
		"       wc3unpack key <archive> <name in the archive> <text>\n");
	return 2;
}
