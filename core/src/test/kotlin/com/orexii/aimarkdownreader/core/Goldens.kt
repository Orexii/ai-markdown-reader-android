package com.orexii.aimarkdownreader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Test fixtures: the goldens made by tools/goldens.mjs from the extension's TypeScript, and the extension's files. */
object Goldens {
    val root: File = File(System.getProperty("amr.root") ?: "..").absoluteFile

    fun json(name: String): JsonElement = Json.parseToJsonElement(text(name))

    fun text(name: String): String = javaClass.getResource("/goldens/$name")?.readText() ?: error("Missing golden $name; run npm run goldens.")

    val documents: Map<String, ReaderDocument> by lazy {
        json("documents.json").jsonObject.mapValues { (_, value) -> ReaderDocument.parse(value.toString()) }
    }

    private val lexicons = HashMap<Accent, Lexicon>()

    @Synchronized
    fun lexicon(accent: Accent): Lexicon = lexicons.getOrPut(accent) {
        val file = File(root, "extension/lexicon/${accent.name.lowercase()}.json")
        Lexicon(LexiconData.parse(file.readText()), accent == Accent.GB)
    }

    /** The host build of the eSpeak JNI library, when tools/espeak-data.sh has built it. */
    val espeakLibrary: File? = System.getProperty("amr.espeakHostLib")?.let(::File)?.takeIf { it.isFile }

    val espeakData: File = File(root, "espeak/src/main/assets")
}

fun JsonElement.string(): String = jsonPrimitive.content
fun JsonElement.stringOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.contentOrNull
fun JsonElement.bool(): Boolean = jsonPrimitive.booleanOrNull ?: error("Not a boolean: $this")
fun JsonElement.boolOrNull(): Boolean? = if (this is JsonNull) null else jsonPrimitive.booleanOrNull
fun JsonElement.number(): Double = jsonPrimitive.double
fun JsonElement.integer(): Int = jsonPrimitive.int
fun JsonElement.array(): JsonArray = jsonArray
fun JsonElement.obj(): JsonObject = jsonObject
fun JsonElement.isNull(): Boolean = this is JsonNull
fun JsonElement.isString(): Boolean = this is JsonPrimitive && isString
