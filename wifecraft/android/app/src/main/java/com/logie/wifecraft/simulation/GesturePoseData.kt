package com.logie.wifecraft.simulation

// Made by tools/gesture_poses.py (the engine-test harness on the game's models); do not edit by hand.
/** Each measured game animation's worst pose over human_f, human_m, dwarf_m, nightelf_f, draenei_f, bloodelf_m, orc_m, undead_f, gnome_f: [PoseSanity] judges conversation gestures by these. */
internal object GesturePoseData {
    val ROWS: Map<Int, PoseMetrics> = mapOf(
        0 to PoseMetrics(0.0f, 0.0f, 1.000f, 1.608f, 0.000f),
        60 to PoseMetrics(8.8f, 7.9f, 0.982f, 1.667f, 0.266f),
        64 to PoseMetrics(32.8f, 35.8f, 0.770f, 2.161f, 0.677f),
        65 to PoseMetrics(17.8f, 17.6f, 0.928f, 1.636f, 0.000f),
        66 to PoseMetrics(83.1f, 30.2f, 0.038f, 1.558f, 0.211f),
        67 to PoseMetrics(9.3f, 7.8f, 0.977f, 2.289f, 0.843f),
        68 to PoseMetrics(71.4f, 47.3f, 0.196f, 2.550f, 1.214f),
        69 to PoseMetrics(24.9f, 32.2f, 0.867f, 1.890f, 0.566f),
        70 to PoseMetrics(48.7f, 40.2f, 0.535f, 1.663f, 0.266f),
        73 to PoseMetrics(55.4f, 16.6f, 0.502f, 2.115f, 0.264f),
        74 to PoseMetrics(48.8f, 39.7f, 0.515f, 1.932f, 0.412f),
        77 to PoseMetrics(53.0f, 39.3f, 0.442f, 1.603f, 0.000f),
        78 to PoseMetrics(34.9f, 19.2f, 0.740f, 1.164f, 0.000f),
        79 to PoseMetrics(62.2f, 44.1f, 0.258f, 1.825f, 0.531f),
        80 to PoseMetrics(4.3f, 11.7f, 0.959f, 1.387f, 0.000f),
        81 to PoseMetrics(54.4f, 49.3f, 0.430f, 1.679f, 0.253f),
        82 to PoseMetrics(34.4f, 25.7f, 0.724f, 2.123f, 1.048f),
        83 to PoseMetrics(31.1f, 28.3f, 0.728f, 1.493f, 0.000f),
        84 to PoseMetrics(9.0f, 7.3f, 0.959f, 2.220f, 0.800f),
        113 to PoseMetrics(12.7f, 11.3f, 0.948f, 2.024f, 0.376f),
        185 to PoseMetrics(33.2f, 19.9f, 0.737f, 1.558f, 0.000f),
        186 to PoseMetrics(4.3f, 3.8f, 0.980f, 1.832f, 0.059f),
        208 to PoseMetrics(8.8f, 7.9f, 0.982f, 1.667f, 0.266f),
        1492 to PoseMetrics(2.3f, 5.6f, 0.986f, 1.603f, 0.000f),
        778 to PoseMetrics(6.7f, 10.1f, 0.985f, 1.608f, 0.000f),
    )
}
