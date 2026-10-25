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
//
// "run" prints "progress <bytes done> <bytes in all>" as it goes and "done" at the end; any failure
// is a line starting "error:" and exit code 1. Built with StormLib (Wargus's copy).

#include <StormLib.h>

#include <cerrno>
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
	SFileCloseFile(file);
	if (!read || got != size) return false;
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
	good = SFileFinishFile(out) && good;
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

} // namespace

int main(int argc, char **argv) {
	if (argc == 5 && std::strcmp(argv[1], "extract") == 0) return extract(argv[2], argv[3], argv[4]);
	if (argc == 5 && std::strcmp(argv[1], "run") == 0) return run(argv[2], argv[3], argv[4]);
	std::fprintf(stderr, "usage: wc3unpack extract <package> <name> <output file>\n"
		"       wc3unpack run <package> <plan> <game folder>\n");
	return 2;
}
