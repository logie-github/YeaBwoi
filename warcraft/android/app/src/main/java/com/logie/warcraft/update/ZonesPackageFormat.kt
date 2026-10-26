// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.update

/**
 * The starting areas' package (scripts/package-zones.sh: cata-zones.tar.xz, its entries under
 * `cata-zones/`), as ZonesPackage unpacks it into `files/zones`.
 */
object ZonesPackageFormat {
    const val TOP = "cata-zones"

    /** Where an entry goes in `zones/` ("" for the package's own folder), or null for one outside it. */
    fun relative(name: String): String? {
        val path = name.removePrefix("./")
        if (path == TOP || path == "$TOP/") return ""
        if (!path.startsWith("$TOP/")) return null
        val inside = path.removePrefix("$TOP/").removeSuffix("/")
        if (inside.isEmpty() || '\\' in inside || '\u0000' in inside) return null
        return inside.takeIf { p -> p.split('/').none { it.isEmpty() || it == "." || it == ".." } }
    }
}
