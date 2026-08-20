package com.quickgit.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

/**
 * Lightweight language-aware syntax highlighting for code editors/readers.
 * Pure Compose AnnotatedString — no extra dependencies.
 */
object SyntaxHighlight {

    data class Palette(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val type: Color,
        val function: Color,
        val annotation: Color,
        val punctuation: Color,
        val default: Color
    )

    fun defaultPalette(base: Color, dark: Boolean): Palette {
        return if (dark) {
            Palette(
                keyword = Color(0xFFFF7B72),
                string = Color(0xFFA5D6FF),
                comment = Color(0xFF8B949E),
                number = Color(0xFF79C0FF),
                type = Color(0xFFFFA657),
                function = Color(0xFFD2A8FF),
                annotation = Color(0xFF7EE787),
                punctuation = Color(0xFFC9D1D9),
                default = base
            )
        } else {
            Palette(
                keyword = Color(0xFFCF222E),
                string = Color(0xFF0A3069),
                comment = Color(0xFF6E7781),
                number = Color(0xFF0550AE),
                type = Color(0xFF953800),
                function = Color(0xFF8250DF),
                annotation = Color(0xFF116329),
                punctuation = Color(0xFF24292F),
                default = base
            )
        }
    }

    /** Map a file path / name to a language key used by [highlight]. */
    fun languageFromPath(path: String): String {
        val name = path.substringAfterLast('/').lowercase()
        val ext = name.substringAfterLast('.', "")
        return when {
            name == "dockerfile" || name.startsWith("dockerfile.") -> "dockerfile"
            name == "makefile" || name == "gnumakefile" -> "makefile"
            name.endsWith(".gradle.kts") || ext == "kts" -> "kotlin"
            name.endsWith(".gradle") -> "groovy"
            else -> when (ext) {
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "js", "mjs", "cjs" -> "javascript"
                "ts", "tsx" -> "typescript"
                "jsx" -> "javascript"
                "py", "pyw" -> "python"
                "rb" -> "ruby"
                "go" -> "go"
                "rs" -> "rust"
                "c", "h" -> "c"
                "cpp", "cc", "cxx", "hpp", "hxx", "hh" -> "cpp"
                "cs" -> "csharp"
                "swift" -> "swift"
                "php" -> "php"
                "sh", "bash", "zsh" -> "shell"
                "json" -> "json"
                "xml", "html", "htm", "svg" -> "xml"
                "css", "scss", "sass" -> "css"
                "yml", "yaml" -> "yaml"
                "md", "markdown" -> "markdown"
                "sql" -> "sql"
                "toml" -> "toml"
                "properties", "ini", "cfg", "conf" -> "properties"
                "dart" -> "dart"
                "lua" -> "lua"
                "r" -> "r"
                "pl", "pm" -> "perl"
                "scala" -> "scala"
                "groovy" -> "groovy"
                else -> "plain"
            }
        }
    }

