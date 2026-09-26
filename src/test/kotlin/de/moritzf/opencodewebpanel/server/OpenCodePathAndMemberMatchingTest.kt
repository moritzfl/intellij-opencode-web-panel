package de.moritzf.opencodewebpanel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class OpenCodePathAndMemberMatchingTest {

    @Test
    fun resolveFileLinkMatchesExactRelativeAndRootRelativePaths() {
        withTree("src/Main.kt", "docs/guide.md") { root ->
            assertEquals(root.file("src/Main.kt"), OpenCodeServerProtocol.resolveFileLink("src/Main.kt", root.toString(), null)?.path)
            assertEquals(root.file("src/Main.kt"), OpenCodeServerProtocol.resolveFileLink("/src/Main.kt", root.toString(), null)?.path)
            assertEquals(root.file("src/Main.kt"), OpenCodeServerProtocol.resolveFileLink("./src/Main.kt", root.toString(), null)?.path)
            assertEquals(root.file("docs/guide.md"), OpenCodeServerProtocol.resolveFileLink("docs/guide.md", root.toString(), null)?.path)
        }
    }

    @Test
    fun resolveFileLinkNormalizesBackslashPaths() {
        withTree("src/Main.kt") { root ->
            assertEquals(
                root.file("src/Main.kt"),
                OpenCodeServerProtocol.resolveFileLink("src\\Main.kt", root.toString(), null)?.path,
            )
            assertEquals(
                root.file("src/Main.kt"),
                OpenCodeServerProtocol.resolveFileLink("src\\Main.kt:12", root.toString(), null)?.path,
            )
            assertEquals(11, OpenCodeServerProtocol.resolveFileLink("src\\Main.kt:12", root.toString(), null)?.line)
        }
    }

    @Test
    fun resolveFileLinkGuessesMissingLeadingSegments() {
        withTree("packages/app/src/Main.kt") { root ->
            val nested = root.file("packages/app/src/Main.kt")
            assertEquals(nested, OpenCodeServerProtocol.resolveFileLink("app/src/Main.kt", root.toString(), null)?.path)
            assertEquals(nested, OpenCodeServerProtocol.resolveFileLink("src/Main.kt", root.toString(), null)?.path)
            assertEquals(nested, OpenCodeServerProtocol.resolveFileLink("Main.kt", root.toString(), null)?.path)
            val withLine = OpenCodeServerProtocol.resolveFileLink("src/Main.kt:9", root.toString(), null)
            assertEquals(nested, withLine?.path)
            assertEquals(8, withLine?.line)
        }
    }

    @Test
    fun resolveFileLinkDropsLeadingSegmentsWhenBaseAlreadySitsInsideTheReference() {
        withTree("packages/app/src/Main.kt") { root ->
            val nested = root.file("packages/app/src/Main.kt")
            val inner = root.resolve("packages/app").toString()
            assertEquals(nested, OpenCodeServerProtocol.resolveFileLink("packages/app/src/Main.kt", inner, null)?.path)
            assertEquals(nested, OpenCodeServerProtocol.resolveFileLink("app/src/Main.kt", inner, null)?.path)
        }
    }

    @Test
    fun navigationBasesPreserveTheOpenCodeDirectoryAndReachSiblingModulesInTheIdeProject() {
        withTree(
            "client-app/src/Main.java",
            "shared-library/src/DocumentMapper.java",
            "shared-library/resources/Document.xsd",
        ) { root ->
            val selected = root.resolve("client-app").toString()
            val bases = listOf(selected, root.toString())
            fun resolve(ref: String) = OpenCodeServerProtocol.resolveFileLinkWithBases(ref, bases)

            assertEquals(root.file("client-app/src/Main.java"), resolve("src/Main.java")?.path)
            assertEquals(
                root.file("shared-library/src/DocumentMapper.java"),
                resolve("shared-library/src/DocumentMapper.java:42")?.path,
            )
            assertEquals(41, resolve("shared-library/src/DocumentMapper.java:42")?.line)
            assertEquals(root.file("shared-library/resources/Document.xsd"), resolve("shared-library/resources/Document.xsd")?.path)
        }
    }

    @Test
    fun resolveFileLinkPrefersTheLongestMatchingSubpath() {
        withTree("a/src/Main.kt", "b/other/Main.kt", "c/src/nested/Main.kt") { root ->
            assertEquals(
                root.file("a/src/Main.kt"),
                OpenCodeServerProtocol.resolveFileLink("src/Main.kt", root.toString(), null)?.path,
            )
            assertEquals(
                root.file("c/src/nested/Main.kt"),
                OpenCodeServerProtocol.resolveFileLink("src/nested/Main.kt", root.toString(), null)?.path,
            )
        }
    }

    @Test
    fun resolveFileLinkFindsExtensionlessClassSubpaths() {
        withTree("packages/app/src/Foo.kt") { root ->
            assertEquals(
                root.file("packages/app/src/Foo.kt"),
                OpenCodeServerProtocol.resolveFileLink("src/Foo", root.toString(), null)?.path,
            )
            assertEquals(
                root.file("packages/app/src/Foo.kt"),
                OpenCodeServerProtocol.resolveFileLink("Foo", root.toString(), null)?.path,
            )
        }
    }

    @Test
    fun resolveFileLinkDoesNotTreatADifferentExtensionAsTheSameFile() {
        withTree("src/Main.java") { root ->
            assertNull(OpenCodeServerProtocol.resolveFileLink("src/Main.kt", root.toString(), null))
        }
    }

    @Test
    fun resolveFileLinkKeepsAnExactMatchOverANestedGuess() {
        withTree("src/Main.kt", "packages/app/src/Main.kt") { root ->
            assertEquals(
                root.file("src/Main.kt"),
                OpenCodeServerProtocol.resolveFileLink("src/Main.kt", root.toString(), null)?.path,
            )
        }
    }

    @Test
    fun resolveFileLinkOpensMarkdownHrefsFromChat() {
        withTree("README.md", "docs/guide.md", "docs/My File.md") { root ->
            val readme = root.file("README.md")
            val guide = root.file("docs/guide.md")
            val spaced = root.file("docs/My File.md")

            assertEquals(readme, OpenCodeServerProtocol.resolveFileLink("README.md", root.toString(), null)?.path)
            assertEquals(readme, OpenCodeServerProtocol.resolveFileLink("./README.md", root.toString(), null)?.path)
            assertEquals(guide, OpenCodeServerProtocol.resolveFileLink("docs/guide.md", root.toString(), null)?.path)
            assertEquals(guide, OpenCodeServerProtocol.resolveFileLink("guide.md", root.toString(), null)?.path)
            assertEquals(spaced, OpenCodeServerProtocol.resolveFileLink("docs/My%20File.md", root.toString(), null)?.path)

            val heading = OpenCodeServerProtocol.resolveFileLink("docs/guide.md#installation", root.toString(), null)
            assertEquals(guide, heading?.path)
            assertNull(heading?.line)

            val line = OpenCodeServerProtocol.resolveFileLink("docs/guide.md#L12", root.toString(), null)
            assertEquals(guide, line?.path)
            assertEquals(11, line?.line)

            val codeRef = OpenCodeServerProtocol.parseCodeReference("docs/guide.md")!!
            assertEquals("md", codeRef.extension)
            assertEquals(guide, OpenCodeServerProtocol.resolveFileLink(codeRef.path, root.toString(), null)?.path)
        }
    }

    @Test
    fun parseCodeReferenceUnderstandsPathClassAndMemberShapes() {
        fun ref(text: String) = OpenCodeServerProtocol.parseCodeReference(text)!!

        val path = ref("packages/app/src/Main.kt:10:2")
        assertEquals("packages/app/src/Main.kt", path.path)
        assertEquals("Main.kt", path.fileName)
        assertEquals("kt", path.extension)
        assertEquals(9, path.line)
        assertEquals(1, path.column)
        assertTrue(path.hasPath)
        assertNull(path.memberName)

        val simple = ref("OpenCodeIdeNavigation")
        assertEquals("OpenCodeIdeNavigation", simple.fileName)
        assertNull(simple.extension)
        assertNull(simple.qualifiedName)

        val qualified = ref("de.moritzf.opencodewebpanel.features.OpenCodeIdeNavigation")
        assertEquals("OpenCodeIdeNavigation", qualified.fileName)
        assertEquals(qualified.path, qualified.qualifiedName)

        val dotted = ref("OpenCodeIdeNavigation.openFileLinkInIde")
        assertEquals("OpenCodeIdeNavigation", dotted.fileName)
        assertEquals("openFileLinkInIde", dotted.memberName)

        val hash = ref("OpenCodeIdeNavigation#openFileLinkInIde")
        assertEquals("openFileLinkInIde", hash.memberName)

        val call = ref("OpenCodeIdeNavigation.openFileLinkInIde()")
        assertEquals("openFileLinkInIde", call.memberName)

        val spacedCall = ref("OpenCodeIdeNavigation.openFileLinkInIde ()")
        assertEquals("openFileLinkInIde", spacedCall.memberName)

        val qualifiedCall = ref("de.moritzf.Foo.bar(Boolean.TRUE)")
        assertEquals("Foo", qualifiedCall.fileName)
        assertEquals("de.moritzf.Foo", qualifiedCall.qualifiedName)
        assertEquals("bar", qualifiedCall.memberName)

        val innerCall = ref("Foo.Bar.baz()")
        assertEquals("Bar", innerCall.fileName)
        assertEquals("Foo.Bar", innerCall.qualifiedName)
        assertEquals("baz", innerCall.memberName)

        val withLine = ref("OpenCodeIdeNavigation.openFileLinkInIde():42")
        assertEquals("OpenCodeIdeNavigation", withLine.fileName)
        assertEquals("openFileLinkInIde", withLine.memberName)
        assertEquals(41, withLine.line)

        val hashLine = ref("OpenCodeIdeNavigation#openFileLinkInIde:L10")
        assertEquals("openFileLinkInIde", hashLine.memberName)
        assertEquals(9, hashLine.line)

        val backslash = ref("src\\Main.kt:3")
        assertEquals("src\\Main.kt", backslash.path)
        assertTrue(backslash.hasPath)
        assertEquals(2, backslash.line)
    }

    @Test
    fun parseCodeReferenceTreatsSchemaNamesAsFilesRatherThanTypeMembers() {
        val schema = OpenCodeServerProtocol.parseCodeReference("Document.xsd:L1063")!!
        assertEquals("Document.xsd", schema.path)
        assertEquals("Document.xsd", schema.fileName)
        assertEquals("xsd", schema.extension)
        assertNull(schema.memberName)
        assertEquals(1062, schema.line)
        assertEquals(listOf("Document.xsd"), OpenCodeServerProtocol.codeReferenceFileNames(schema))
    }

    @Test
    fun codeReferenceFileNamesAddSourceSuffixesAndOuterTypes() {
        val simple = OpenCodeServerProtocol.parseCodeReference("OpenCodeIdeNavigation")!!
        assertEquals("OpenCodeIdeNavigation", OpenCodeServerProtocol.codeReferenceFileNames(simple).first())
        assertTrue(OpenCodeServerProtocol.codeReferenceFileNames(simple).contains("OpenCodeIdeNavigation.kt"))
        assertTrue(OpenCodeServerProtocol.codeReferenceFileNames(simple).contains("OpenCodeIdeNavigation.java"))

        val file = OpenCodeServerProtocol.parseCodeReference("src/Main.kt")!!
        assertEquals(listOf("Main.kt"), OpenCodeServerProtocol.codeReferenceFileNames(file))

        val inner = OpenCodeServerProtocol.parseCodeReference("Foo.Bar.baz()")!!
        val innerNames = OpenCodeServerProtocol.codeReferenceFileNames(inner)
        assertTrue(innerNames.contains("Bar.kt"))
        assertTrue(innerNames.contains("Foo.kt"))

        val companion = OpenCodeServerProtocol.parseCodeReference("Foo.Companion.bar()")!!
        val companionNames = OpenCodeServerProtocol.codeReferenceFileNames(companion)
        assertTrue(companionNames.contains("Companion.kt"))
        assertTrue(companionNames.contains("Foo.kt"))
    }

    @Test
    fun pickDistinctPathUsesPackageSegmentsOfAQualifiedClass() {
        assertEquals(
            "/repo/src/de/moritzf/Foo.kt",
            OpenCodeServerProtocol.pickDistinctPath(
                listOf("/repo/src/de/moritzf/Foo.kt", "/repo/src/other/Foo.kt"),
                "de.moritzf.Foo",
            ),
        )
        val parsed = OpenCodeServerProtocol.parseCodeReference("de.moritzf.Foo.bar()")!!
        assertEquals("de.moritzf.Foo", parsed.path)
        assertEquals(
            "/repo/src/de/moritzf/Foo.kt",
            OpenCodeServerProtocol.pickDistinctPath(
                listOf("/repo/src/de/moritzf/Foo.kt", "/repo/src/other/Foo.java"),
                parsed.path,
            ),
        )
    }

    @Test
    fun pickDistinctPathMatchesExtensionlessClassNamesAndIgnoresCase() {
        assertEquals(
            "/repo/src/Main.kt",
            OpenCodeServerProtocol.pickDistinctPath(
                listOf("/repo/src/Main.kt", "/repo/other/Util.kt"),
                "Main",
            ),
        )
        assertEquals(
            "/Repo/SRC/Main.kt",
            OpenCodeServerProtocol.pickDistinctPath(
                listOf("/Repo/SRC/Main.kt", "/repo/other/Main.kt"),
                "src/Main.kt",
            ),
        )
        assertNull(
            OpenCodeServerProtocol.pickDistinctPath(
                listOf("/a/src/Main.kt", "/b/src/Main.kt"),
                "src/Main.kt",
            ),
        )
    }

    @Test
    fun pickDistinctPathPrefersASubpathOverAFilenameHit() {
        assertEquals(
            "/repo/packages/app/src/Main.kt",
            OpenCodeServerProtocol.pickDistinctPath(
                listOf("/repo/packages/app/src/Main.kt", "/repo/other/Main.kt"),
                "src\\Main.kt",
            ),
        )
    }

    @Test
    fun scoreFilePathSuffixRanksQualifiedAndExtensionlessReferences() {
        assertTrue(
            OpenCodeServerProtocol.scoreFilePathSuffix("/repo/src/de/moritzf/Foo.kt", "de.moritzf.Foo") >
                OpenCodeServerProtocol.scoreFilePathSuffix("/repo/src/other/Foo.kt", "de.moritzf.Foo"),
        )
        assertTrue(
            OpenCodeServerProtocol.scoreFilePathSuffix("/repo/src/Foo.kt", "Foo") >
                OpenCodeServerProtocol.scoreFilePathSuffix("/repo/src/Bar.kt", "Foo"),
        )
        assertEquals(0, OpenCodeServerProtocol.scoreFilePathSuffix("/repo/src/Bar.kt", "Foo"))
        assertTrue(OpenCodeServerProtocol.scoreFilePathSuffix("/repo/src/Main.kt", "src/Main") > 1)
    }

    @Test
    fun findMemberLineIndexPrefersDefinitionsOverEarlierCalls() {
        val source = """
            package demo
            foo()
            // fun foo()
            fun foo() {}
            fun bar() {}
        """.trimIndent()
        assertEquals(3, OpenCodeServerProtocol.findMemberLineIndex(source, "foo"))
        assertEquals(4, OpenCodeServerProtocol.findMemberLineIndex(source, "bar"))
        assertNull(OpenCodeServerProtocol.findMemberLineIndex(source, "missing"))
    }

    @Test
    fun findMemberLineIndexUnderstandsKotlinJavaAndPythonShapes() {
        val kotlin = """
            class Host {
                fun <T> openFileLinkInIde(href: String?) {}
                fun OpenCodeIdeNavigation.other() {}
                val token = 1
            }
        """.trimIndent()
        assertEquals(1, OpenCodeServerProtocol.findMemberLineIndex(kotlin, "openFileLinkInIde"))
        assertEquals(2, OpenCodeServerProtocol.findMemberLineIndex(kotlin, "other"))
        assertEquals(3, OpenCodeServerProtocol.findMemberLineIndex(kotlin, "token"))

        val java = """
            public class Host {
                foo();
                public void foo(String href) {}
            }
        """.trimIndent()
        assertEquals(2, OpenCodeServerProtocol.findMemberLineIndex(java, "foo"))

        val python = "def parse_path(value):\n    return value\n"
        assertEquals(0, OpenCodeServerProtocol.findMemberLineIndex(python, "parse_path"))

        val crlf = "package demo\r\nfun foo() {}\r\n"
        assertEquals(1, OpenCodeServerProtocol.findMemberLineIndex(crlf, "foo"))
    }

    @Test
    fun classAndMethodPipelinePicksTheTypeFileAndMemberLine() {
        val candidates = listOf(
            "/repo/src/de/moritzf/OpenCodeIdeNavigation.kt",
            "/repo/src/other/OpenCodeIdeNavigation.kt",
            "/repo/src/de/moritzf/Foo.kt",
        )
        val source = """
            package de.moritzf
            class OpenCodeIdeNavigation {
                fun openFileLinkInIde(href: String?) {}
            }
        """.trimIndent()

        val parsed = OpenCodeServerProtocol.parseCodeReference("de.moritzf.OpenCodeIdeNavigation.openFileLinkInIde()")!!
        val names = OpenCodeServerProtocol.codeReferenceFileNames(parsed).toSet()
        val matching = candidates.filter { path ->
            path.substringAfterLast('/') in names
        }
        assertEquals(
            "/repo/src/de/moritzf/OpenCodeIdeNavigation.kt",
            OpenCodeServerProtocol.pickDistinctPath(matching, parsed.path),
        )
        assertEquals(2, OpenCodeServerProtocol.findMemberLineIndex(source, parsed.memberName!!))
    }

    @Test
    fun innerClassMethodFallsBackToTheOuterTypeFile() {
        val parsed = OpenCodeServerProtocol.parseCodeReference("Foo.Bar.baz()")!!
        val names = OpenCodeServerProtocol.codeReferenceFileNames(parsed).toSet()
        val picked = OpenCodeServerProtocol.pickDistinctPath(
            listOf("/repo/src/Foo.kt").filter { it.substringAfterLast('/') in names },
            parsed.path,
        )
        assertEquals("/repo/src/Foo.kt", picked)
        val source = """
            class Foo {
                class Bar {
                    fun baz() {}
                }
            }
        """.trimIndent()
        assertEquals(2, OpenCodeServerProtocol.findMemberLineIndex(source, parsed.memberName!!))
    }

    @Test
    fun resolveFileLinkGuessesAndroidAndTypescriptLayouts() {
        withTree(
            "app/src/main/java/com/example/Foo.java",
            "packages/web/src/components/Button.tsx",
        ) { root ->
            assertEquals(
                root.file("app/src/main/java/com/example/Foo.java"),
                OpenCodeServerProtocol.resolveFileLink("com/example/Foo.java", root.toString(), null)?.path,
            )
            assertEquals(
                root.file("app/src/main/java/com/example/Foo.java"),
                OpenCodeServerProtocol.resolveFileLink("java/com/example/Foo.java:80", root.toString(), null)?.path,
            )
            assertEquals(
                root.file("packages/web/src/components/Button.tsx"),
                OpenCodeServerProtocol.resolveFileLink("components/Button.tsx", root.toString(), null)?.path,
            )
            assertEquals(
                root.file("packages/web/src/components/Button.tsx"),
                OpenCodeServerProtocol.resolveFileLink("src\\components\\Button.tsx", root.toString(), null)?.path,
            )
        }
    }

    @Test
    fun pickDistinctPathUsesJavaPackageFoldersForAQualifiedClass() {
        assertEquals(
            "/app/src/main/java/com/example/Foo.java",
            OpenCodeServerProtocol.pickDistinctPath(
                listOf(
                    "/app/src/main/java/com/example/Foo.java",
                    "/lib/src/main/java/other/Foo.java",
                ),
                "com.example.Foo",
            ),
        )
        assertEquals(
            "/app/src/main/java/com/example/Foo.java",
            OpenCodeServerProtocol.pickDistinctPath(
                listOf(
                    "/app/src/main/java/com/example/Foo.java",
                    "/app/src/test/java/com/example/Foo.java",
                ),
                "com.example.Foo",
            ),
        )
    }

    @Test
    fun findMemberLineIndexFindsBacktickKotlinNames() {
        val source = """
            class Host {
                fun `open file`() {}
            }
        """.trimIndent()
        assertEquals(1, OpenCodeServerProtocol.findMemberLineIndex(source, "open file"))
    }

    @Test
    fun smokeEditExploreAndReviewPathShapes() {
        withTree(
            "src/main/kotlin/Foo.kt",
            "src/test/kotlin/Foo.kt",
            "docs/guide.md",
            "packages/app/src/Main.kt",
        ) { root ->
            val foo = root.file("src/main/kotlin/Foo.kt")
            val main = root.file("packages/app/src/Main.kt")
            val guide = root.file("docs/guide.md")
            val absFoo = foo.toAbsolutePath().toString()
            val absMain = main.toAbsolutePath().toString()

            fun resolve(ref: String) = OpenCodeServerProtocol.resolveFileLink(ref, root.toString(), null)
            fun parsed(ref: String) = OpenCodeServerProtocol.parseCodeReference(ref)!!

            assertEquals(foo, resolve(absFoo)?.path)
            assertEquals(foo, resolve("$absFoo:")?.path)
            assertEquals(foo, resolve("src/main/kotlin/Foo.kt")?.path)
            assertEquals(6, resolve("src/main/kotlin/Foo.kt:7")?.line)
            assertEquals(foo, resolve("main/kotlin/Foo.kt")?.path)
            assertEquals(foo, resolve("\u202Asrc/main/kotlin/Foo.kt\u202C")?.path)

            assertEquals(absFoo, OpenCodeServerProtocol.normalizeNavigablePath("$absFoo:"))
            assertEquals("Foo.kt:12", OpenCodeServerProtocol.normalizeNavigablePath("at com.example.Foo.bar(Foo.kt:12)"))
            assertEquals("src/Foo.kt:10:5", OpenCodeServerProtocol.normalizeNavigablePath("src/Foo.kt:10:5:"))
            assertEquals("/tmp/x.py:8", OpenCodeServerProtocol.normalizeNavigablePath("File \"/tmp/x.py\", line 8"))

            val stack = parsed("at com.example.Foo.bar(Foo.kt:12)")
            assertEquals("Foo.kt", stack.fileName)
            assertEquals(11, stack.line)

            val gcc = parsed("src/main/kotlin/Foo.kt:10:5:")
            assertEquals("src/main/kotlin/Foo.kt", gcc.path)
            assertEquals(9, gcc.line)
            assertEquals(4, gcc.column)

            val python = parsed("File \"src/main/kotlin/Foo.kt\", line 3")
            assertEquals("src/main/kotlin/Foo.kt", python.path)
            assertEquals(2, python.line)

            assertEquals(main, resolve(absMain)?.path)
            assertEquals(main, resolve("packages/app/src/Main.kt")?.path)
            assertEquals(guide, resolve("docs/guide.md#overview")?.path)
            assertEquals(
                foo,
                OpenCodeServerProtocol.pickDistinctPath(
                    listOf(foo.toString(), root.file("src/test/kotlin/Foo.kt").toString()),
                    "src/main/kotlin/Foo.kt",
                )?.let { java.nio.file.Path.of(it).normalize() },
            )
        }
    }

    @Test
    fun fileLinkPathAliasesIncludeSlashNormalizedSpellings() {
        val aliases = OpenCodeServerProtocol.fileLinkPathAliases("src\\Main.kt")
        assertTrue(aliases.contains("src\\Main.kt"))
        assertTrue(aliases.contains("src/Main.kt"))
    }

    @Test
    fun parseCodeReferenceHandlesAbsolutePathWithLine() {
        val ref = OpenCodeServerProtocol.parseCodeReference("/tmp/project/src/Main.kt:42")!!

        assertEquals("Main.kt", ref.fileName)
        assertEquals("/tmp/project/src/Main.kt", ref.path)
        assertNull(ref.qualifiedName)
        assertEquals("kt", ref.extension)
        assertEquals(41, ref.line)
        assertTrue(ref.hasPath)
    }

    @Test
    fun parseCodeReferenceUnderstandsCommonLocators() {
        fun loc(text: String) = OpenCodeServerProtocol.parseCodeReference(text)!!

        val colon = loc("src/main.ts:42")
        assertEquals("src/main.ts", colon.path)
        assertEquals(41, colon.line)
        assertNull(colon.column)

        val colonCol = loc("src/main.ts:42:13")
        assertEquals("src/main.ts", colonCol.path)
        assertEquals(41, colonCol.line)
        assertEquals(12, colonCol.column)

        val github = loc("src/main.ts#L42")
        assertEquals("src/main.ts", github.path)
        assertEquals(41, github.line)

        val githubRange = loc("src/main.ts#L42-L57")
        assertEquals("src/main.ts", githubRange.path)
        assertEquals(41, githubRange.line)

        val lRange = loc("Foo.java:L123-1234")
        assertEquals("Foo.java", lRange.path)
        assertEquals("java", lRange.extension)
        assertEquals(122, lRange.line)

        val vs = loc("src/main.ts(42)")
        assertEquals("src/main.ts", vs.path)
        assertEquals(41, vs.line)
        assertNull(vs.column)

        val msvc = loc("src/main.ts(42,13)")
        assertEquals("src/main.ts", msvc.path)
        assertEquals(41, msvc.line)
        assertEquals(12, msvc.column)

        val windows = loc("""C:\proj\Foo.java:L10""")
        assertEquals("""C:\proj\Foo.java""", windows.path)
        assertEquals(9, windows.line)

        val parenL = loc("Foo.java(L98)")
        assertEquals("Foo.java", parenL.path)
        assertEquals(97, parenL.line)

        val colonNoL = loc("production.xsd:16488")
        assertEquals("production.xsd", colonNoL.path)
        assertEquals(16487, colonNoL.line)
    }

    @Test
    fun parseCodeReferenceStripsMethodCallToType() {
        val method = OpenCodeServerProtocol.parseCodeReference("PackagingMailingBarcodeDefinition.isWithScanRule()")!!
        assertEquals("PackagingMailingBarcodeDefinition", method.fileName)
        assertEquals("PackagingMailingBarcodeDefinition", method.path)
        assertEquals("isWithScanRule", method.memberName)
        assertNull(method.qualifiedName)
        assertNull(method.extension)
        assertNull(method.line)

        val withLine = OpenCodeServerProtocol.parseCodeReference("PackagingMailingBarcodeDefinition.isWithScanRule()(L98)")!!
        assertEquals("PackagingMailingBarcodeDefinition", withLine.fileName)
        assertEquals(97, withLine.line)

        val qualified = OpenCodeServerProtocol.parseCodeReference("java.util.Optional.of(Boolean.TRUE)")!!
        assertEquals("Optional", qualified.fileName)
        assertEquals("java.util.Optional", qualified.path)
        assertEquals("java.util.Optional", qualified.qualifiedName)
        assertEquals("of", qualified.memberName)
        assertNull(qualified.extension)
    }

    @Test
    fun parseCodeReferenceKeepsLowercaseCallUnchanged() {
        val ref = OpenCodeServerProtocol.parseCodeReference("handle(CurrentSheetField)")!!
        assertEquals("handle(CurrentSheetField)", ref.fileName)
        assertEquals("handle(CurrentSheetField)", ref.path)
        assertNull(ref.extension)
    }

    @Test
    fun parseCodeReferenceAllowsSpacesWhenExtensionPresent() {
        val ref = OpenCodeServerProtocol.parseCodeReference("Manuelle Verpackung und PM-Mobile.xml:L1063")!!
        assertEquals("Manuelle Verpackung und PM-Mobile.xml", ref.path)
        assertEquals("xml", ref.extension)
        assertEquals(1062, ref.line)
    }

    @Test
    fun parseCodeReferenceReturnsNullForBlankInput() {
        assertNull(OpenCodeServerProtocol.parseCodeReference(""))
        assertNull(OpenCodeServerProtocol.parseCodeReference("   "))
    }

    @Test
    fun scoreFilePathSuffixPrefersTheLongerTrailingMatch() {
        assertTrue(
            OpenCodeServerProtocol.scoreFilePathSuffix("/repo/packages/app/src/Main.kt", "src/Main.kt") >
                OpenCodeServerProtocol.scoreFilePathSuffix("/repo/other/Main.kt", "src/Main.kt"),
        )
        assertEquals(0, OpenCodeServerProtocol.scoreFilePathSuffix("/repo/other/Util.kt", "src/Main.kt"))
    }

    private fun withTree(vararg files: String, block: (Path) -> Unit) {
        val root = Files.createTempDirectory("opencode-match")
        try {
            files.forEach { rel ->
                val path = root.resolve(rel)
                Files.createDirectories(path.parent)
                Files.writeString(path, "x")
            }
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun Path.file(rel: String): Path = resolve(rel).normalize()
}
