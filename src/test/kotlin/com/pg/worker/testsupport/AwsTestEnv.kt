package com.pg.worker.testsupport

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AwsTestEnv {
    private val dotenv: Map<String, String> by lazy { loadDotEnv() }

    fun get(key: String): String? {
        val sys = System.getProperty(key)?.trim()
        if (!sys.isNullOrEmpty()) return sys

        val env = System.getenv(key)?.trim()
        if (!env.isNullOrEmpty()) return env

        return dotenv[key]?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun createSqsClient(regionKey: String = "AWS_REGION", defaultRegion: String = "ap-northeast-2"): SqsClient {
        val region = get(regionKey) ?: defaultRegion
        val accessKey = get("AWS_ACCESS_KEY_ID")
        val secretKey = get("AWS_SECRET_ACCESS_KEY")
        val sessionToken = get("AWS_SESSION_TOKEN")

        val builder = SqsClient.builder().region(Region.of(region))

        if (!accessKey.isNullOrBlank() && !secretKey.isNullOrBlank()) {
            val provider = if (!sessionToken.isNullOrBlank()) {
                StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKey, secretKey, sessionToken)
                )
            } else {
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
                )
            }
            builder.credentialsProvider(provider)
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create())
        }

        return builder.build()
    }

    private fun loadDotEnv(): Map<String, String> {
        val candidates = listOf(
            Paths.get(".env"),
            Paths.get("worker", ".env"),
            Paths.get("..", ".env"),
        )

        val envPath = candidates.firstOrNull { Files.exists(it) } ?: return emptyMap()
        return parse(envPath)
    }

    private fun parse(path: Path): Map<String, String> {
        return Files.readAllLines(path)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().removeSurrounding("\"")
                if (key.isBlank()) null else key to value
            }
            .toMap()
    }
}
