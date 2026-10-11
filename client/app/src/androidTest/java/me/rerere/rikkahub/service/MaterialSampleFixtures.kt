package me.rerere.rikkahub.service

import java.io.File
import me.rerere.rikkahub.feature.material.MaterialWriter

/** A newly written synthetic source for each method/run; never relies on an old output. */
internal suspend fun writeSyntheticMaterialSample(file: File, extension: String) {
    MaterialWriter.write(file, extension, "合成 QA 材料：三角形面积为底乘高除以二。",
        listOf(listOf("名称", "数值"), listOf("样例", "19")),
        listOf("合成 QA 材料：三角形面积"))
    check(file.isFile && file.length() > 0)
}