    private val KEYWORDS = mapOf(
        "kotlin" to setOf(
            "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "interface", "is", "null", "object", "package", "return", "super",
            "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when",
            "while", "by", "catch", "constructor", "delegate", "dynamic", "field", "file",
            "finally", "get", "import", "init", "param", "property", "receiver", "set",
            "setparam", "where", "actual", "abstract", "annotation", "companion", "const",
            "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline",
            "inner", "internal", "lateinit", "noinline", "open", "operator", "out", "override",
            "private", "protected", "public", "reified", "sealed", "suspend", "tailrec",
            "vararg", "it"
        ),
        "java" to setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true",
            "false", "null", "var", "record", "sealed", "permits", "yield"
        ),
        "javascript" to setOf(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "export", "extends", "false", "finally", "for", "function",
            "if", "import", "in", "instanceof", "let", "new", "null", "return", "super",
            "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with",
            "yield", "async", "await", "of", "static", "get", "set", "from", "as"
        ),
        "typescript" to setOf(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "export", "extends", "false", "finally", "for", "function",
            "if", "import", "in", "instanceof", "let", "new", "null", "return", "super",
            "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with",
            "yield", "async", "await", "of", "static", "get", "set", "from", "as", "type",
            "interface", "enum", "implements", "private", "protected", "public", "readonly",
            "namespace", "module", "declare", "abstract", "keyof", "infer", "never", "unknown",
            "any", "string", "number", "boolean", "symbol", "bigint"
        ),
        "python" to setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
            "raise", "return", "try", "while", "with", "yield", "match", "case"
        ),
        "go" to setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
            "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
            "return", "select", "struct", "switch", "type", "var", "true", "false", "nil", "iota"
        ),
        "rust" to setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
            "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
            "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
            "trait", "true", "type", "unsafe", "use", "where", "while", "abstract", "become",
            "box", "do", "final", "macro", "override", "priv", "typeof", "unsized", "virtual",
            "yield", "try"
        ),
        "c" to setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
            "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
            "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "_Bool",
            "_Complex", "_Imaginary"
        ),
        "cpp" to setOf(
            "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "bool",
            "break", "case", "catch", "char", "char8_t", "char16_t", "char32_t", "class",
            "compl", "concept", "const", "consteval", "constexpr", "constinit", "const_cast",
            "continue", "co_await", "co_return", "co_yield", "decltype", "default", "delete",
            "do", "double", "dynamic_cast", "else", "enum", "explicit", "export", "extern",
            "false", "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable",
            "namespace", "new", "noexcept", "not", "not_eq", "nullptr", "operator", "or",
            "or_eq", "private", "protected", "public", "register", "reinterpret_cast",
            "requires", "return", "short", "signed", "sizeof", "static", "static_assert",
            "static_cast", "struct", "switch", "template", "this", "thread_local", "throw",
            "true", "try", "typedef", "typeid", "typename", "union", "unsigned", "using",
            "virtual", "void", "volatile", "wchar_t", "while", "xor", "xor_eq"
        ),
        "csharp" to setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char",
            "checked", "class", "const", "continue", "decimal", "default", "delegate", "do",
            "double", "else", "enum", "event", "explicit", "extern", "false", "finally",
            "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int",
            "interface", "internal", "is", "lock", "long", "namespace", "new", "null",
            "object", "operator", "out", "override", "params", "private", "protected",
            "public", "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof",
            "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
            "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using",
            "virtual", "void", "volatile", "while", "var", "async", "await", "nameof",
            "when", "where", "yield", "record", "init", "required"
        ),
        "swift" to setOf(
            "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func",
            "import", "init", "inout", "internal", "let", "open", "operator", "private",
            "protocol", "public", "rethrows", "static", "struct", "subscript", "typealias",
            "var", "break", "case", "continue", "default", "defer", "do", "else", "fallthrough",
            "for", "guard", "if", "in", "repeat", "return", "switch", "where", "while", "as",
            "Any", "catch", "false", "is", "nil", "super", "self", "Self", "throw", "throws",
            "true", "try", "async", "await", "actor"
        ),
        "ruby" to setOf(
            "BEGIN", "END", "alias", "and", "begin", "break", "case", "class", "def", "defined?",
            "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module",
            "next", "nil", "not", "or", "redo", "rescue", "retry", "return", "self", "super",
            "then", "true", "undef", "unless", "until", "when", "while", "yield"
        ),
        "php" to setOf(
            "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
            "clone", "const", "continue", "declare", "default", "die", "do", "echo", "else",
            "elseif", "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch",
            "endwhile", "eval", "exit", "extends", "final", "finally", "fn", "for", "foreach",
            "function", "global", "goto", "if", "implements", "include", "include_once",
            "instanceof", "insteadof", "interface", "isset", "list", "match", "namespace",
            "new", "or", "print", "private", "protected", "public", "readonly", "require",
            "require_once", "return", "static", "switch", "throw", "trait", "try", "unset",
            "use", "var", "while", "xor", "yield", "true", "false", "null"
        ),
        "shell" to setOf(
            "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while",
            "until", "do", "done", "in", "function", "time", "coproc", "true", "false"
        ),
        "sql" to setOf(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "INSERT", "INTO", "VALUES",
            "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "ALTER", "DROP", "INDEX",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "AS", "ORDER", "BY", "GROUP",
            "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "NULL", "TRUE", "FALSE",
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "DEFAULT", "CHECK",
            "CASCADE", "VIEW", "WITH", "EXISTS", "BETWEEN", "LIKE", "IN", "IS", "ASC", "DESC"
        ),
        "dart" to setOf(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class",
            "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else",
            "enum", "export", "extends", "extension", "external", "factory", "false", "final",
            "finally", "for", "Function", "get", "hide", "if", "implements", "import", "in",
            "interface", "is", "late", "library", "mixin", "new", "null", "on", "operator",
            "part", "required", "rethrow", "return", "set", "show", "static", "super",
            "switch", "sync", "this", "throw", "true", "try", "typedef", "var", "void",
            "while", "with", "yield"
        ),
        "scala" to setOf(
            "abstract", "case", "catch", "class", "def", "do", "else", "extends", "false",
            "final", "finally", "for", "forSome", "if", "implicit", "import", "lazy", "match",
            "new", "null", "object", "override", "package", "private", "protected", "return",
            "sealed", "super", "this", "throw", "trait", "try", "true", "type", "val", "var",
            "while", "with", "yield"
        ),
        "groovy" to setOf(
            "as", "assert", "break", "case", "catch", "class", "const", "continue", "def",
            "default", "do", "else", "enum", "extends", "false", "finally", "for", "goto",
            "if", "implements", "import", "in", "instanceof", "interface", "new", "null",
            "package", "return", "super", "switch", "this", "throw", "throws", "trait",
            "true", "try", "while"
        )
    )

    private val LINE_COMMENT = mapOf(
        "kotlin" to "//", "java" to "//", "javascript" to "//", "typescript" to "//",
        "go" to "//", "rust" to "//", "c" to "//", "cpp" to "//", "csharp" to "//",
        "swift" to "//", "dart" to "//", "scala" to "//", "groovy" to "//",
        "python" to "#", "ruby" to "#", "shell" to "#", "yaml" to "#", "toml" to "#",
        "properties" to "#", "r" to "#", "perl" to "#", "dockerfile" to "#", "makefile" to "#"
    )

    private val BLOCK_COMMENT_START = mapOf(
        "kotlin" to "/*", "java" to "/*", "javascript" to "/*", "typescript" to "/*",
        "go" to "/*", "rust" to "/*", "c" to "/*", "cpp" to "/*", "csharp" to "/*",
        "swift" to "/*", "dart" to "/*", "scala" to "/*", "groovy" to "/*", "css" to "/*",
        "sql" to "/*"
    )

    fun highlight(text: String, language: String, palette: Palette): AnnotatedString {
        if (text.isEmpty() || language == "plain") {
            return AnnotatedString(text, SpanStyle(color = palette.default))
        }
        // Cap work on huge files so UI stays responsive
        if (text.length > 200_000) {
            return AnnotatedString(text, SpanStyle(color = palette.default))
        }

        val builder = AnnotatedString.Builder()
        val keywords = KEYWORDS[language].orEmpty()
        val lineComment = LINE_COMMENT[language]
        val blockStart = BLOCK_COMMENT_START[language]
        val blockEnd = if (blockStart != null) "*/" else null
        val hashComment = language == "yaml" || language == "toml" || language == "properties"
            || language == "shell" || language == "python" || language == "ruby"
            || language == "dockerfile" || language == "makefile"

        var i = 0
        val n = text.length
        while (i < n) {
            // Block comment
            if (blockStart != null && text.startsWith(blockStart, i)) {
                val end = text.indexOf(blockEnd!!, i + 2).let { if (it < 0) n else it + 2 }
                builder.withStyle(SpanStyle(color = palette.comment)) {
                    append(text.substring(i, end))
                }
                i = end
                continue
            }
            // Line comment
            if (lineComment != null && text.startsWith(lineComment, i)) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                builder.withStyle(SpanStyle(color = palette.comment)) {
                    append(text.substring(i, end))
                }
                i = end
                continue
            }
            if (hashComment && text[i] == '#' && (i == 0 || text[i - 1] == '\n' || text[i - 1].isWhitespace())) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                builder.withStyle(SpanStyle(color = palette.comment)) {
                    append(text.substring(i, end))
                }
                i = end
                continue
            }
            // Strings
            val ch = text[i]
            if (ch == '"' || ch == '\'' || (ch == '`' && (language == "javascript" || language == "typescript" || language == "shell"))) {
                val quote = ch
                var j = i + 1
                while (j < n) {
                    if (text[j] == '\\' && j + 1 < n) {
                        j += 2
                        continue
                    }
                    if (text[j] == quote) {
                        j++
                        break
                    }
                    if (quote != '`' && text[j] == '\n') break
                    j++
                }
                builder.withStyle(SpanStyle(color = palette.string)) {
                    append(text.substring(i, j))
                }
                i = j
                continue
            }
            // Annotation / decorator
            if ((ch == '@' || (ch == '#' && language == "csharp")) && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j++
                builder.withStyle(SpanStyle(color = palette.annotation)) {
                    append(text.substring(i, j))
                }
                i = j
                continue
            }
            // Numbers
            if (ch.isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_')) {
                var j = i
                while (j < n && (text[j].isDigit() || text[j] == '.' || text[j] == '_' ||
                            text[j] == 'x' || text[j] == 'X' || text[j] == 'b' || text[j] == 'B' ||
                            text[j] in "abcdefABCDEF" || text[j] == 'L' || text[j] == 'f' || text[j] == 'd')) {
                    j++
                }
                builder.withStyle(SpanStyle(color = palette.number)) {
                    append(text.substring(i, j))
                }
                i = j
                continue
            }
            // Identifiers / keywords
            if (ch.isLetter() || ch == '_' || ch == '$') {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$' || text[j] == '?')) j++
                val word = text.substring(i, j)
                val style = when {
                    keywords.contains(word) || (language == "sql" && keywords.contains(word.uppercase())) ->
                        SpanStyle(color = palette.keyword)
                    j < n && text[j] == '(' -> SpanStyle(color = palette.function)
                    word.first().isUpperCase() && word.any { it.isLowerCase() } ->
                        SpanStyle(color = palette.type)
                    else -> SpanStyle(color = palette.default)
                }
                builder.withStyle(style) { append(word) }
                i = j
                continue
            }
            // Markdown headings
            if (language == "markdown" && ch == '#' && (i == 0 || text[i - 1] == '\n')) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                builder.withStyle(SpanStyle(color = palette.keyword)) {
                    append(text.substring(i, end))
                }
                i = end
                continue
            }
            // Default character
            builder.withStyle(SpanStyle(color = palette.default)) {
                append(ch)
            }
            i++
        }
        return builder.toAnnotatedString()
    }

    fun visualTransformation(language: String, palette: Palette): VisualTransformation {
        return VisualTransformation { text ->
            val highlighted = highlight(text.text, language, palette)
            TransformedText(highlighted, OffsetMapping.Identity)
        }
    }
}

/** Remembered palette for a given base text color (keyed on dark/light theme). */
@Composable
fun rememberSyntaxPalette(baseColor: Color): SyntaxHighlight.Palette {
    val dark = isSystemInDarkTheme()
    return remember(dark, baseColor) { SyntaxHighlight.defaultPalette(baseColor, dark) }
}
