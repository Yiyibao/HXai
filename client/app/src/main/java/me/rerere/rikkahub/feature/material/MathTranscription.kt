package me.rerere.rikkahub.feature.material

/** A confirmed page needs visible input; Unicode formatting alone is not handwriting. */
internal object MathTranscription {
    fun requireContent(text: String, maximum: Int) {
        require(text.length in 1..maximum) { "转录为空或超过范围" }
        var visible = false
        var offset = 0
        while (offset < text.length) {
            val point = Character.codePointAt(text, offset)
            require(point == 9 || point == 10 || point == 13 || point in 0x20..0xD7FF ||
                point in 0xE000..0xFFFD || point in 0x10000..0x10FFFF) { "转录包含无效Unicode字符" }
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point) &&
                Character.getType(point) != Character.FORMAT.toInt() && !Character.isISOControl(point)) visible = true
            offset += Character.charCount(point)
        }
        require(visible) { "转录只有空白或不可见字符，请核对原页；不能确认完整输入" }
    }
}
