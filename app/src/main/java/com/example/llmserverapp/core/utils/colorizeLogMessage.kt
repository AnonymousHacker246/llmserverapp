
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

fun colorizeLogMessage(message: String, tagColor: Color): AnnotatedString {
    return buildAnnotatedString {

        var i = 0
        while (i < message.length) {
            val c = message[i]

            when {
                // Numbers
                c.isDigit() -> {
                    val start = i
                    while (i < message.length && message[i].isDigit()) i++
                    append(message.substring(start, i))
                    addStyle(
                        SpanStyle(color = Color(0xFF64B5F6)), // blue-ish
                        start,
                        i
                    )
                }

                // Tags like [MODEL], [SERVER], etc.
                c == '[' -> {
                    val start = i
                    while (i < message.length && message[i] != ']') i++
                    if (i < message.length) i++ // include closing bracket
                    append(message.substring(start, i))
                    addStyle(
                        SpanStyle(color = tagColor),
                        start,
                        i
                    )
                }

                else -> {
                    append(c)
                    i++
                }
            }
        }
    }
}
