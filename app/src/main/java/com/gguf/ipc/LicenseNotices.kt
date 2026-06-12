package com.gguf.ipc

/**
 * License notices for all open-source dependencies.
 * This file contains the required license texts for distribution.
 */
object LicenseNotices {

    val notices = listOf(
        LicenseEntry(
            name = "llama.cpp",
            version = "b9578+",
            license = "MIT License",
            copyright = "Copyright (c) 2023-2026 The ggml authors",
            url = "https://github.com/ggml-org/llama.cpp",
            text = """
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
            """.trimIndent()
        ),
        LicenseEntry(
            name = "ggml",
            version = "latest",
            license = "MIT License",
            copyright = "Copyright (c) 2023-2026 The ggml authors",
            url = "https://github.com/ggml-org/ggml",
            text = "Same as llama.cpp (MIT License)"
        ),
        LicenseEntry(
            name = "MNN (Alibaba)",
            version = "3.5.0+",
            license = "Apache License 2.0",
            copyright = "Copyright 2018 Alibaba Group",
            url = "https://github.com/alibaba/MNN",
            text = """
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            """.trimIndent()
        ),
        LicenseEntry(
            name = "LiteRT-LM (Google)",
            version = "latest",
            license = "Apache License 2.0",
            copyright = "Copyright Google LLC",
            url = "https://github.com/google-ai-edge/LiteRT-LM",
            text = """
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            """.trimIndent()
        ),
        LicenseEntry(
            name = "whisper.cpp",
            version = "latest",
            license = "MIT License",
            copyright = "Copyright (c) 2023 The ggml authors",
            url = "https://github.com/ggml-org/whisper.cpp",
            text = "Same as llama.cpp (MIT License)"
        ),
        LicenseEntry(
            name = "clip.cpp",
            version = "latest",
            license = "MIT License",
            copyright = "Copyright (c) 2023 The ggml authors",
            url = "https://github.com/ggerganov/llama.cpp",
            text = "Same as llama.cpp (MIT License)"
        )
    )

    fun getFormattedNotices(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Open Source Licenses ===")
        sb.appendLine()
        for (notice in notices) {
            sb.appendLine("--- ${notice.name} (${notice.version}) ---")
            sb.appendLine("License: ${notice.license}")
            sb.appendLine("Copyright: ${notice.copyright}")
            sb.appendLine("URL: ${notice.url}")
            sb.appendLine()
            sb.appendLine(notice.text)
            sb.appendLine()
        }
        return sb.toString()
    }

    fun getShortNotices(): String {
        return notices.joinToString("\n") { "${it.name} — ${it.license}" }
    }
}

data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val copyright: String,
    val url: String,
    val text: String
)
